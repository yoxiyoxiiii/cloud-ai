package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysRoleMenu;
import com.cloudai.system.entity.SysUserRole;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleManageService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;

    public PageResult<SysRole> page(PageQuery query) {
        Page<SysRole> page = roleMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<SysRole>().orderByDesc(SysRole::getId));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    public List<SysRole> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 0).orderByAsc(SysRole::getId));
    }

    public Long add(SysRole role) {
        assertRoleKeyValid(role.getRoleKey());
        assertRoleKeyFree(role.getRoleKey(), null);
        try {
            roleMapper.insert(role);
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
        try {
            roleMapper.updateById(role);
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
        Long count = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, roleKey)
                .ne(excludeId != null, SysRole::getId, excludeId));
        if (count > 0) {
            throw new BusinessException(3003, "角色标识已存在: " + roleKey);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireRole(id);
        roleMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        requireRole(roleId);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds != null) {
            menuIds.stream().distinct().forEach(menuId -> {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setRoleId(roleId);
                rm.setMenuId(menuId);
                roleMenuMapper.insert(rm);
            });
        }
    }

    public List<Long> menuIdsOf(Long roleId) {
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, roleId))
                .stream().map(SysRoleMenu::getMenuId).toList();
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(3004, "角色不存在");
        }
        return role;
    }
}
