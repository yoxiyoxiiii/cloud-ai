package com.cloudai.system.api.dataperm;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * advice 统一列应用单测（设计 §7.1 十条用例矩阵逐条落 + 两防御补充）：
 * 列表/嵌套/数组三形态应用、fail-closed 3042 红态（data 丢弃）、空 scope 全可见、
 * 白名单外树零处理、自引用防环、supports 红绿、非列字段零污染、空集合不触发、非 R body 分支、
 * null scope 上下文短路。真组件真 ThreadLocal 不 mock 静态；@AfterEach clear 防跨用例污染。
 */
class DataPermColumnAdviceTest {

    private final DataPermColumnAdvice advice = new DataPermColumnAdvice();

    @AfterEach
    void tearDown() {
        DataPermColumnContext.clear();
    }

    // ---- 既有 controller 形态的假方法：supports 泛型解析样本（沿翻译单测形态） ----

    @SuppressWarnings("unused")
    R<PageResult<TestColumnVo>> samplePage() {
        return null;
    }

    @SuppressWarnings("unused")
    R<TestDetailWrapperVo> sampleDetail() {
        return null;
    }

    @SuppressWarnings("unused")
    R<TestColumnVo[]> sampleArray() {
        return null;
    }

    @SuppressWarnings("unused")
    R<PlainVo> samplePlain() {
        return null;
    }

    @SuppressWarnings("unused")
    R<Void> sampleVoid() {
        return null;
    }

    // ---- 用例矩阵（§7.1 1-10 + 防御补充） ----

