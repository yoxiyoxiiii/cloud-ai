package com.cloudai.common.core.exception;

import com.cloudai.common.core.domain.R;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：适用于 WebMVC 注解 controller。
 * WebFlux 校验异常（WebExchangeBindException）与网关 filter 层错误不在覆盖范围
 * （网关统一错误 JSON 需 ErrorWebExceptionHandler，见设计文档）。
 */
@AutoConfiguration
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：原样透出错误码与消息（warn 级审计，不含堆栈） */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        log.warn("业务异常 code={} msg={}", e.getCode(), e.getMessage());
        return R.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败（@Valid）：字段错误带字段名，对象级错误只带消息 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        StringBuilder msg = new StringBuilder();
        e.getBindingResult().getAllErrors()
                .forEach(oe -> msg.append(oe instanceof FieldError fe
                                ? fe.getField() + " " + oe.getDefaultMessage()
                                : oe.getDefaultMessage())
                        .append("; "));
        return R.fail(ErrorCode.PARAM_ERROR, msg.toString());
    }

    /** 兜底：未知异常统一 500，不向前端暴露细节，但服务端必须留痕 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("未处理异常", e);
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }
}
