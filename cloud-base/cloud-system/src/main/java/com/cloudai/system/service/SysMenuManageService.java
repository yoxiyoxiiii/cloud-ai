package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.entity.SysRoleMenu;
import com.cloudai.system.mapper.SysMenuMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysMenuManageService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    public List<SysMenu> listAll() {
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSort));
    }

    public Long add(SysMenu menu) {
        if (menu.getName() == null || menu.getName().isBlank()) {
            throw new BusinessException("菜单名称不能为空");
        }
        validateParent(menu.getParentId(), null);
        menuMapper.insert(menu);
        return menu.getId();
    }

    public void edit(SysMenu menu) {
        requireMenu(menu.getId());
        validateParent(menu.getParentId(), menu.getId());
        menuMapper.updateById(menu);
    }

    /** parentId 须为 0/null 或已存在菜单；编辑时不允许自指或把自身后代设为父（成环会使菜单支系从树上静默消失且 API 层不可恢复） */
    private void validateParent(Long parentId, Long selfId) {
        if (parentId == null || parentId == 0L) {
            return;
        }
        if (parentId.equals(selfId)) {
            throw new BusinessException(3007, "父菜单不能是自身");
        }
        SysMenu parent = menuMapper.selectById(parentId);
        if (parent == null) {
            throw new BusinessException(3007, "父菜单不存在: " + parentId);
        }
        if (selfId == null) {
            return;
        }
        Long cursor = parent.getParentId();
        int guard = 0;
        while (cursor != null && cursor != 0L && guard++ < 100) {
            if (cursor.equals(selfId)) {
                throw new BusinessException(3007, "父菜单不能是自身的后代（会形成环）");
            }
            SysMenu up = menuMapper.selectById(cursor);
            if (up == null) {
                break;
            }
            cursor = up.getParentId();
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireMenu(id);
        Long childCount = menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException(3005, "存在子菜单，先删除子级");
        }
        menuMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, id));
    }

    private SysMenu requireMenu(Long id) {
        SysMenu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException(3006, "菜单不存在");
        }
        return menu;
    }
}