    @Test
    void listForm_columnActionsApplied() throws Exception {
        // 1. R<PageResult<TestColumnVo>> + ctx(hidden title/masked reason) → 两字段改写
        TestColumnVo vo = columnVo("t", "r");
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of("title"), Set.of("reason")));
        R<PageResult<TestColumnVo>> body = R.ok(PageResult.of(1, List.of(vo)));

        Object result = write(body, "samplePage");

        assertThat(result).isSameAs(body);
        assertThat(vo.getTitle()).isNull();
        assertThat(vo.getReason()).isEqualTo("***");
    }

    @Test
    void nestedWrapper_drilledAndApplied() throws Exception {
        // 2. R<TestDetailWrapperVo> → 下钻 inner 应用（嵌套红态核心，D55——列处理无逃生门）
        TestColumnVo inner = columnVo("t", "r");
        TestDetailWrapperVo wrapper = new TestDetailWrapperVo();
        wrapper.setInner(inner);
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of("title"), Set.of("reason")));
        R<TestDetailWrapperVo> body = R.ok(wrapper);

        Object result = write(body, "sampleDetail");

        assertThat(result).isSameAs(body);
        assertThat(inner.getTitle()).isNull();
        assertThat(inner.getReason()).isEqualTo("***");
    }

    @Test
    void whitelistInTree_contextMissing_failClosed3042() throws Exception {
        // 3. fail-closed 红态：白名单在树、上下文空 → body 改写 R.fail(3042) 且 data 丢弃
        TestColumnVo vo = columnVo("t", "r");
        R<PageResult<TestColumnVo>> body = R.ok(PageResult.of(1, List.of(vo)));

        Object result = write(body, "samplePage");

        assertThat(result).isNotSameAs(body).isInstanceOf(R.class);
        R<?> rejected = (R<?>) result;
        assertThat(rejected.getCode()).isEqualTo(3042);
        assertThat(rejected.getMsg()).isEqualTo("数据权限列处理异常，响应已拒绝");
        assertThat(rejected.getData()).isNull();
        // 原 VO 不因拒绝路径被改写（数据不返回，明文不外泄）
        assertThat(vo.getTitle()).isEqualTo("t");
    }

    @Test
    void emptyScope_allColumnsVisible() throws Exception {
        // 4. set 空 scope（无列动作）→ 原样全可见（set 语义=接线声明，非数据，D52）
        TestColumnVo vo = columnVo("t", "r");
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of(), Set.of()));
        R<PageResult<TestColumnVo>> body = R.ok(PageResult.of(1, List.of(vo)));

        Object result = write(body, "samplePage");

        assertThat(result).isSameAs(body);
        assertThat(vo.getTitle()).isEqualTo("t");
        assertThat(vo.getReason()).isEqualTo("r");
    }

    @Test
    void plainVo_noAnnotation_noContext_originalBody() throws Exception {
        // 5. R<PlainVo> 无注解 → 原样零处理（上下文空也不触发 3042）
        PlainVo vo = new PlainVo();
        vo.setName("n");
        R<PlainVo> body = R.ok(vo);

        Object result = write(body, "samplePlain");

        assertThat(result).isSameAs(body);
        assertThat(vo.getName()).isEqualTo("n");
    }

    @Test
    void selfReference_noInfiniteLoop() throws Exception {
        // 6. SelfRefVo 自引用 → 不死循环、正常返回（白名单消费后不下钻其字段 + IdentitySet 防环）
        SelfRefVo vo = new SelfRefVo();
        vo.setTitle("t");
        vo.setNext(vo);
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of("title"), Set.of()));

        Object result = write(R.ok(PageResult.of(1, List.of(vo))), "samplePage");

        assertThat(result).isInstanceOf(R.class);
        assertThat(((R<?>) result).getCode()).isEqualTo(200);
        assertThat(vo.getTitle()).isNull();
    }

    @Test
    void supports_whitelistReachableTrue_othersFalse() throws Exception {
        // 7. supports 红绿：泛型链/字段闭包可达白名单 true；普通 VO / R<Void> false
        assertThat(advice.supports(returnType("samplePage"), Converter.class)).isTrue();
        assertThat(advice.supports(returnType("sampleDetail"), Converter.class)).isTrue();
        assertThat(advice.supports(returnType("sampleArray"), Converter.class)).isTrue();
        assertThat(advice.supports(returnType("samplePlain"), Converter.class)).isFalse();
        assertThat(advice.supports(returnType("sampleVoid"), Converter.class)).isFalse();
    }

    @Test
    void treeTouchesOnlyColumnFields() throws Exception {
        // 8. 树遍历只改列字段：statusLabel/id 非列字段断言原值
        TestColumnVo vo = columnVo("t", "r");
        vo.setStatusLabel("译文字段归翻译 advisor");
        vo.setId(7L);
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of("title"), Set.of("reason")));

        write(R.ok(PageResult.of(1, List.of(vo))), "samplePage");

        assertThat(vo.getStatusLabel()).isEqualTo("译文字段归翻译 advisor");
        assertThat(vo.getId()).isEqualTo(7L);
    }

    @Test
    void emptyRows_noContext_originalBody() throws Exception {
        // 9. PageResult.rows=[] 且上下文空 → 原样（无白名单实例不触发 fail-closed）
        R<PageResult<TestColumnVo>> body = R.ok(PageResult.of(0, List.of()));

        Object result = write(body, "samplePage");

        assertThat(result).isSameAs(body);
    }

    @Test
    void nonRBody_dataBranchProcessed() throws Exception {
        // 10. body 非 R 形态（防御）：data 分支处理 + 正常路径原样返回
        TestColumnVo inner = columnVo("t", "r");
        TestDetailWrapperVo wrapper = new TestDetailWrapperVo();
        wrapper.setInner(inner);
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of("title"), Set.of()));

        Object result = write(wrapper, "sampleDetail");

        assertThat(result).isSameAs(wrapper);
        assertThat(inner.getTitle()).isNull();
        assertThat(inner.getReason()).isEqualTo("r");
    }

    @Test
    void arrayBody_elementsApplied() throws Exception {
        // 防御补充：数组容器展开逐元素应用
        TestColumnVo first = columnVo("t1", "r1");
        TestColumnVo second = columnVo("t2", "r2");
        DataPermColumnContext.set("bpmn_approval", ColumnScope.of(Set.of(), Set.of("title")));

        write(R.ok(new TestColumnVo[] {first, second}), "sampleArray");

        assertThat(first.getTitle()).isEqualTo("***");
        assertThat(second.getTitle()).isEqualTo("***");
        assertThat(first.getReason()).isEqualTo("r1");
    }

    @Test
    void nullScopeContext_applierShortCircuits() throws Exception {
        // 防御补充：set(resource, null)——决策在而 scope null → applier 短路，原样全可见
        TestColumnVo vo = columnVo("t", "r");
        R<PageResult<TestColumnVo>> body = R.ok(PageResult.of(1, List.of(vo)));
        DataPermColumnContext.set("leave", null);

        Object result = write(body, "samplePage");

        assertThat(result).isSameAs(body);
        assertThat(vo.getTitle()).isEqualTo("t");
        assertThat(vo.getReason()).isEqualTo("r");
    }

    // ---- 脚手架（沿 TranslateAdvisorTest 形态） ----

    private TestColumnVo columnVo(String title, String reason) {
        TestColumnVo vo = new TestColumnVo();
        vo.setTitle(title);
        vo.setReason(reason);
        return vo;
    }

    private Object write(Object body, String methodName) throws Exception {
        return advice.beforeBodyWrite(body, returnType(methodName), MediaType.APPLICATION_JSON,
                Converter.class,
                new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(new MockHttpServletResponse()));
    }

    private MethodParameter returnType(String methodName) throws NoSuchMethodException {
        return new MethodParameter(DataPermColumnAdviceTest.class.getDeclaredMethod(methodName), -1);
    }

    /** converterType 形参占位（advice 不读其内容） */
    private static final class Converter implements HttpMessageConverter<Object> {
        @Override
        public boolean canRead(Class<?> clazz, MediaType mediaType) {
            return false;
        }

        @Override
        public boolean canWrite(Class<?> clazz, MediaType mediaType) {
            return false;
        }

        @Override
        public List<MediaType> getSupportedMediaTypes() {
            return List.of();
        }

        @Override
        public Object read(Class<?> clazz, org.springframework.http.HttpInputMessage inputMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(Object o, MediaType contentType,
                          org.springframework.http.HttpOutputMessage outputMessage) {
            throw new UnsupportedOperationException();
        }
    }
}
