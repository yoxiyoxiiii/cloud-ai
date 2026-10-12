package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 列处理上下文单测（设计 §7.1）：set/get 往返、clear 归零、null scope 防御、后 set 覆盖前 set
 * （单资源语义，D52）——真 ThreadLocal 直测不 mock 静态；@AfterEach clear 防跨用例污染。
 */
class DataPermColumnContextTest {

    @AfterEach
    void tearDown() {
        DataPermColumnContext.clear();
    }

    @Test
    void setGet_roundTripsResourceAndScope() {
        ColumnScope scope = ColumnScope.of(Set.of("title"), Set.of("reason"));

        DataPermColumnContext.set("leave", scope);

        DataPermColumnContext.ColumnDecision decision = DataPermColumnContext.get();
        assertThat(decision).isNotNull();
        assertThat(decision.resource()).isEqualTo("leave");
        assertThat(decision.columnScope()).isSameAs(scope);
        assertThat(decision.columnScope().getHiddenColumns()).containsExactly("title");
        assertThat(decision.columnScope().getMaskedColumns()).containsExactly("reason");
    }

    @Test
    void clear_getReturnsNull() {
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of(), Set.of()));
        DataPermColumnContext.clear();

        assertThat(DataPermColumnContext.get()).isNull();
    }

    @Test
    void nullScope_setDoesNotThrow() {
        // 防御：scope 为 null 的 set 不抛（消费侧 applier null 短路，D52 记档形态）
        assertThatCode(() -> DataPermColumnContext.set("leave", null)).doesNotThrowAnyException();

        DataPermColumnContext.ColumnDecision decision = DataPermColumnContext.get();
        assertThat(decision).isNotNull();
        assertThat(decision.resource()).isEqualTo("leave");
        assertThat(decision.columnScope()).isNull();
    }

    @Test
    void secondSet_overridesFirst() {
        // 单请求单资源：后 set 覆盖前 set（多资源同响应为移交项，设计 §11.6）
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of("title"), Set.of()));
        DataPermColumnContext.set("bpmn_approval", ColumnScope.of(Set.of(), Set.of("title")));

        DataPermColumnContext.ColumnDecision decision = DataPermColumnContext.get();
        assertThat(decision.resource()).isEqualTo("bpmn_approval");
        assertThat(decision.columnScope().getHiddenColumns()).isEmpty();
        assertThat(decision.columnScope().getMaskedColumns()).containsExactly("title");
    }
}
