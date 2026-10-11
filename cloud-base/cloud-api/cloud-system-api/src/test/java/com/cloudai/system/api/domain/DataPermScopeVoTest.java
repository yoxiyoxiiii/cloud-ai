package com.cloudai.system.api.domain;

import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataScope;
import com.cloudai.system.api.dataperm.FilterColumnTypeEnum;
import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.api.dataperm.RowPredicate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 窄契约出参转换单测（设计 D19 + 2026-10-11 谓词轮 D31/D35 toRowFilter 增量）：
 * toDataScope/toColumnScope 契约模型→终态对象转换 + rowAll 短路 + 空集合边界 +
 * 无参构造默认空列表（Feign 反序列化前提）；getSummary 格式快照防 D28 label 内联漂移
 * （输出与原实体枚举 label 逐字一致）；toRowFilter 分组并集转换 + 值类型化 +
 * v1 形态回退（四参构造兼容，D40）+ 归一失败 fail-closed 降级。
 */
class DataPermScopeVoTest {

    // ---- toDataScope ----

    @Test
    void toDataScope_rowAll_exemptFiltering() {
        DataScope scope = new DataPermScopeVo(true, List.of(), List.of(), List.of()).toDataScope();

        assertThat(scope.isAll()).isTrue();
        assertThat(scope.isEmptyScope()).isFalse();
        assertThat(scope.allows("anyone")).isTrue();
    }

    @Test
    void toDataScope_whitelist_byAccountSet() {
        DataScope scope = new DataPermScopeVo(false, List.of("userA", "userB"), List.of(), List.of())
                .toDataScope();

        assertThat(scope.isAll()).isFalse();
        assertThat(scope.allows("userA")).isTrue();
        assertThat(scope.allows("userC")).isFalse();
    }

    @Test
    void toDataScope_emptyAccounts_emptyScopeShortCircuit() {
        DataScope scope = new DataPermScopeVo(false, List.of(), List.of(), List.of()).toDataScope();

        // 契约 §5.1：空数组 + rowAll=false = 空集短路（消费方不查库直接空页）
        assertThat(scope.isEmptyScope()).isTrue();
        assertThat(scope.allows("userA")).isFalse();
    }

    // ---- toColumnScope ----

    @Test
    void toColumnScope_hiddenAndMasked() {
        ColumnScope columnScope = new DataPermScopeVo(
                false, List.of(), List.of("title"), List.of("reason")).toColumnScope();

        assertThat(columnScope.isHidden("title")).isTrue();
        assertThat(columnScope.isMasked("reason")).isTrue();
        assertThat(columnScope.isEmpty()).isFalse();
    }

    @Test
    void toColumnScope_empty_noAction() {
        ColumnScope columnScope = new DataPermScopeVo(true, List.of(), List.of(), List.of()).toColumnScope();

        assertThat(columnScope.isEmpty()).isTrue();
    }

    @Test
    void toColumnScope_summaryFormat_d28FrozenByContract() {
        // D28 标签内联后格式快照（hidden 前 masked 后各自字典序）——契约 §6.8 冻结点，与搬家前实现逐字一致
        ColumnScope columnScope = new DataPermScopeVo(
                false, List.of(), List.of("title"), List.of("reason")).toColumnScope();

        assertThat(columnScope.getSummary()).isEqualTo("title:隐藏;reason:脱敏");
    }

    // ---- 构造口径 ----

    @Test
    void noArgsConstructor_defaultsToEmptyLists() {
        DataPermScopeVo vo = new DataPermScopeVo();

        assertThat(vo.isRowAll()).isFalse();
        assertThat(vo.getAccounts()).isEmpty();
        assertThat(vo.getHiddenColumns()).isEmpty();
        assertThat(vo.getMaskedColumns()).isEmpty();
    }

    @Test
    void allArgsConstructor_fieldOrderRowAllAccountsHiddenMasked() {
        DataPermScopeVo vo = new DataPermScopeVo(true, List.of(), List.of("title"), List.of());

        assertThat(vo.isRowAll()).isTrue();
        assertThat(vo.getHiddenColumns()).containsExactly("title");
    }

    // ---- toRowFilter（2026-10-11 谓词轮增量，D31/D35） ----

    @Test
    void toRowFilter_rowAll_shortCircuit() {
        RowFilter filter = new DataPermScopeVo(true, List.of(), List.of(), List.of()).toRowFilter();

        assertThat(filter.isRowAll()).isTrue();
        assertThat(filter.isEmptyScope()).isFalse();
        assertThat(filter.allowsDetail("anyone", field -> null)).isTrue();
    }

