package com.cloudai.bpmn.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 请假单详情 VO（契约 2026-10-07-bpmn-leave-api §2.3：leave 主体 + steps 时间线）。
 * 注意：嵌套 VO 不在 TranslateAdvisor 容器下钻范围（候选①挂账）——
 * 译文字段由 BpmnLeaveManageService 经 TranslationCacheService 手动回填（未命中 null 同降级语义）。
 */
@Data
public class LeaveDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请假单主体（列表行同构） */
    private LeaveVo leave;

    /** 审批时间线（apply/approval/end，按时间升序） */
    private List<ApprovalStepVo> steps;
}
