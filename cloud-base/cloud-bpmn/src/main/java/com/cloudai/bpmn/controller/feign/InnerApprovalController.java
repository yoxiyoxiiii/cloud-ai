package com.cloudai.bpmn.controller.feign;

import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.common.core.domain.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台审批 /inner 面（契约 2026-10-08-approval-platform-api §4，服务间 Feign 专用）。
 * 无 @PreAuthorize：/inner/** 不经网关鉴权链（网关 inner-block-bpmn 路由屏蔽外访，内网+屏蔽为边界）；
 * 入参 applyUser/operator 显式传（无登录态上下文）。
 */
@RestController
@RequestMapping("/inner/approval")
@RequiredArgsConstructor
public class InnerApprovalController {

    private final ApprovalWorkflowService workflowService;

    /** 发起审批（查重→insert→启动实例→回填，同事务）；返回审批单 id 与初始状态 */
    @PostMapping("/create")
    public R<InnerApprovalCreateVo> create(@Valid @RequestBody ApprovalCreateInnerRequest req) {
        InnerApprovalCreateVo created = workflowService.createApproval(req);
        return R.ok(created);
    }

    /** 批量查状态：businessKey 全集回包，无审批单的键 approvalId/status 均 null */
    @PostMapping("/status-list")
    public R<List<InnerApprovalStatusVo>> statusList(@Valid @RequestBody ApprovalStatusQueryInnerRequest req) {
        List<InnerApprovalStatusVo> statusList = workflowService.listStatusByBusiness(req);
        return R.ok(statusList);
    }

    /** 按业务键撤销审批（4010→4012→4011；同事务删实例+置已撤销） */
    @PostMapping("/cancel")
    public R<Void> cancel(@Valid @RequestBody ApprovalCancelInnerRequest req) {
        workflowService.cancelByBusiness(req);
        return R.ok();
    }
}