    @Test
    void toRowFilter_valuesTypedDuringConversion() {
        PredicateVo predicate = new PredicateVo("id", "id", "BETWEEN",
                FilterColumnTypeEnum.NUMBER, List.of("1", "100"));
        RowGroupVo groupVo = new RowGroupVo(true, List.of(), List.of(predicate));
        DataPermScopeVo vo = new DataPermScopeVo(false, List.of("userA"), List.of(), List.of(),
                List.of(groupVo));

        RowFilter filter = vo.toRowFilter();

        // 值字符串→类型化（BigDecimal，D33 类型化绑定）；组/谓词结构保序收敛
        assertThat(filter.getGroups()).hasSize(1);
        RowPredicate converted = filter.getGroups().get(0).getPredicates().get(0);
        assertThat(converted.getValues()).containsExactly(new BigDecimal("1"), new BigDecimal("100"));
        assertThat(converted.getField()).isEqualTo("id");
        assertThat(converted.getColumn()).isEqualTo("id");
        // 行属性判定：id=50 他人单放行（allAccounts 组），id=150 拒绝
        assertThat(filter.allowsDetail("other", field -> 50L)).isTrue();
        assertThat(filter.allowsDetail("other", field -> 150L)).isFalse();
    }

    @Test
    void toRowFilter_multiGroups_orderPreserved() {
        RowGroupVo groupA = new RowGroupVo(false, List.of("userA"), List.of());
        RowGroupVo groupB = new RowGroupVo(false, List.of("userB"), List.of());
        DataPermScopeVo vo = new DataPermScopeVo(false, List.of("userA", "userB"), List.of(),
                List.of(), List.of(groupA, groupB));

        RowFilter filter = vo.toRowFilter();

        assertThat(filter.getGroups()).hasSize(2);
        assertThat(filter.getGroups().get(0).getAccounts()).containsExactly("userA");
        assertThat(filter.getGroups().get(1).getAccounts()).containsExactly("userB");
        assertThat(filter.allowsDetail("userB", field -> null)).isTrue();
    }

    @Test
    void toRowFilter_v1Shape_fallbackToUnconditionalGroup() {
        // v1 形态回退（D40）：四参构造（rowGroups 空）+ accounts 非空 → 无条件单组，
        // 与 toDataScope().allows 语义逐字等价（旧 provider/旧调用点零迁移）
        DataPermScopeVo vo = new DataPermScopeVo(false, List.of("userA", "userB"), List.of(), List.of());

        RowFilter filter = vo.toRowFilter();

        assertThat(filter.getGroups()).hasSize(1);
        assertThat(filter.getGroups().get(0).isAllAccounts()).isFalse();
        assertThat(filter.getGroups().get(0).getPredicates()).isEmpty();
        assertThat(filter.allowsDetail("userA", field -> null)).isTrue();
        assertThat(filter.allowsDetail("userC", field -> null)).isFalse();
    }

    @Test
    void toRowFilter_v1ShapeEmptyAccounts_emptyScope() {
        RowFilter filter = new DataPermScopeVo(false, List.of(), List.of(), List.of()).toRowFilter();

        assertThat(filter.isEmptyScope()).isTrue();
    }

    @Test
    void toRowFilter_badPredicate_failClosedNeverSatisfied() {
        // 操作符未知（"LIKE" 非封闭集）→ 该谓词恒不满足占位（组永不放行，不丢弃——丢弃=放大授权）
        PredicateVo bad = new PredicateVo("id", "id", "LIKE",
                FilterColumnTypeEnum.NUMBER, List.of("1"));
        RowGroupVo groupVo = new RowGroupVo(true, List.of(), List.of(bad));
        DataPermScopeVo vo = new DataPermScopeVo(false, List.of(), List.of(), List.of(),
                List.of(groupVo));

        RowFilter filter = vo.toRowFilter();

        assertThat(filter.getGroups()).hasSize(1);
        assertThat(filter.getGroups().get(0).getPredicates()).hasSize(1);
        assertThat(filter.allowsDetail("anyone", field -> 1L)).isFalse();
    }

    @Test
    void toRowFilter_badValue_failClosedNeverSatisfied() {
        // 值归一失败（NUMBER 坏值 "abc"）→ 同款恒不满足降级
        PredicateVo bad = new PredicateVo("id", "id", "EQ",
                FilterColumnTypeEnum.NUMBER, List.of("abc"));
        RowGroupVo groupVo = new RowGroupVo(true, List.of(), List.of(bad));
        DataPermScopeVo vo = new DataPermScopeVo(false, List.of(), List.of(), List.of(),
                List.of(groupVo));

        assertThat(vo.toRowFilter().allowsDetail("anyone", field -> 1L)).isFalse();
    }
}
