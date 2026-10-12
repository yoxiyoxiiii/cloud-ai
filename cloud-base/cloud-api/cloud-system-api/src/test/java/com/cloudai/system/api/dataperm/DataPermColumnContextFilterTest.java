package com.cloudai.system.api.dataperm;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 上下文清理 filter 单测（设计 §7.1 串号护栏三红态）：入口清（chain 内看不到入口前残留）、
 * finally 清（chain 内 set 的值 doFilter 返回后归零）、异常路径（chain 抛出传播且 finally 已清）。
 */
class DataPermColumnContextFilterTest {

    private final DataPermColumnContextFilter filter = new DataPermColumnContextFilter();

    @AfterEach
    void tearDown() {
        DataPermColumnContext.clear();
    }

    @Test
    void residualContextClearedBeforeChain() throws ServletException, IOException {
        // 红态①（入口清）：doFilter 前的任意残留（上一请求未知形态）在 chain 内不可见
        DataPermColumnContext.set("leave", ColumnScope.of(Set.of("title"), Set.of()));
        AtomicBoolean assertedInChain = new AtomicBoolean(false);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> {
                    assertedInChain.set(true);
                    assertThat((Object) DataPermColumnContext.get()).isNull();
                });

        assertThat(assertedInChain.get()).isTrue();
    }

    @Test
    void contextSetInsideChain_clearedAfterDoFilterReturns() throws ServletException, IOException {
        // 红态②（finally 清）：chain 内 set（模拟 Service 接线）→ doFilter 返回后归零
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> DataPermColumnContext.set("leave",
                        ColumnScope.of(Set.of("title"), Set.of())));

        assertThat(DataPermColumnContext.get()).isNull();
    }

    @Test
    void chainThrows_propagatesAndContextCleared() {
        // 红态③（异常路径）：chain 异常向上传播（不吞），finally 已清——线程池复用无串号窗口
        RuntimeException boom = new RuntimeException("chain exploded");

        RuntimeException thrown = catchThrowableOfType(() ->
                filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                        (request, response) -> {
                            DataPermColumnContext.set("leave", ColumnScope.of(Set.of(), Set.of()));
                            throw boom;
                        }), RuntimeException.class);

        assertThat(thrown).isSameAs(boom);
        assertThat((Object) DataPermColumnContext.get()).isNull();
    }
}
