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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

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

    // ---- 内置保护（契约 2026-10-07-builtin-protection-api §2：内置角色一刀切全禁，3013）----

    private SysRole builtinRole() {
        SysRole role = new SysRole();
        role.setId(1L);
        role.setIsBuiltin(SysRole.BuiltinEnum.BUILT_IN.getCode());
        return role;
    }

    @Test
    void edit_builtinRoleRejected() {
        when(roleMapper.findById(1L)).thenReturn(builtinRole());
        SysRole role = new SysRole();
        role.setId(1L);
        role.setName("x");
        assertThatThrownBy(() -> service.update(role))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置角色禁止修改")
                .extracting("code")
                .isEqualTo(3013);
        verify(roleMapper, never()).update(any(SysRole.class));
    }

    @Test
    void delete_builtinRoleRejected() {
        when(roleMapper.findById(1L)).thenReturn(builtinRole());
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置角色禁止删除")
                .extracting("code")
                .isEqualTo(3013);
        verify(roleMapper, never()).deleteById(any(), any(), any());
    }

    @Test
    void assignMenus_builtinRoleRejected() {
        when(roleMapper.findById(1L)).thenReturn(builtinRole());
        assertThatThrownBy(() -> service.assignMenus(1L, List.of(10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置角色禁止修改权限")
                .extracting("code")
                .isEqualTo(3013);
        verify(roleMenuMapper, never()).deleteByRoleId(anyLong());
    }

    /** 放行路径：非内置（is_builtin=0）照旧走 mapper；既有用例已覆盖 is_builtin=null 路径 */
    @Test
    void edit_nonBuiltinRoleUpdated() {
        SysRole current = new SysRole();
        current.setId(2L);
        current.setIsBuiltin(SysRole.BuiltinEnum.DEFAULT.getCode());
        when(roleMapper.findById(2L)).thenReturn(current);
        SysRole role = new SysRole();
        role.setId(2L);
        service.update(role);
        verify(roleMapper).update(any(SysRole.class));
    }
}
