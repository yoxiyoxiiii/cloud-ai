package com.cloudai.system.dto;

import com.cloudai.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 模板分页查询入参（契约 2026-11-08-notify-api §1.1：name/code 模糊 + type 精确）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NotifyTemplatePageQuery extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模板名称模糊匹配 */
    private String name;

    /** 模板编码模糊匹配 */
    private String code;

    /** 消息类型精确匹配：1公告 2提醒（SysNotifyTemplate.TypeEnum） */
    private Integer type;
}
