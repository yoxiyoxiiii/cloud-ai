package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysRoleMenu;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleManageService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;

    public PageResult<SysRole> page(PageQuery query) {
        IPage<SysRole> page = roleMapper.selectRolePage(
                new Page<>(query.getPageNum(), query.getPageSize()));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    public List<SysRole> listAll() {
        return roleMapper.selectEnabledRoles();
    }

    public Long add(SysRole role) {
        assertRoleKeyValid(role.getRoleKey());
        assertRoleKeyFree(role.getRoleKey(), null);
        // 手写 SQL 无 MetaObjectHandler 自动填充：审计四值显式传入，插入时 update 值 = create 值
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        role.setCreateBy(operator);
        role.setCreateTime(now);
        role.setUpdateBy(operator);
        role.setUpdateTime(now);
        try {
            roleMapper.insertRole(role);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(3003, "角色标识已存在: " + role.getRoleKey());
        }
        return role.getId();
    }

    public void edit(SysRole role) {
        requireRole(role.getId());
        if (role.getRoleKey() != null) {
            assertRoleKeyValid(role.getRoleKey());
            assertRoleKeyFree(role.getRoleKey(), role.getId());
        }
        role.setUpdateBy(SecurityUtils.currentAccount());
        role.setUpdateTime(LocalDateTime.now());
        try {
            roleMapper.updateRole(role);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(3003, "角色标识已存在: " + role.getRoleKey());
        }
    }

    private void assertRoleKeyValid(String roleKey) {
        if (roleKey == null || roleKey.isBlank()) {
            throw new BusinessException("角色标识不能为空");
        }
    }

    /** 预检（编辑时排除自身）；逻辑删除墓碑仍占 uk_role_key，并发/墓碑场景由 DuplicateKey 兜底 */
    private void assertRoleKeyFree(String roleKey, Long excludeId) {
        Long count = roleMapper.countByRoleKey(roleKey, excludeId);
        if (count > 0) {
            throw new BusinessException(3003, "角色标识已存在: " + roleKey);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireRole(id);
        roleMapper.deleteRoleById(id, SecurityUtils.currentAccount(), LocalDateTime.now());
        roleMenuMapper.deleteByRoleId(id);
        userRoleMapper.deleteByRoleId(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        requireRole(roleId);
        roleMenuMapper.deleteByRoleId(roleId);
        if (menuIds != null) {
            List<SysRoleMenu> list = menuIds.stream().distinct()
                    .map(menuId -> {
                        SysRoleMenu rm = new SysRoleMenu();
                        rm.setRoleId(roleId);
                        rm.setMenuId(menuId);
                        rm.setCreateTime(LocalDateTime.now());
                        return rm;
                    }).toList();
            if (!list.isEmpty()) {
                roleMenuMapper.insertBatch(list);
            }
        }
    }

    public List<Long> menuIdsOf(Long roleId) {
        return roleMenuMapper.selectMenuIdsByRoleId(roleId);
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectRoleById(id);
        if (role == null) {
            throw new BusinessException(3004, "角色不存在");
        }
        return role;
    }
}
