package com.cloudai.system.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
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
class SysRoleManageServiceTest {

    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysRoleMenuMapper roleMenuMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @InjectMocks
    private SysRoleManageService service;

    @Test
    void add_blankKeyRejected() {
        SysRole role = new SysRole();
        role.setRoleKey(" ");
        assertThatThrownBy(() -> service.save(role))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色标识不能为空");
        verify(roleMapper, never()).save(any(SysRole.class));
    }

    @Test
    void edit_duplicateKeyRejected() {
        SysRole role = new SysRole();
        role.setId(2L);
        role.setRoleKey("admin");
        when(roleMapper.findById(2L)).thenReturn(new SysRole());
        when(roleMapper.countByRoleKey(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(1L);
        assertThatThrownBy(() -> service.update(role))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("角色标识已存在");
        verify(roleMapper, never()).update(any(SysRole.class));
    }
}
