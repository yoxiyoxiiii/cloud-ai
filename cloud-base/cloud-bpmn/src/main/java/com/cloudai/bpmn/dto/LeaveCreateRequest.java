package com.cloudai.bpmn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 发起请假入参（契约 2026-10-07-bpmn-leave-api §2.1：Bean Validation 全集，失败 1001）。
 * 申请人=当前登录人（X-User-Account，不接受传入）。
 */
@Data
public class LeaveCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 请假标题（e2e 前缀锚点） */
    @NotBlank
    @Size(max = 100)
    private String title;

    /** 请假类型（字典 bpmn_leave_type value："1"事假 "2"病假 "3"年假） */
    @NotBlank
    @Pattern(regexp = "^[123]$")
    private String leaveType;

    /** 开始日期 yyyy-MM-dd */
    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$")
    private String startDate;

    /** 结束日期 yyyy-MM-dd */
    @NotBlank
    @Pattern(regexp = "^\\d{4}-\\d{2}-\\d{2}$")
    private String endDate;

    /** 事由说明 */
    @Size(max = 500)
    private String reason;

    /** 审批人账号（须在用户投影内，否则 4004） */
    @NotBlank
    @Size(max = 30)
    private String approver;
}
