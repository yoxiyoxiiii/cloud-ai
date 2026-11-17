package com.cloudai.common.dataperm;

import com.cloudai.common.dataperm.annotation.DataPermEvaluate;
import com.cloudai.common.dataperm.column.DataPermColumnContext;
import com.cloudai.common.dataperm.domain.ColumnScope;
import com.cloudai.common.dataperm.domain.DataPermOperation;
import com.cloudai.common.dataperm.domain.RowFilter;
import com.cloudai.common.dataperm.domain.RowGroup;
import com.cloudai.common.dataperm.evaluate.DataPermEvaluateAspect;
import com.cloudai.common.dataperm.evaluate.DataPermEvaluation;
import com.cloudai.common.dataperm.evaluate.DataPermEvaluationProvider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 求值切面行为（AspectJProxyFactory 走真实织入路径——顺带实证字面量切点在无 -parameters
 * 编译参数下命中注解）：接线三件（列上下文/求值 holder/businessKey 解析）、fail-closed、
 * 嵌套前值恢复、holder 与列上下文的生命周期分离。
 */
class DataPermEvaluateAspectTest {

    private RowFilter rowAll;
    private RowFilter rowSelf;
    private ColumnScope maskedTitle;
    private DataPermEvaluationProvider backend;
    private ObjectProvider<DataPermEvaluationProvider> provider;
    private SampleService target;
    private SampleService proxy;

    /** 标注样例（资源/操作/businessKey 三形态覆盖） */
    static class SampleService {

        String bodyRecord;

        @DataPermEvaluate(resource = "leave", operation = DataPermOperation.LIST)
        public String listOnly() {
            bodyRecord = "rowAll=" + DataPermEvaluation.currentRowFilter().isRowAll();
            return "ok";
        }

        @DataPermEvaluate(resource = "leave", operation = DataPermOperation.DETAIL, businessKey = "#p0")
        public String detail(Long id) {
            return "id=" + id + ",masked=" + DataPermEvaluation.current().columnScope().getMaskedColumns();
        }

        @DataPermEvaluate(resource = "leave", operation = DataPermOperation.DETAIL, businessKey = "#p1")
        public String detailSecond(String ignored, Long id) {
            return "id=" + id;
        }
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        rowAll = RowFilter.rowAll();
        rowSelf = RowFilter.of(List.of(RowGroup.of(false, java.util.Set.of("admin"), List.of())));
        maskedTitle = ColumnScope.of(java.util.Set.of(), java.util.Set.of("title"));
        backend = mock(DataPermEvaluationProvider.class);
        provider = mock(ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(backend);
        target = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(new DataPermEvaluateAspect(provider));
        proxy = factory.getProxy();
    }

    @BeforeEach
    @AfterEach
    void cleanThreadLocals() {
        DataPermColumnContext.clear();
        DataPermEvaluation.clear();
    }

    /** 求值 + 列上下文接线（空 scope 也 set）+ 方法体取行过滤——切点真实命中注解 */
    @Test
    void listWiresContextAndHolder() {
        when(backend.evaluate("leave", "list", null))
                .thenReturn(new DataPermEvaluation("leave", ColumnScope.of(java.util.Set.of(), java.util.Set.of()), rowAll));
        assertThat(proxy.listOnly()).isEqualTo("ok");
        assertThat(target.bodyRecord).isEqualTo("rowAll=true");
        assertThat(DataPermColumnContext.get()).isNotNull();
        assertThat(DataPermColumnContext.get().resource()).isEqualTo("leave");
        assertThat(DataPermColumnContext.get().columnScope().isEmpty()).isTrue();
    }

    /** businessKey #p0 位置引用：首参 id 落求值入参 + 列 scope 经 holder 可读 */
    @Test
    void detailResolvesBusinessKeyFromFirstArg() {
        when(backend.evaluate("leave", "detail", "5"))
                .thenReturn(new DataPermEvaluation("leave", maskedTitle, rowSelf));
        assertThat(proxy.detail(5L)).isEqualTo("id=5,masked=[title]");
        assertThat(DataPermColumnContext.get().columnScope().getMaskedColumns()).containsExactly("title");
    }

