package com.cloudai.bpmn.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 定义 XML VO（契约 2026-10-08-bpmn-diagram-designer-api §2.1：id/key/name/version/xml 五字段，
 * version 为 int 字符串化；xml 为部署时原始资源字符串（UTF-8），非 BpmnModel 往返重建——注释等细节零丢失）。
 */
@Data
public class DefinitionXmlVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 定义 id（回显入参，引擎字符串形态，如 leave_approval:1:4） */
    private String id;

    /** 定义 key */
    private String key;

    /** 定义名（引擎可不设，可空） */
    private String name;

    /** 版本（int 字符串化） */
    private String version;

    /** BPMN 2.0 XML 原文（含中文，UTF-8） */
    private String xml;
}
