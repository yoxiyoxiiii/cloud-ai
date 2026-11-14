package com.cloudai.bpmn.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 待办任务出参镜像（外部面出参镜像，随契约 2026-11-14-aggregate-workbench-api §3 同步）。
 * 字段与下游 com.cloudai.bpmn.vo.TaskVo 逐字一致（9 字段全 String）；
 * 不带任何注解——翻译注解、数据权限标记都是下游的事，聚合透传场景纯转发 JSON。
 * 字段漂移检查基准：cloud-aggregate 的 AggregateArchitectureGuardTest 字段清单断言。
 */
@Data
public class TaskVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 引擎任务 id */
    private String taskId;

    /** 审批单 id */
    private String approvalId;

    /** 业务类型编码 */
    private String businessType;

    /** 业务类型名（配置表渲染，必返非空） */
    private String businessTypeName;

    /** 单据标题快照 */
    private String title;

    /** 详情跳转路径（未配/渲染失败 null） */
    private String detailPath;

    /** 申请人 account */
    private String applyUser;

    /** 申请人昵称译文（未命中 null） */
    private String applyUserName;

    /** 任务创建时间（yyyy-MM-dd HH:mm:ss） */
    private String createTime;
}
