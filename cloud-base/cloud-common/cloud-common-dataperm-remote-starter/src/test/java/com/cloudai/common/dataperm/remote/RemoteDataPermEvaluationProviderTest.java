package com.cloudai.common.dataperm.remote;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.dataperm.DataPermEvaluation;
import com.cloudai.system.api.client.DataPermClient;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
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
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.when;

/**
 * 远端求值后端单测：账号解析（SecurityUtils 当前登录人入参）+ wire 模型到求值结果的映射
 * （rowAll/白名单组、masked 列）+ 三路 fail-closed（降级 R / 空体 / Feign 异常——统一拒绝访问）。
 */
@ExtendWith(MockitoExtension.class)
class RemoteDataPermEvaluationProviderTest {

    @Mock
    private DataPermClient dataPermClient;
    @InjectMocks
    private RemoteDataPermEvaluationProvider provider;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String account) {
        LoginUser loginUser = new LoginUser();
        loginUser.setAccount(account);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    @Test
    void evaluate_mapsWireScope_withCurrentAccount() {
        loginAs("userA");
        DataPermScopeVo scopeVo = new DataPermScopeVo(false, List.of("userA"), List.of(), List.of("title"));
        when(dataPermClient.evaluate(
                new DataPermEvaluateRequest("userA", "bpmn_approval", "detail", "5")))
                .thenReturn(R.ok(scopeVo));

        DataPermEvaluation evaluation = provider.evaluate("bpmn_approval", "detail", "5");

        assertThat(evaluation.resource()).isEqualTo("bpmn_approval");
        assertThat(evaluation.rowFilter().isRowAll()).isFalse();
        assertThat(evaluation.rowFilter().getGroups().get(0).getAccounts()).containsExactly("userA");
        assertThat(evaluation.columnScope().getMaskedColumns()).containsExactly("title");
    }

    @Test
    void evaluate_allScope_rowAllAndEmptyColumns() {
        loginAs("admin");
        when(dataPermClient.evaluate(
                new DataPermEvaluateRequest("admin", "leave", "list", null)))
                .thenReturn(R.ok(new DataPermScopeVo(true, List.of(), List.of(), List.of())));

        DataPermEvaluation evaluation = provider.evaluate("leave", "list", null);

        assertThat(evaluation.rowFilter().isRowAll()).isTrue();
        assertThat(evaluation.columnScope().isEmpty()).isTrue();
    }

    /** 降级 R（消费方 fallbackFactory 或对端业务失败）：msg 透传，fail-closed 拒绝访问 */
    @Test
    void evaluate_degradedR_failClosedWithMsg() {
        loginAs("userA");
        when(dataPermClient.evaluate(
                new DataPermEvaluateRequest("userA", "bpmn_approval", "list", null)))
                .thenReturn(R.fail(1002, "数据权限服务不可用，请稍后重试"));

        BusinessException ex = catchThrowableOfType(
                () -> provider.evaluate("bpmn_approval", "list", null), BusinessException.class);

        assertThat(ex.getMessage()).isEqualTo("数据权限服务不可用，请稍后重试");
    }

    /** 空体（data=null 协议异常防御分支）：fail-closed 拒绝访问——异常文案透传远端 msg（R.ok 恒带「操作成功」），不另外断言 */
    @Test
    void evaluate_nullBody_failClosed() {
        loginAs("userA");
        when(dataPermClient.evaluate(
                new DataPermEvaluateRequest("userA", "bpmn_approval", "list", null)))
                .thenReturn(R.ok(null));

        catchThrowableOfType(
                () -> provider.evaluate("bpmn_approval", "list", null), BusinessException.class);
    }

    /** resp msg 为空：统一默认文案拒绝 */
    @Test
    void evaluate_nullMsg_failClosedWithDefaultMsg() {
        loginAs("userA");
        R<DataPermScopeVo> nullMsg = R.ok(null);
        nullMsg.setMsg(null);
        when(dataPermClient.evaluate(
                new DataPermEvaluateRequest("userA", "bpmn_approval", "list", null)))
                .thenReturn(nullMsg);

        BusinessException ex = catchThrowableOfType(
                () -> provider.evaluate("bpmn_approval", "list", null), BusinessException.class);

        assertThat(ex.getMessage()).isEqualTo("数据权限服务不可用，请稍后重试");
    }

    /** Feign 异常（超时/连接拒绝/熔断未包装的裸异常）：catch-all 兜底统一拒绝 */
    @Test
    void evaluate_feignException_failClosedWithDefaultMsg() {
        loginAs("userA");
        when(dataPermClient.evaluate(
                new DataPermEvaluateRequest("userA", "bpmn_approval", "list", null)))
                .thenThrow(new RuntimeException("connect refused"));

        BusinessException ex = catchThrowableOfType(
                () -> provider.evaluate("bpmn_approval", "list", null), BusinessException.class);

        assertThat(ex.getMessage()).isEqualTo("数据权限服务不可用，请稍后重试");
    }
}
