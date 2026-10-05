package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.entity.SysUserRole;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
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

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public PageResult<SysUser> page(PageQuery query) {
        IPage<SysUser> page = userMapper.selectUserPage(
                new Page<>(query.getPageNum(), query.getPageSize()));
        page.getRecords().forEach(u -> u.setPassword(null));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    public SysUser detail(Long id) {
        SysUser user = userMapper.selectUserById(id);
        if (user == null) {
            throw new BusinessException(3001, "用户不存在");
        }
        user.setPassword(null);
        return user;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long add(UserSaveRequest req) {
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
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setStatus(req.getStatus() == null ? 0 : req.getStatus());
        // 手写 SQL 无 MetaObjectHandler 自动填充：审计四值显式传入，插入时 update 值 = create 值
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        user.setCreateBy(operator);
        user.setCreateTime(now);
        user.setUpdateBy(operator);
        user.setUpdateTime(now);
        try {
            userMapper.insertUser(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 逻辑删除行仍占用 uk_account，countByAccount 查重看不到——捕获兜底转业务异常
            log.error("唯一键冲突：{}", e.getMessage());
            throw new BusinessException(3002, "账号已存在: " + req.getAccount());
        }
        return user.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void edit(Long id, UserSaveRequest req) {
        SysUser user = requireUser(id);
        user.setNickname(req.getNickname());
        user.setStatus(req.getStatus());
        user.setUpdateBy(SecurityUtils.currentAccount());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.updateUser(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireUser(id);
        userMapper.deleteUserById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
        userRoleMapper.deleteByUserId(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        requireUser(id);
        userMapper.updateUserPassword(id, passwordEncoder.encode(newPassword),
                SecurityUtils.currentAccount(), LocalDateTime.now());
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        requireUser(userId);
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
                userRoleMapper.insertBatch(list);
            }
        }
    }

    public List<Long> roleIdsOf(Long userId) {
        return userRoleMapper.selectRoleIdsByUserId(userId);
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectUserById(id);
        if (user == null) {
            throw new BusinessException(3001, "用户不存在");
        }
        return user;
    }
}
