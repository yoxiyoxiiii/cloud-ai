package com.cloudai.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 模板修改入参（契约 2026-11-08-notify-api §1.4）：新增全字段 + id 必填置首。
 * code 允许修改（同样查重 3043）；内置模板（isBuiltin=1）内容可改，仅禁删（3049 在删除端点）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotifyTemplateEditRequest extends NotifyTemplateAddRequest {

    private static final long serialVersionUID = 1L;

    /** 模板ID（必填；不存在或已删 3044） */
    @NotNull
    private Long id;
}
