package com.cloudai.system.service;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataPermColumnContext;
import com.cloudai.system.api.dataperm.DataPermOperation;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import com.cloudai.system.service.dataperm.DataPermResources;
import com.cloudai.system.vo.SysUserVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户管理单测：翻译缓存失效挂钩（save/update/delete 后 DEL trans:user；resetPassword 不挂；
 * DEL 失败主流程不炸）+ 列级数据权限轮增量（读路径列上下文接线 / 写路径脱敏哨兵与空串归一 /
 * 联系列格式校验）。
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
    @Mock
    private DataPermEvaluator dataPermEvaluator;
    @InjectMocks
    private SysUserManageService service;

    @AfterEach
    void clearThreadLocals() {
        // SecurityContextHolder 与列上下文都是线程级 ThreadLocal，测试线程被复用——清掉避免污染其它测试
        SecurityContextHolder.clearContext();
        DataPermColumnContext.clear();
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
        verify(userMapper).deleteById(eq(1L), eq("admin"));
        verify(translationCacheService).deleteUsers();
    }

    @Test
    void resetPassword_neverEvictsUserTransCache() {
        loginAs("admin");
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        when(passwordEncoder.encode("newpass123")).thenReturn("hashed");
        service.resetPassword(1L, "newpass123");
        verify(userMapper).updatePassword(eq(1L), eq("hashed"), eq("admin"));
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

    // ---- 内置保护（契约 2026-10-07-builtin-protection-api §2：用户域例外——仅禁删/禁停/禁改角色，
    //      nickname 与 resetPassword 放行；3017）----

    private SysUser builtinUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setAccount("admin");
        user.setIsBuiltin(SysUser.BuiltinEnum.BUILT_IN.getCode());
        return user;
    }

    @Test
    void delete_builtinUserRejected() {
        when(userMapper.findById(1L)).thenReturn(builtinUser());
        assertThatThrownBy(() -> service.delete(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置用户禁止删除")
                .extracting("code")
                .isEqualTo(3017);
        verify(userMapper, never()).deleteById(any(), any());
    }

    @Test
    void update_builtinUserDisableRejected() {
        when(userMapper.findById(1L)).thenReturn(builtinUser());
        UserSaveRequest req = saveRequest();
        req.setStatus(1);
        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置用户禁止停用")
                .extracting("code")
                .isEqualTo(3017);
        verify(userMapper, never()).update(any(SysUser.class));
    }

    /** 放行路径：内置用户改昵称（status=0）正常走 mapper——合法运维（契约 §2 用户域例外） */
    @Test
    void update_builtinUserNicknameChangeAllowed() {
        when(userMapper.findById(1L)).thenReturn(builtinUser());
        UserSaveRequest req = saveRequest();
        req.setNickname("新昵称");
        req.setStatus(0);
        service.update(1L, req);
        verify(userMapper).update(any(SysUser.class));
    }

    /** 放行路径：status 未传（null）视为不改状态——部分更新语义，放行 */
    @Test
    void update_builtinUserStatusNullAllowed() {
        when(userMapper.findById(1L)).thenReturn(builtinUser());
        UserSaveRequest req = saveRequest();
        req.setStatus(null);
        service.update(1L, req);
        verify(userMapper).update(any(SysUser.class));
    }

    /** 拍板延伸（设计 D6/契约 §5.2）：assignRoles 整体拒绝——独立校验点便于剔除 */
    @Test
    void assignRoles_builtinUserRejected() {
        when(userMapper.findById(1L)).thenReturn(builtinUser());
        assertThatThrownBy(() -> service.assignRoles(1L, java.util.List.of(1L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("内置用户禁止修改角色")
                .extracting("code")
                .isEqualTo(3017);
        verify(userRoleMapper, never()).deleteByUserId(anyLong());
    }

    /** 放行路径：内置用户重置密码放行（admin 忘密码是合法运维） */
    @Test
    void resetPassword_builtinUserAllowed() {
        loginAs("admin");
        when(userMapper.findById(1L)).thenReturn(builtinUser());
        when(passwordEncoder.encode("newpass123")).thenReturn("hashed");
        service.resetPassword(1L, "newpass123");
        verify(userMapper).updatePassword(eq(1L), eq("hashed"), eq("admin"));
    }

    // ---- 列级数据权限轮增量（2026-10-22）：读路径列上下文接线 + 写路径哨兵/归一/格式校验 ----

    /** 求值决策脚手架：email 隐藏 + maskedColumns 脱敏（可变参数，空即空 scope） */
    private DataPermDecision columnDecision(String... maskedColumns) {
        DataPermDecision decision = new DataPermDecision();
        decision.setColumnScope(ColumnScope.of(java.util.Set.of("email"), java.util.Set.of(maskedColumns)));
        return decision;
    }

    @Test
    void pageList_setsColumnContextForUserResource() {
        when(dataPermEvaluator.evaluate(eq(DataPermResources.USER), eq(DataPermOperation.LIST), isNull()))
                .thenReturn(columnDecision("mobile"));
        when(userMapper.pageList(any())).thenReturn(
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<SysUserVo>(1, 10));

        service.pageList(new PageQuery());

        // 接线声明语义：无论 scope 是否为空都 set——advice 据此应用列动作，缺失即 fail-closed
        DataPermColumnContext.ColumnDecision context = DataPermColumnContext.get();
        assertThat(context).isNotNull();
        assertThat(context.resource()).isEqualTo(DataPermResources.USER);
        assertThat(context.columnScope().isHidden("email")).isTrue();
        assertThat(context.columnScope().isMasked("mobile")).isTrue();
    }

    @Test
    void findById_setsColumnContextWithBusinessKey() {
        // 空 scope（无隐藏无脱敏）也 set——advice 据此判断「列权限已求值」，缺失才 fail-closed
        DataPermDecision decision = new DataPermDecision();
        decision.setColumnScope(ColumnScope.of(java.util.Set.of(), java.util.Set.of()));
        when(dataPermEvaluator.evaluate(eq(DataPermResources.USER), eq(DataPermOperation.DETAIL), eq("1")))
                .thenReturn(decision);
        SysUser user = new SysUser();
        user.setId(1L);
        user.setMobile("13800000001");
        when(userMapper.findById(1L)).thenReturn(user);

        SysUserVo vo = service.findById(1L);

        // detail 链补两列 + businessKey=用户 id + 空 scope 也 set
        assertThat(vo.getMobile()).isEqualTo("13800000001");
        assertThat(DataPermColumnContext.get()).isNotNull();
        assertThat(DataPermColumnContext.get().resource()).isEqualTo(DataPermResources.USER);
        assertThat(DataPermColumnContext.get().columnScope().isEmpty()).isTrue();
    }

    @Test
    void update_maskSentinel_keepsColumnsUntouched() {
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        UserSaveRequest req = saveRequest();
        req.setMobile("***");
        req.setEmail("***");
        req.setNickname("new-name");

        service.update(1L, req);

        // 哨兵归 null：mapper 收 null（动态 if 跳过 → 库值保持原值）；混合场景 nickname 正常更新
        verify(userMapper).update(ArgumentMatchers.argThat(u ->
                u.getMobile() == null && u.getEmail() == null && "new-name".equals(u.getNickname())));
    }

    @Test
    void update_blankStringNormalized_neverWritesEmpty() {
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        UserSaveRequest req = saveRequest();
        req.setMobile("   ");
        req.setEmail("");

        service.update(1L, req);

        // 空串/纯空白归 null：mapper 动态 if 只挡 null 挡不住空串——空永不写库
        verify(userMapper).update(ArgumentMatchers.argThat(u ->
                u.getMobile() == null && u.getEmail() == null));
    }

    @Test
    void update_invalidMobileFormat_rejected1002() {
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        UserSaveRequest req = saveRequest();
        req.setMobile("12345");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("手机号格式无效")
                .extracting("code")
                .isEqualTo(1002);
        verify(userMapper, never()).update(any(SysUser.class));
    }

    @Test
    void update_invalidEmailFormat_rejected1002() {
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        UserSaveRequest req = saveRequest();
        req.setEmail("not-an-email");

        assertThatThrownBy(() -> service.update(1L, req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("邮箱格式无效")
                .extracting("code")
                .isEqualTo(1002);
        verify(userMapper, never()).update(any(SysUser.class));
    }

    @Test
    void update_validContacts_passToMapper() {
        when(userMapper.findById(1L)).thenReturn(new SysUser());
        UserSaveRequest req = saveRequest();
        req.setMobile("13800000001");
        req.setEmail("tester@example.com");

        service.update(1L, req);

        verify(userMapper).update(ArgumentMatchers.argThat(u ->
                "13800000001".equals(u.getMobile()) && "tester@example.com".equals(u.getEmail())));
    }

    @Test
    void save_maskSentinel_rejectedByFormatValidation() {
        // 新增路径不设哨兵：*** 作为新值被格式校验拦（脱敏标记永远不是合法新值）
        when(userMapper.countByAccount("tester")).thenReturn(0L);
        UserSaveRequest req = saveRequest();
        req.setMobile("***");

        assertThatThrownBy(() -> service.save(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("手机号格式无效")
                .extracting("code")
                .isEqualTo(1002);
        verify(userMapper, never()).save(any(SysUser.class));
    }

    @Test
    void save_validContacts_passToMapper() {
        when(userMapper.countByAccount("tester")).thenReturn(0L);
        when(passwordEncoder.encode("pass123")).thenReturn("hashed");
        UserSaveRequest req = saveRequest();
        req.setMobile("13800000001");
        req.setEmail("tester@example.com");

        service.save(req);

        verify(userMapper).save(ArgumentMatchers.argThat(u ->
                "13800000001".equals(u.getMobile()) && "tester@example.com".equals(u.getEmail())));
    }
}
