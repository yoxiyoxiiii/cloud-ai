package com.cloudai.bpmn.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量查审批状态入参（契约 2026-10-08-approval-platform-api §4.2：/inner/approval/status-list，
 * businessKey 全集回包，无审批单的键 status=null——查询语义宽松，businessType 不存在不报 4014）。
 */
@Data
public class ApprovalStatusQueryInnerRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型编码 */
    @NotBlank
    @Size(max = 50)
    private String businessType;

    /** 业务单据标识集合（≤100 项；分批责任在调用方，防整页撞 1001） */
    @NotEmpty
    @Size(max = 100)
    private List<@NotBlank @Size(max = 64) String> businessKeys;
}
