package com.cloudai.bpmn.api.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 发起审批入参（契约 2026-10-08-approval-platform-api §4.1：/inner/approval/create，
 * Feign 专用无认证头，applyUser/operator 由调用方显式传入——沿 inner-api 先例）。
 * （cloud-bpmn-api 归位 2026-10-09）
 */
@Data
public class ApprovalCreateInnerRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型编码（须在配置表，否则 4014） */
    @NotBlank
    @Size(max = 50)
    private String businessType;

    /** 业务单据标识（业务方主键字符串化） */
    @NotBlank
    @Size(max = 64)
    private String businessKey;

    /** 单据标题快照（待办/列表渲染，发起时定格） */
    @NotBlank
    @Size(max = 100)
    private String title;

    /** 申请人账号（调用方传入，非登录上下文） */
    @NotBlank
    @Size(max = 30)
    private String applyUser;

    /** 审批人账号（须在用户投影内，否则 4013） */
    @NotBlank
    @Size(max = 30)
    private String approver;

    /** 调用方流程变量透传（≤10 项；与平台四变量同名时以平台注入为准） */
    @Valid
    @Size(max = 10)
    private List<VariableItem> variables;
}
