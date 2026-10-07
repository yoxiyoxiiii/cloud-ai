package com.cloudai.system.service;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.mapper.SysMenuMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.vo.UserNavVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder 是线程级 ThreadLocal，测试线程被复用——清掉避免污染其它测试
        SecurityContextHolder.clearContext();
    }

    private SysMenu menu(Long id, Long parentId) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setParentId(parentId);
        m.setName("n" + id);
        return m;
    }

    private void loginAs(String account) {
        LoginUser loginUser = new LoginUser();
        loginUser.setAccount(account);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    @Test
    void edit_selfParentRejected() {
        when(menuMapper.findById(10L)).thenReturn(menu(10L, 0L));
        assertThatThrownBy(() -> service.update(menu(10L, 10L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("父菜单不能是自身");
        verify(menuMapper, never()).update(any(SysMenu.class));
    }

    @Test
    void edit_cycleRejected() {
        when(menuMapper.findById(10L)).thenReturn(menu(10L, 0L));
        when(menuMapper.findById(13L)).thenReturn(menu(13L, 10L));
        assertThatThrownBy(() -> service.update(menu(10L, 13L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("后代");
        verify(menuMapper, never()).update(any(SysMenu.class));
    }

    @Test
    void edit_nonExistentParentRejected() {
        when(menuMapper.findById(10L)).thenReturn(menu(10L, 0L));
        assertThatThrownBy(() -> service.update(menu(10L, 999L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("父菜单不存在");
    }

    @Test
    void edit_blankNameRejected() {
        when(menuMapper.findById(10L)).thenReturn(menu(10L, 0L));
        SysMenu req = menu(10L, 0L);
        req.setName("");
        assertThatThrownBy(() -> service.update(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("菜单名称不能为空");
        verify(menuMapper, never()).update(any(SysMenu.class));
    }

    @Test
    void edit_nullNameAllowed() {
        when(menuMapper.findById(10L)).thenReturn(menu(10L, 0L));
        SysMenu req = menu(10L, 0L);
        req.setName(null);
        service.update(req);
        verify(menuMapper).update(any(SysMenu.class));
    }

    @Test
    void listUserNav_blankAccountReturnsEmptyWithoutQuery() {
        // 无认证上下文 → currentAccount() 为 null（防御路径）：返回空列表且不打 mapper
        SecurityContextHolder.clearContext();
        List<UserNavVo> nav = service.listUserNav();
        assertThat(nav).isEmpty();
        verify(menuMapper, never()).listNavByAccount(anyString());
    }

    @Test
    void listUserNav_buildsTreeForCurrentAccount() {
        loginAs("admin");
        SysMenu dir = menu(10L, 0L);
        dir.setType("M");
        SysMenu leaf = menu(11L, 10L);
        leaf.setType("C");
        leaf.setPath("/system/user");
        when(menuMapper.listNavByAccount("admin")).thenReturn(List.of(dir, leaf));

        List<UserNavVo> nav = service.listUserNav();

        assertThat(nav).hasSize(1);
        assertThat(nav.get(0).getChildren()).hasSize(1);
        assertThat(nav.get(0).getChildren().get(0).getPath()).isEqualTo("/system/user");
    }

    // ---- 内置保护（契约 2026-10-07-builtin-protection-api §2：内置菜单一刀切全禁，3014）----

    private SysMenu builtinMenu(Long id) {
        SysMenu m = menu(id, 0L);
        m.setIsBuiltin(SysMenu.BuiltinEnum.BUILT_IN.getCode());
        return m;
    }

    @Test
    void edit_builtinMenuRejected() {
        when(menuMapper.findById(10L)).thenReturn(builtinMenu(10L));
        assertThatThrownBy(() -> service.update(menu(10L, 0L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置菜单禁止修改")
                .extracting("code")
                .isEqualTo(3014);
        verify(menuMapper, never()).update(any(SysMenu.class));
    }

    @Test
    void delete_builtinMenuRejected() {
        when(menuMapper.findById(10L)).thenReturn(builtinMenu(10L));
        assertThatThrownBy(() -> service.delete(10L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置菜单禁止删除")
                .extracting("code")
                .isEqualTo(3014);
        verify(menuMapper, never()).deleteById(any(), any(), any());
        verify(roleMenuMapper, never()).deleteByMenuId(anyLong());
    }

    /** 放行路径：非内置（is_builtin=null，用户创建行默认 0）照旧走删除链 */
    @Test
    void delete_nonBuiltinMenuDeleted() {
        loginAs("admin");
        when(menuMapper.findById(50L)).thenReturn(menu(50L, 10L));
        when(menuMapper.countByParentId(50L)).thenReturn(0L);
        service.delete(50L);
        verify(menuMapper).deleteById(eq(50L), eq("admin"), any());
        verify(roleMenuMapper).deleteByMenuId(50L);
    }
}
