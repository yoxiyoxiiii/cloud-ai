package com.cloudai.bpmn.api.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 批量状态回包行（契约 2026-10-08-approval-platform-api §4.2：businessKey 全集回包，
 * 无审批单的键 approvalId/status 均 null；2026-10-09 投影轮 §4.2 增 processInstanceId——additive，
 * 消费方变更为 api 模块对账组件，端点行为不变）。
 * （cloud-bpmn-api 归位 2026-10-09）
 */
@Data
public class InnerApprovalStatusVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务单据标识（回显请求键） */
    private String businessKey;

    /** 审批单 id（无审批单 null） */
    private String approvalId;

    /** 流程实例 ID（无审批单/撤销后 null）——投影 pid 列对账回填依据 */
    private String processInstanceId;

    /** 当前状态（无审批单 null） */
    private String status;
}
