package com.cloudai.system.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.mapper.SysMenuMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysMenuManageServiceTest {

    @Mock
    private SysMenuMapper menuMapper;
    @Mock
    private SysRoleMenuMapper roleMenuMapper;
    @InjectMocks
    private SysMenuManageService service;

    private SysMenu menu(Long id, Long parentId) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setParentId(parentId);
        m.setName("n" + id);
        return m;
    }

    @Test
    void edit_selfParentRejected() {
        when(menuMapper.selectMenuById(10L)).thenReturn(menu(10L, 0L));
        assertThatThrownBy(() -> service.edit(menu(10L, 10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("父菜单不能是自身");
        verify(menuMapper, never()).updateMenu(any(SysMenu.class));
    }

    @Test
    void edit_cycleRejected() {
        when(menuMapper.selectMenuById(10L)).thenReturn(menu(10L, 0L));
        when(menuMapper.selectMenuById(13L)).thenReturn(menu(13L, 10L));
        assertThatThrownBy(() -> service.edit(menu(10L, 13L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("后代");
        verify(menuMapper, never()).updateMenu(any(SysMenu.class));
    }

    @Test
    void edit_nonExistentParentRejected() {
        when(menuMapper.selectMenuById(10L)).thenReturn(menu(10L, 0L));
        assertThatThrownBy(() -> service.edit(menu(10L, 999L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("父菜单不存在");
    }
}
