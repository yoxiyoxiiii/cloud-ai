package com.cloudai.bpmn.exception;

import com.cloudai.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * multipart 解析层超限归口（契约 §2.2 三段式第 3 段 / 设计 D4）：仅拦
 * MaxUploadSizeExceededException（文件 ≥3MB 或请求 ≥4MB，Spring multipart 解析层拒绝），
 * 归口 R.fail(4009)——全尺寸域超限观测形态恒 4009（2MB 业务上限在 Service 前置校验，同码）。
 * common GlobalExceptionHandler 零触碰红线——本类为 cloud-bpmn 本地 advice。
 * @Order 最高优先级：common 兜底 @ExceptionHandler(Exception.class) 同样能匹配本异常，
 * advice 遍历按 order 先到先得，不显式置前会被兜底吞成 500 形态（实现期记档决策）。
 */
@Slf4j
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MultipartLimitExceptionHandler {

    /** 解析层超限：HTTP 200 + body 4009，msg 与 Service 前置校验逐字一致（契约 §4） */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.error("multipart 解析层超限（≥3MB/4MB 上限）", e);
        R<Void> result = R.fail(4009, "流程文件无效或部署失败");
        return result;
    }
}
