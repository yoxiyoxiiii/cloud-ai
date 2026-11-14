package com.cloudai.bpmn.api.domain;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 审批单出参镜像（外部面出参镜像，随契约 2026-11-14-aggregate-workbench-api §3 同步）。
 * 字段与下游 com.cloudai.bpmn.vo.ApprovalVo 逐字一致（15 字段；id 为 Long——
 * 全局 Jackson Long→String 口径下序列化形态是字符串）；不带任何注解——
 * 翻译注解、数据权限标记都是下游的事，聚合透传场景纯转发 JSON。
 * 字段漂移检查基准：cloud-aggregate 的 AggregateArchitectureGuardTest 字段清单断言。
 */
@Data
public class ApprovalVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审批单 id（序列化后 String；即流程实例 businessKey） */
    private Long id;

    /** 业务类型编码 */
    private String businessType;

    /** 业务类型名（配置表渲染，必返非空） */
    private String businessTypeName;

    /** 业务单据标识 */
    private String businessKey;

    /** 标题快照 */
    private String title;

    /** 状态值（"0"-"3"，字典 bpmn_approval_status） */
    private String status;

    /** 状态译文（未命中 null） */
    private String statusLabel;

    /** 申请人 account */
    private String applyUser;

    /** 申请人昵称译文（未命中 null） */
    private String applyUserName;

    /** 审批人 account */
    private String approver;

    /** 审批人昵称译文（未命中 null） */
    private String approverName;

    /** 详情跳转路径（未配/渲染失败 null） */
    private String detailPath;

    /** 流程实例 id（撤销后 null） */
    private String processInstanceId;

    /** 发起时间 */
    private LocalDateTime createTime;

    /** 最近状态变更时间（未变更过 null） */
    private LocalDateTime updateTime;
}
