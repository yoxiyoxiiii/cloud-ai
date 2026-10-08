package com.cloudai.bpmn.exception;

import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * multipart 解析层超限归口直测（设计 D4 三段式第 3 段）：code=4009、msg 与 Service 前置校验
 * 逐字一致（契约 §4），HTTP 200 + body 形态由 @RestControllerAdvice 返回 R 保证。
 */
class MultipartLimitExceptionHandlerTest {

    @Test
    void handleMaxUploadSizeExceeded_returns4009WithExactMsg() {
        MultipartLimitExceptionHandler handler = new MultipartLimitExceptionHandler();
        MaxUploadSizeExceededException exception = new MaxUploadSizeExceededException(3 * 1024 * 1024);

        R<Void> result = handler.handleMaxUploadSizeExceeded(exception);

        assertThat(result.getCode()).isEqualTo(4009);
        assertThat(result.getMsg()).isEqualTo("流程文件无效或部署失败");
        assertThat(result.getData()).isNull();
    }

    @Test
    void advice_orderedHighestPrecedence() throws Exception {
        // 先于 common GlobalExceptionHandler 兜底 @ExceptionHandler(Exception.class) 遍历，
        // 否则 MaxUploadSizeExceededException 会被吞成 500 形态（实现期记档决策）
        Order order = MultipartLimitExceptionHandler.class.getAnnotation(Order.class);
        assertThat(order).isNotNull();
        assertThat(order.value()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }
}
