package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.service.ApprovalQueryService;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.bpmn.vo.ApprovalDetailVo;
import com.cloudai.bpmn.vo.ApprovalDiagramVo;
import com.cloudai.bpmn.vo.ApprovalVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.util.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台审批单端点（契约 2026-10-08-approval-platform-api §3，网关前缀 /bpmn/approval）。
 * 分页恒按当前登录人 applyUser；撤销校验序 4010→4012→4011（Service 层）。
 */
@RestController
@RequestMapping("/approval")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalQueryService queryService;
    private final ApprovalWorkflowService workflowService;

    /** 我的审批分页（恒当前登录人，id 倒序，含全部状态与业务类型） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('bpmn:approval:list')")
    public R<PageResult<ApprovalVo>> page(PageQuery query) {
        PageResult<ApprovalVo> page = queryService.pageListMy(query, SecurityUtils.currentAccount());
        return R.ok(page);
    }

    /** 审批单详情（approval 主体 + steps 时间线，apply/approval/end 时间升序） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('bpmn:approval:list')")
    public R<ApprovalDetailVo> detail(@PathVariable("id") Long id) {
        ApprovalDetailVo detail = queryService.findById(id);
        return R.ok(detail);
    }

    /** 撤销审批（仅申请人本人且审批中；同事务删流程实例 + 置已撤销） */
    @PutMapping("/cancel/{id}")
    @PreAuthorize("hasAuthority('bpmn:approval:cancel')")
    public R<Void> cancel(@PathVariable("id") Long id) {
        workflowService.cancelApproval(id, SecurityUtils.currentAccount());
        return R.ok();
    }

    /** 审批单图数据（businessKey=approvalId 历史锚点三态；历史缺失防御 definitionId=null 空集合） */
    @GetMapping("/{id}/diagram")
    @PreAuthorize("hasAuthority('bpmn:approval:list')")
    public R<ApprovalDiagramVo> diagram(@PathVariable("id") Long id) {
        ApprovalDiagramVo diagram = queryService.findDiagram(id);
        return R.ok(diagram);
    }
}
