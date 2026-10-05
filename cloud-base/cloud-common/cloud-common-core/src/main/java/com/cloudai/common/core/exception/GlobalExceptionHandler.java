package com.cloudai.common.core.exception;

import com.cloudai.common.core.domain.R;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：自动装配，引入 common-core 的 web 服务即生效（WebMVC 与 WebFlux 均适用）
 */
@AutoConfiguration
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：原样透出错误码与消息 */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败（@Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        StringBuilder msg = new StringBuilder();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> msg.append(fe.getField()).append(" ").append(fe.getDefaultMessage()).append("; "));
        return R.fail(ErrorCode.PARAM_ERROR, msg.toString());
    }

    /** 兜底：未知异常统一 500，不向外暴露堆栈细节 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }
}
