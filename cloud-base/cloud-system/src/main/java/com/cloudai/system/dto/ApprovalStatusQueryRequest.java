package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 批量查审批状态入参（契约 2026-10-08-approval-platform-api §4.2 镜像：
 * businessKeys ≤100/项——分批责任在本服务纠偏侧，防整页撞平台 1001）。
 */
@Data
public class ApprovalStatusQueryRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务类型编码（sys_leave 恒 "leave"） */
    private String businessType;

    /** 业务单据标识集合（单批 ≤100，由调用方分批保证） */
    private List<String> businessKeys;
}
