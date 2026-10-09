package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 发起审批入参（契约 2026-10-08-approval-platform-api §4.1 镜像：bpmn 侧另有 Bean Validation 全集，
 * 本镜像仅服务内构造经 Feign 序列化——不作为 Controller 入参，校验责任在构造方）。
 */
@Data
public class ApprovalCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型编码（sys_leave 恒 "leave"，配置表种子） */
    private String businessType;

    /** 业务单据标识（请假单 id 字符串化） */
    private String businessKey;

    /** 单据标题快照 */
    private String title;

    /** 申请人账号（当前登录人） */
    private String applyUser;

    /** 审批人账号（须在用户投影内，否则平台 4013） */
    private String approver;
}
