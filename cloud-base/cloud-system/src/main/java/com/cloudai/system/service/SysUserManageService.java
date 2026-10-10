package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.convert.SysUserConvert;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.entity.SysUserRole;
import com.cloudai.system.mapper.SysDeptMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import com.cloudai.system.vo.SysUserVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SysUserManageService {

    /** 错误码分段：3xxx system，保护域 3013-3017（契约 2026-10-07-builtin-protection-api §4） */
    private static final int ERR_BUILTIN = 3017;

    /** 数据权限轮（契约 2026-10-10-data-permission-api §5.1/§8）：用户保存 deptId 校验 */
    private static final int ERR_DEPT_NOT_FOUND = 3027;

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysDeptMapper deptMapper;
    private final PasswordEncoder passwordEncoder;
    private final TranslationCacheService translationCacheService;

    /**
     * 分页查询（数据权限轮 D14 改型）：mapper VO 直出（LEFT JOIN sys_dept 派生 deptName），
     * 无实体可转——convert 层本链路退役，沿 SysLeaveMapper.pageList 派生列直出 VO 先例。
     */
    public PageResult<SysUserVo> pageList(PageQuery query) {
        IPage<SysUserVo> page = userMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    public SysUserVo findById(Long id) {
        SysUser user = userMapper.findById(id);
        if (user == null) {
            throw new BusinessException(3001, "用户不存在");
        }
        return SysUserConvert.toVo(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public Long save(UserSaveRequest req) {
        if (req.getAccount() == null || req.getAccount().isBlank()
                || req.getPassword() == null || req.getPassword().isBlank()) {
            throw new BusinessException("账号与密码不能为空");
        }
        Long exists = userMapper.countByAccount(req.getAccount());
        if (exists > 0) {
            throw new BusinessException(3002, "账号已存在: " + req.getAccount());
        }
        SysUser user = new SysUser();
        user.setAccount(req.getAccount());
        user.setNickname(req.getNickname() == null ? req.getAccount() : req.getNickname());
        user.setDeptId(requireDeptId(req.getDeptId()));
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setStatus(req.getStatus() == null ? SysUser.StatusEnum.NORMAL.getCode() : req.getStatus());
        // 手写 SQL 无 MetaObjectHandler 自动填充：审计四值显式传入，插入时 update 值 = create 值
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        user.setCreateBy(operator);
        user.setCreateTime(now);
        user.setUpdateBy(operator);
        user.setUpdateTime(now);
        try {
            userMapper.save(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 逻辑删除行仍占用 uk_account，countByAccount 查重看不到——捕获兜底转业务异常
            log.error("唯一键冲突：{}", e.getMessage());
            throw new BusinessException(3002, "账号已存在: " + req.getAccount());
        }
        evictUserTransCache();
        return user.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, UserSaveRequest req) {
        SysUser user = requireUser(id);
        // 内置保护（契约 §2 矩阵，用户域例外）：仅禁停用——按请求值判定（status=1 拒，0/null 放行），nickname 正常更新
        if (Integer.valueOf(SysUser.BuiltinEnum.BUILT_IN.getCode()).equals(user.getIsBuiltin())
                && Integer.valueOf(SysUser.StatusEnum.DISABLED.getCode()).equals(req.getStatus())) {
            throw new BusinessException(ERR_BUILTIN, "内置用户禁止停用");
        }
        user.setNickname(req.getNickname());
        user.setDeptId(requireDeptId(req.getDeptId()));
        user.setStatus(req.getStatus());
        user.setUpdateBy(SecurityUtils.currentAccount());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.update(user);
        evictUserTransCache();
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        SysUser current = requireUser(id);
        // 内置保护：admin 是唯一种子账号，删除即登录入口消失
        if (Integer.valueOf(SysUser.BuiltinEnum.BUILT_IN.getCode()).equals(current.getIsBuiltin())) {
            throw new BusinessException(ERR_BUILTIN, "内置用户禁止删除");
        }
        userMapper.deleteById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
        userRoleMapper.deleteByUserId(id);
        evictUserTransCache();
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        requireUser(id);
        userMapper.updatePassword(id, passwordEncoder.encode(newPassword),
                SecurityUtils.currentAccount(), LocalDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        SysUser current = requireUser(userId);
        // 内置保护（拍板延伸，设计 D6/契约 §5.2）：绑定操作整体拒绝——清空绑定 = 无权限锁死（独立三行便于剔除）
        if (Integer.valueOf(SysUser.BuiltinEnum.BUILT_IN.getCode()).equals(current.getIsBuiltin())) {
            throw new BusinessException(ERR_BUILTIN, "内置用户禁止修改角色");
        }
        userRoleMapper.deleteByUserId(userId);
        if (roleIds != null) {
            List<SysUserRole> list = roleIds.stream().distinct()
                    .map(roleId -> {
                        SysUserRole ur = new SysUserRole();
                        ur.setUserId(userId);
                        ur.setRoleId(roleId);
                        ur.setCreateTime(LocalDateTime.now());
                        return ur;
                    }).toList();
            if (!list.isEmpty()) {
                userRoleMapper.saveBatch(list);
            }
        }
    }

    public List<Long> listRoleIds(Long userId) {
        return userRoleMapper.listRoleIdsByUserId(userId);
    }

    /** 用户翻译缓存失效（nickname 变更/新增/删除；resetPassword 不动显示名不挂）：失败仅 log.error 不抛，TTL 兜底 */
    private void evictUserTransCache() {
        try {
            translationCacheService.deleteUsers();
        } catch (Exception e) {
            log.error("user trans cache evict failed", e);
        }
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.findById(id);
        if (user == null) {
            throw new BusinessException(3001, "用户不存在");
        }
        return user;
    }

    /** deptId 校验（契约 §5.1）：null=不挂部门放行；传入时部门必须存在且未删（3027） */
    private Long requireDeptId(Long deptId) {
        if (deptId == null) {
            return null;
        }
        if (deptMapper.findById(deptId) == null) {
            throw new BusinessException(ERR_DEPT_NOT_FOUND, "部门不存在");
        }
        return deptId;
    }
}
