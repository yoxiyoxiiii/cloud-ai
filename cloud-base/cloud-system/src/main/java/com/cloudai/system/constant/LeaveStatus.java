package com.cloudai.system.constant;

/**
 * 请假对外状态常量（契约 2026-10-09-approval-projection-api §1.5 派生规则；投影轮 D6）：
 * sys_leave 状态列退役后终态判定迁此（VO String 域，沿用禁魔法值精神）。
 * 值域与字典 bpmn_approval_status 一致；"4" 发起失败仅由 create_result=2 派生（投影列永不落 4）。
 */
public final class LeaveStatus {

    /** 审批中（无投影行/未达事件 → IFNULL(approval_status,0)） */
    public static final String APPROVING = "0";

    /** 已通过（TERMINAL/对账推进） */
    public static final String APPROVED = "1";

    /** 已拒绝（TERMINAL/对账推进） */
    public static final String REJECTED = "2";

    /** 已撤销（TERMINAL/对账推进） */
    public static final String CANCELLED = "3";

    /** 发起失败（create_result=2 派生，仅 system 读侧可见；可重新发起新单，原单不再变更） */
    public static final String FAILED = "4";

    private LeaveStatus() {
    }

    /** 终态判定：非审批中即终态（1/2/3/4 不可再变更） */
    public static boolean isTerminal(String status) {
        return status != null && !APPROVING.equals(status);
    }
}
