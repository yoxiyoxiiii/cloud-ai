package com.cloudai.system.service;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理翻译缓存失效挂钩单测（设计 §4.2）：save/update/delete 后 DEL trans:user；
 * resetPassword 不动显示名不挂；DEL 失败主流程不炸。
 */
@ExtendWith(MockitoExtension.class)
class SysUserManageServiceTest {

    @Mock
    private SysUserMapper userMapper;
    @Mock
    private SysUserRoleMapper userRoleMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TranslationCacheService translationCacheService;
    @InjectMocks
    private SysUserManageService service;

    @AfterEach
    void clearSecurityContext() {
        // SecurityContextHolder 是线程级 ThreadLocal，测试线程被复用——清掉避免污染其它测试
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String account) {
        LoginUser loginUser = new LoginUser();
        loginUser.setAccount(account);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    private UserSaveRequest saveRequest() {
        UserSaveRequest req = new UserSaveRequest();
        req.setAccount("tester");
        req.setPassword("pass123");
        req.setNickname("测试员");
        return req;
    }

    @Test
    void save_evictsUserTransCache() {
        loginAs("admin");
        when(userMapper.countByAccount("tester")).thenReturn(0L);
        when(passwordEncoder.encode("pass123")).thenReturn("hashed");
        service.save(saveRequest());
        verify(userMapper).save(any(SysUser.class));
        verify(translationCacheService).deleteUsers();
    }

    @Test
    void update_evictsUserTransCache() {
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        service.update(1L, saveRequest());
        verify(userMapper).update(any(SysUser.class));
        verify(translationCacheService).deleteUsers();
    }

    @Test
    void delete_evictsUserTransCache() {
        loginAs("admin");
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        service.delete(1L);
        verify(userMapper).deleteById(eq(1L), eq("admin"), any());
        verify(translationCacheService).deleteUsers();
    }

    @Test
    void resetPassword_neverEvictsUserTransCache() {
        loginAs("admin");
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        when(passwordEncoder.encode("newpass123")).thenReturn("hashed");
        service.resetPassword(1L, "newpass123");
        verify(userMapper).updatePassword(eq(1L), eq("hashed"), eq("admin"), any());
        verify(translationCacheService, never()).deleteUsers();
    }

    @Test
    void evictFails_mainFlowNotBroken() {
        when(userMapper.countByAccount("tester")).thenReturn(0L);
        when(passwordEncoder.encode("pass123")).thenReturn("hashed");
        doThrow(new RuntimeException("redis down")).when(translationCacheService).deleteUsers();
        assertThatCode(() -> service.save(saveRequest())).doesNotThrowAnyException();
        verify(userMapper).save(any(SysUser.class));
        verify(userRoleMapper, never()).deleteByUserId(anyLong());
    }
}
