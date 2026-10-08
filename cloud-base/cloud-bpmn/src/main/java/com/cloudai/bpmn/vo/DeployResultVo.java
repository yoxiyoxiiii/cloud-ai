package com.cloudai.bpmn.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 部署结果 VO（契约 2026-10-08-bpmn-diagram-designer-api §2.2：deploymentId + 本次部署产生的定义
 * ——不做 latestVersion 过滤，返回的就是本次产生的版本；DefinitionVo 复用 v1 §4.1）。
 */
@Data
public class DeployResultVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 部署 id */
    private String deploymentId;

    /** 本次部署产生的定义（同 key 原样重部署亦产生新版本） */
    private List<DefinitionVo> definitions;
}
