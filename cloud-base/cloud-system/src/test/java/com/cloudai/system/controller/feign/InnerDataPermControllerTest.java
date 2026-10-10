package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.DataPermDenyRequest;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataScope;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /inner/data-perm 薄壳单测（契约 2026-10-10-dataperm-component-api §2，组件化 D23）：
 * evaluate → evaluator.evaluateFor 四参透传 + Decision→ScopeVo 四字段收敛（D19）；
 * deny → evaluator.logDeny 三参透传。求值矩阵本体由 DataPermEvaluatorTest 覆盖，本类只测壳。
 */
@ExtendWith(MockitoExtension.class)
class InnerDataPermControllerTest {

    @Mock
    private DataPermEvaluator evaluator;
    @InjectMocks
    private InnerDataPermController controller;

    @Test
    void evaluate_allScope_convergesToRowAllWithEmptyAccounts() {
        when(evaluator.evaluateFor("admin", "bpmn_approval", "list", null))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of("title"), Set.of())));

        R<DataPermScopeVo> resp = controller.evaluate(
                new DataPermEvaluateRequest("admin", "bpmn_approval", "list", null));

        assertThat(resp.getCode()).isEqualTo(200);
        assertThat(resp.getData().isRowAll()).isTrue();
        assertThat(resp.getData().getAccounts()).isEmpty();
        assertThat(resp.getData().getHiddenColumns()).containsExactly("title");
        assertThat(resp.getData().getMaskedColumns()).isEmpty();
    }

    @Test
    void evaluate_whitelistScope_convergesAccountsAndColumns() {
        when(evaluator.evaluateFor("userA", "bpmn_approval", "detail", "5"))
                .thenReturn(decision(DataScope.of(Set.of("userA", "peerB")),
                        ColumnScope.of(Set.of(), Set.of("title"))));

        R<DataPermScopeVo> resp = controller.evaluate(
                new DataPermEvaluateRequest("userA", "bpmn_approval", "detail", "5"));

        assertThat(resp.getData().isRowAll()).isFalse();
        assertThat(resp.getData().getAccounts()).containsExactlyInAnyOrder("userA", "peerB");
        assertThat(resp.getData().getMaskedColumns()).containsExactly("title");
    }

    @Test
    void deny_delegatesThreeArgs() {
        R<Void> resp = controller.deny(new DataPermDenyRequest("userB", "bpmn_approval", "5"));

        assertThat(resp.getCode()).isEqualTo(200);
        verify(evaluator).logDeny("userB", "bpmn_approval", "5");
    }

    private DataPermDecision decision(DataScope dataScope, ColumnScope columnScope) {
        DataPermDecision decision = new DataPermDecision();
        decision.setResource("bpmn_approval");
        decision.setAccount("who");
        decision.setOperation("list");
        decision.setDataScope(dataScope);
        decision.setColumnScope(columnScope);
        return decision;
    }
}
