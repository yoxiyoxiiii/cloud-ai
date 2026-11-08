package com.cloudai.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 模板新增入参（契约 2026-11-08-notify-api §1.3）。Bean Validation 全集失败 1001；
 * code 重复（含墓碑占键）由 Service 查重 + DuplicateKey 兜底转 3043。
 */
@Data
public class NotifyTemplateAddRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模板编码（小写下划线，库级唯一） */
    @NotBlank
    @Size(min = 2, max = 50)
    @Pattern(regexp = "^[a-z][a-z0-9_]*$")
    private String code;

    /** 模板名称 */
    @NotBlank
    @Size(min = 2, max = 50)
    private String name;

    /** 标题模板（支持 ${var} 占位符，解析后上限 100 列宽超长 3046） */
    @NotBlank
    @Size(max = 100)
    private String titleTemplate;

    /** 内容模板（支持 ${var} 占位符，解析后上限 1000 列宽超长 3046） */
    @NotBlank
    @Size(max = 1000)
    private String contentTemplate;

    /** 消息类型：1公告 2提醒 */
    @NotNull
    @Min(1)
    @Max(2)
    private Integer type;

    /** 消息级别：0普通 1重要 2紧急 */
    @NotNull
    @Min(0)
    @Max(2)
    private Integer level;

    /** 变量说明（人工维护文档文本，可空，缺省空串） */
    @Size(max = 500)
    private String variablesDesc;
}
