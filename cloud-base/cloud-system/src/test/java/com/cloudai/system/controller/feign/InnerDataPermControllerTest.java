package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.DataPermDenyRequest;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import com.cloudai.system.api.domain.PredicateVo;
import com.cloudai.system.api.domain.RowGroupVo;
import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataScope;
import com.cloudai.system.api.dataperm.PredicateOperatorEnum;
import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.api.dataperm.RowGroup;
import com.cloudai.system.api.dataperm.RowPredicate;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /inner/data-perm 薄壳单测（契约 2026-10-10-dataperm-component-api §2 + 谓词轮 §3 增量，
 * 组件化 D23）：evaluate → evaluator.evaluateFor 四参透传 + Decision→ScopeVo 收敛
 * （D19 五字段——rowGroups 行集判定唯一权威）；deny → evaluator.logDeny 三参透传。
 * 求值矩阵本体由 DataPermEvaluatorTest 覆盖，本类只测壳。
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

    /** 谓词轮收敛（契约 §3）：无条件+条件混合——rowGroups 保组序，PredicateVo 自含 column/type，值类型化→线格式 */
    @Test
    void evaluate_conditionalScope_convergesRowGroupsWithSelfContainedPredicates() {
        DataPermDecision decision = decision(DataScope.of(Set.of("userA")),
                ColumnScope.of(Set.of(), Set.of()));
        decision.setResource("leave");
        decision.setRowFilter(RowFilter.of(List.of(
                RowGroup.of(false, Set.of("userA"), List.of()),
                RowGroup.of(true, Set.of(), List.of(
                        RowPredicate.of("id", "id", PredicateOperatorEnum.BETWEEN,
                                List.of(new BigDecimal("1"), new BigDecimal("100"))),
                        RowPredicate.of("startDate", "start_date", PredicateOperatorEnum.GTE,
                                List.of(LocalDate.of(2026, 1, 1))))))));

        when(evaluator.evaluateFor("userA", "leave", "list", null)).thenReturn(decision);

        R<DataPermScopeVo> resp = controller.evaluate(
                new DataPermEvaluateRequest("userA", "leave", "list", null));

        assertThat(resp.getData().isRowAll()).isFalse();
        assertThat(resp.getData().getRowGroups()).hasSize(2);
        RowGroupVo merged = resp.getData().getRowGroups().get(0);
        assertThat(merged.isAllAccounts()).isFalse();
        assertThat(merged.getAccounts()).containsExactly("userA");
        assertThat(merged.getPredicates()).isEmpty();
        RowGroupVo conditional = resp.getData().getRowGroups().get(1);
        assertThat(conditional.isAllAccounts()).isTrue();
        assertThat(conditional.getAccounts()).isEmpty();
        assertThat(conditional.getPredicates()).hasSize(2);
        PredicateVo between = conditional.getPredicates().get(0);
        assertThat(between.getField()).isEqualTo("id");
        assertThat(between.getColumn()).isEqualTo("id");
        assertThat(between.getOperator()).isEqualTo("BETWEEN");
        assertThat(between.getType().name()).isEqualTo("NUMBER");
        assertThat(between.getValues()).containsExactly("1", "100");
        PredicateVo gte = conditional.getPredicates().get(1);
        assertThat(gte.getOperator()).isEqualTo("GTE");
        assertThat(gte.getType().name()).isEqualTo("DATE");
        assertThat(gte.getValues()).containsExactly("2026-01-01");
    }

    /** 谓词轮收敛：rowAll=true 时 rowGroups 置空（过滤豁免无需分组白名单，契约 §3） */
    @Test
    void evaluate_rowAllScope_leavesRowGroupsEmpty() {
        DataPermDecision decision = decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of()));
        decision.setRowFilter(RowFilter.rowAll());

        when(evaluator.evaluateFor("admin", "leave", "list", null)).thenReturn(decision);

        R<DataPermScopeVo> resp = controller.evaluate(
                new DataPermEvaluateRequest("admin", "leave", "list", null));

        assertThat(resp.getData().isRowAll()).isTrue();
        assertThat(resp.getData().getRowGroups()).isEmpty();
        assertThat(resp.getData().getAccounts()).isEmpty();
    }

    private DataPermDecision decision(DataScope dataScope, ColumnScope columnScope) {
        DataPermDecision decision = new DataPermDecision();
        decision.setResource("bpmn_approval");
        decision.setAccount("who");
        decision.setOperation("list");
        decision.setDataScope(dataScope);
        decision.setColumnScope(columnScope);
        // 谓词轮机械补：toScopeVo 改读 rowFilter（既有断言零变化——无条件态与 dataScope 同义）
        decision.setRowFilter(dataScope.isAll() ? RowFilter.rowAll()
                : RowFilter.of(dataScope.getAccounts().isEmpty() ? List.of()
                        : List.of(RowGroup.of(false, dataScope.getAccounts(), List.of()))));
        return decision;
    }
}
