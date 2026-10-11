package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行级终态判定单测（设计 D31/D34，2026-10-11 谓词轮）：rowAll 短路；单组无谓词与
 * DataScope.allows 语义等价（D40 红线证据用例）；allAccounts+谓词 owner 无关放行；
 * 组内 AND；账号维不过即拒；isEmptyScope。
 */
class RowFilterTest {

    private static final Function<String, Object> ID_50 = field -> "id".equals(field) ? 50L : null;
    private static final Function<String, Object> ID_150 = field -> "id".equals(field) ? 150L : null;

    // ---- rowAll 短路 ----

    @Test
    void rowAll_shortCircuit_evenNullValues() {
        RowFilter filter = RowFilter.rowAll();

        assertThat(filter.isRowAll()).isTrue();
        assertThat(filter.allowsDetail("anyone", field -> null)).isTrue();
        assertThat(filter.isEmptyScope()).isFalse();
    }

    // ---- D40 等价性：单组无谓词 ≡ 旧 DataScope.allows ----

    @Test
    void singleGroupNoPredicates_equivalentToDataScopeAllows() {
        Set<String> accounts = Set.of("userA", "userB");
        RowFilter filter = RowFilter.of(List.of(RowGroup.of(false, accounts, List.of())));
        DataScope legacy = DataScope.of(accounts);

        // 逐一对照：无条件规则的 allowsDetail 与旧 allows 行集语义逐字等价（红线证据；
        // null owner 不入对照——legacy 不可变 Set.contains(null) 本就 NPE，非对齐义务面）
        for (String owner : new String[]{"userA", "userB", "userC"}) {
            assertThat(filter.allowsDetail(owner, field -> null))
                    .isEqualTo(legacy.allows(owner));
        }
    }

    @Test
    void emptyGroups_emptyScope() {
        RowFilter filter = RowFilter.of(List.of());

        assertThat(filter.isEmptyScope()).isTrue();
        assertThat(filter.allowsDetail("userA", field -> null)).isFalse();
    }

    // ---- allAccounts + 谓词：owner 无关授权 ----

    @Test
    void allAccountsPredicate_ownerIrrelevant() {
        RowGroup group = RowGroup.of(true, Set.of(), List.of(
                RowPredicate.of("id", "id", PredicateOperatorEnum.BETWEEN,
                        List.of(new java.math.BigDecimal("1"), new java.math.BigDecimal("100")))));
        RowFilter filter = RowFilter.of(List.of(group));

        // 他人单 id=50 可见（owner 无关），本人单 id=150 不可见（行属性判定的本质用例，D34）
        assertThat(filter.allowsDetail("otherUser", ID_50)).isTrue();
        assertThat(filter.allowsDetail("selfUser", ID_150)).isFalse();
    }

    @Test
    void unknownField_nullValue_failsGroup_failClosed() {
        RowGroup group = RowGroup.of(true, Set.of(), List.of(
                RowPredicate.of("id", "id", PredicateOperatorEnum.EQ,
                        List.of(new java.math.BigDecimal("1")))));
        RowFilter filter = RowFilter.of(List.of(group));

        // 取值器未知字段 → null → 谓词不满足 → 组失败 → 拒绝（fail-closed：配置漂移不放大授权）
        assertThat(filter.allowsDetail("anyone", field -> null)).isFalse();
    }

    // ---- 组内 AND：账号维过、条件维不过 → 拒 ----

    @Test
    void accountMatchesButPredicateFails_rejected() {
        RowGroup group = RowGroup.of(false, Set.of("userA"), List.of(
                RowPredicate.of("id", "id", PredicateOperatorEnum.GTE,
                        List.of(new java.math.BigDecimal("100")))));
        RowFilter filter = RowFilter.of(List.of(group));

        assertThat(filter.allowsDetail("userA", ID_50)).isFalse();
        assertThat(filter.allowsDetail("userA", ID_150)).isTrue();
    }

    @Test
    void accountDimFails_evenPredicatesPass_rejected() {
        RowGroup group = RowGroup.of(false, Set.of("userA"), List.of(
                RowPredicate.of("id", "id", PredicateOperatorEnum.GTE,
                        List.of(new java.math.BigDecimal("1")))));
        RowFilter filter = RowFilter.of(List.of(group));

        assertThat(filter.allowsDetail("userB", ID_50)).isFalse();
    }

    // ---- 组间 OR 并集 ----

    @Test
    void groupsUnion_anyGroupMatchesAllows() {
        RowGroup groupA = RowGroup.of(false, Set.of("userA"), List.of());
        RowGroup groupB = RowGroup.of(true, Set.of(), List.of(
                RowPredicate.of("id", "id", PredicateOperatorEnum.EQ,
                        List.of(new java.math.BigDecimal("150")))));
        RowFilter filter = RowFilter.of(List.of(groupA, groupB));

        // userB 不在 A 组账号，但 B 组（ALL+id=150）按行属性放行——跨规则并集
        assertThat(filter.allowsDetail("userB", ID_50)).isFalse();
        assertThat(filter.allowsDetail("userB", ID_150)).isTrue();
        assertThat(filter.allowsDetail("userA", ID_50)).isTrue();
    }
}
