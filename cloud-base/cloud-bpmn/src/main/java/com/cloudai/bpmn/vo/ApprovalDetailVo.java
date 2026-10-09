package com.cloudai.bpmn.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 审批单详情 VO（契约 2026-10-08-approval-platform-api §3.2：approval 主体 + steps 时间线，
 * steps 按时间升序，ApprovalStepVo 字段与 v1 §2.3 逐字相同）。
 * 注意：嵌套 VO 不在 TranslateAdvisor 容器下钻范围（候选①挂账）——
 * 译文字段由 ApprovalQueryService 经 TranslationCacheService 手动回填（未命中 null 同降级语义）。
 */
@Data
public class ApprovalDetailVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 审批单主体（列表行同构） */
    private ApprovalVo approval;

    /** 审批时间线（apply/approval/end，按时间升序） */
    private List<ApprovalStepVo> steps;
}