    /** businessKey #p1：第二参引用（多参方法非首参形态） */
    @Test
    void detailResolvesBusinessKeyFromSecondArg() {
        when(backend.evaluate("leave", "detail", "9"))
                .thenReturn(new DataPermEvaluation("leave", maskedTitle, rowAll));
        assertThat(proxy.detailSecond("x", 9L)).isEqualTo("id=9");
    }

    /** 方法返回后 holder 收尾（窗口只在标注方法内）、列上下文保留（请求级，归 filter 清理） */
    @Test
    void holderClearedButColumnContextSurvivesMethod() {
        when(backend.evaluate("leave", "list", null))
                .thenReturn(new DataPermEvaluation("leave", maskedTitle, rowAll));
        proxy.listOnly();
        assertThat(DataPermEvaluation.current()).isNull();
        assertThat(DataPermColumnContext.get()).isNotNull();
    }

    /** 嵌套前值恢复：内层标注方法返回后，外层求值结果不被清空（同请求串行标注调用不串值） */
    @Test
    void nestedCallRestoresPreviousEvaluation() {
        DataPermEvaluation outer = new DataPermEvaluation("leave", maskedTitle, rowSelf);
        DataPermEvaluation.set(outer);
        when(backend.evaluate("leave", "list", null))
                .thenReturn(new DataPermEvaluation("leave", maskedTitle, rowAll));
        proxy.listOnly();
        assertThat(DataPermEvaluation.current()).isSameAs(outer);
    }

    /** 宿主未注册 provider：fail-closed 拒绝调用，方法体不执行（不静默跳过求值） */
    @Test
    void missingProviderFailsClosedWithoutProceed() {
        ObjectProvider<DataPermEvaluationProvider> empty = mock(ObjectProvider.class);
        when(empty.getIfAvailable()).thenReturn(null);
        SampleService isolatedTarget = new SampleService();
        AspectJProxyFactory factory = new AspectJProxyFactory(isolatedTarget);
        factory.addAspect(new DataPermEvaluateAspect(empty));
        SampleService isolated = factory.getProxy();
        assertThatThrownBy(isolated::listOnly)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DataPermEvaluationProvider");
        assertThat(isolatedTarget.bodyRecord).as("方法体不应执行").isNull();
        verifyNoInteractions(backend);
    }

    /** 非法 businessKey 表达式：首调用即抛配置错误（不留静默 null 分支） */
    @Test
    void illegalBusinessKeyExpressionFailsFast() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new BadExpressionService());
        factory.addAspect(new DataPermEvaluateAspect(provider));
        BadExpressionService isolated = factory.getProxy();
        assertThatThrownBy(() -> isolated.detail(5L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("#pN");
    }

    /** businessKey 位置越界：显性拒绝 */
    @Test
    void outOfRangeBusinessKeyIndexFailsFast() {
        AspectJProxyFactory factory = new AspectJProxyFactory(new OutOfRangeService());
        factory.addAspect(new DataPermEvaluateAspect(provider));
        OutOfRangeService isolated = factory.getProxy();
        assertThatThrownBy(() -> isolated.detail())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界");
    }

    /** 求值异常透传：列上下文不被污染（此前已 set 的前值保持，advice 仍按既有值工作） */
    @Test
    void evaluationFailurePropagatesWithoutWiring() {
        when(backend.evaluate("leave", "list", null)).thenThrow(new IllegalStateException("下游不可用"));
        assertThatThrownBy(proxy::listOnly)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("下游不可用");
        assertThat(DataPermColumnContext.get()).isNull();
    }

    static class BadExpressionService {

        @DataPermEvaluate(resource = "leave", operation = DataPermOperation.DETAIL, businessKey = "#id")
        public String detail(Long id) {
            return "unreachable";
        }
    }

    static class OutOfRangeService {

        @DataPermEvaluate(resource = "leave", operation = DataPermOperation.DETAIL, businessKey = "#p0")
        public String detail() {
            return "unreachable";
        }
    }
}
