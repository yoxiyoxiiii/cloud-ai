package com.cloudai.bpmn.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 流程定义 VO（契约 2026-10-07-bpmn-leave-api §4.1：id/key/name/version/deploymentTime，
 * version 为 int 字符串化；无翻译字段——@TranslateVO 不需要）。
 */
@Data
public class DefinitionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 定义 id（引擎字符串形态，如 leave_approval:1:4） */
    private String id;

    /** 定义 key */
    private String key;

    /** 定义名 */
    private String name;

    /** 版本（int 字符串化） */
    private String version;

    /** 部署时间（yyyy-MM-dd HH:mm:ss；查不到为 null） */
    private String deploymentTime;
}
