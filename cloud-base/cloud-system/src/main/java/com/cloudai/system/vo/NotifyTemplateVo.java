package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 模板出参 VO（契约 2026-11-08-notify-api §1.1：列表与详情共用，详情=单行同构）。
 * deleted 不进 VO；Long 序列化为 String 防前端精度丢失（全局 Jackson 口径）。
 */
@Data
public class NotifyTemplateVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模板ID */
    private Long id;

    /** 模板编码（如 welcome_notice） */
    private String code;

    /** 模板名称 */
    private String name;

    /** 标题模板（含 ${var} 占位符） */
    private String titleTemplate;

    /** 内容模板（含 ${var} 占位符） */
    private String contentTemplate;

    /** 消息类型：1公告 2提醒 */
    private Integer type;

    /** 消息级别：0普通 1重要 2紧急 */
    private Integer level;

    /** 变量说明（人工维护文档文本，可空串） */
    private String variablesDesc;

    /** 1=内置模板（禁删）0=用户创建 */
    private Integer isBuiltin;

    /** 创建人账号 */
    private String createBy;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
