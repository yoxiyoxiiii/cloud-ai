package com.cloudai.system.service;

import cn.hutool.core.lang.Validator;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataPermColumnContext;
import com.cloudai.system.api.dataperm.DataPermOperation;
import com.cloudai.system.convert.SysUserConvert;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.entity.SysUserRole;
import com.cloudai.system.mapper.SysDeptMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import com.cloudai.system.service.dataperm.DataPermResources;
import com.cloudai.system.vo.SysUserVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private final DataPermEvaluator dataPermEvaluator;

    /**
     * 分页查询（数据权限轮 D14 改型 + 列级轮接线）：mapper VO 直出（LEFT JOIN sys_dept 派生
     * deptName）；SysUserVo 已标 @DataPermVo——求值后 set 列上下文（空 scope 也 set，接线声明语义），
     * 缺失即列处理 advice fail-closed 拒绝。user 是列级-only 资源：行段恒全部短路，mapper 零行过滤。
     */
    public PageResult<SysUserVo> pageList(PageQuery query) {
        DataPermDecision decision = dataPermEvaluator.evaluate(DataPermResources.USER,
                DataPermOperation.LIST, null);
        DataPermColumnContext.set(DataPermResources.USER, decision.getColumnScope());
        IPage<SysUserVo> page = userMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    /** 详情（列级轮接线）：同 pageList 求值 + set，businessKey=用户 id；detail 链 convert 补 mobile/email */
    public SysUserVo findById(Long id) {
        DataPermDecision decision = dataPermEvaluator.evaluate(DataPermResources.USER,
                DataPermOperation.DETAIL, String.valueOf(id));
        DataPermColumnContext.set(DataPermResources.USER, decision.getColumnScope());
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
        // 新增路径不设脱敏哨兵：*** 作为新值被下方格式校验拦截（脱敏标记永远不是合法新值）
        String mobile = normalizeContact(req.getMobile());
        String email = normalizeContact(req.getEmail());
        validateContactFormat(mobile, email);
        SysUser user = new SysUser();
        user.setAccount(req.getAccount());
        user.setNickname(req.getNickname() == null ? req.getAccount() : req.getNickname());
        user.setMobile(mobile);
        user.setEmail(email);
        user.setDeptId(requireDeptId(req.getDeptId()));
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setStatus(req.getStatus() == null ? SysUser.StatusEnum.NORMAL.getCode() : req.getStatus());
        // 手写 SQL 无 MetaObjectHandler 自动填充：操作人显式传入；审计时间由 XML 的 NOW() 落库（插入时 create=update 同值）
        String operator = SecurityUtils.currentAccount();
        user.setCreateBy(operator);
        user.setUpdateBy(operator);
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
        // 哨兵归 null 必须在校验前：脱敏标记 *** 不进格式校验，按不修改处理（库值保持原值）
        String mobile = maskSentinelToNull(normalizeContact(req.getMobile()));
        String email = maskSentinelToNull(normalizeContact(req.getEmail()));
        validateContactFormat(mobile, email);
        user.setNickname(req.getNickname());
        user.setMobile(mobile);
        user.setEmail(email);
        user.setDeptId(requireDeptId(req.getDeptId()));
        user.setStatus(req.getStatus());
        user.setUpdateBy(SecurityUtils.currentAccount());
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
        userMapper.deleteById(id, SecurityUtils.currentAccount());
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
                SecurityUtils.currentAccount());
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

    /**
     * 联系列归一：null/空白串 trim 后归 null——「空永不写库」（mapper 动态 if 只挡 null 挡不住空串，
     * 契约 §4.1 null/空=未填或不更新）；非空白返回 trim 值。
     */
    private String normalizeContact(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 修改路径脱敏哨兵：值等于脱敏标记 ***（ColumnScope.MASK）按 null 处理——null 走动态 if 不更新该列 */
    private String maskSentinelToNull(String value) {
        return ColumnScope.MASK.equals(value) ? null : value;
    }

    /** 联系列格式校验（契约 §4.2，非空才验）：手机号中国大陆 11 位 / 邮箱常规格式；无效 1002 */
    private void validateContactFormat(String mobile, String email) {
        if (mobile != null && !Validator.isMobile(mobile)) {
            throw new BusinessException("手机号格式无效");
        }
        if (email != null && !Validator.isEmail(email)) {
            throw new BusinessException("邮箱格式无效");
        }
    }
}
