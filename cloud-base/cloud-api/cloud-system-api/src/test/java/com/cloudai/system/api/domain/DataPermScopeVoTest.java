package com.cloudai.system.api.domain;

import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataScope;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 窄契约出参转换单测（设计 D19）：toDataScope/toColumnScope 契约模型→终态对象转换 +
 * rowAll 短路 + 空集合边界 + 无参构造默认空列表（Feign 反序列化前提）；
 * getSummary 格式快照防 D28 label 内联漂移（输出与原实体枚举 label 逐字一致）。
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
}
