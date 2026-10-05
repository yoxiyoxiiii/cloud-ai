package com.cloudai.common.core.exception;

import lombok.Getter;

/**
 * 统一错误码：1xxx 通用 / 2xxx 认证 / 3xxx system / 4xxx bpmn（后续阶段按需追加）
 */
@Getter
public enum ErrorCode {

    SUCCESS(200, "操作成功"),
    SYSTEM_ERROR(500, "系统异常，请稍后重试"),
    UNAUTHORIZED(401, "认证失败或未登录"),
    FORBIDDEN(403, "无操作权限"),
    PARAM_ERROR(1001, "参数校验失败"),
    BUSINESS_ERROR(1002, "业务处理失败");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
