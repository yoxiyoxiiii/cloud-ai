package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.dto.LeaveCreateRequest;
import com.cloudai.bpmn.service.BpmnLeaveManageService;
import com.cloudai.bpmn.service.LeaveWorkflowService;
import com.cloudai.bpmn.vo.LeaveDetailVo;
import com.cloudai.bpmn.vo.LeaveVo;
import com.cloudai.bpmn.vo.UserOptionVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.util.SecurityUtils;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 请假单端点（契约 2026-10-07-bpmn-leave-api §2，网关前缀 /bpmn/leave）。
 * 申请人恒取当前登录人（X-User-Account 信任链），不接受传入。
 */
@RestController
@RequestMapping("/leave")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveWorkflowService workflowService;
    private final BpmnLeaveManageService manageService;

    /** 发起请假（同事务：建单审批中 + 启动流程实例；返回新请假单 id） */
    @PostMapping
    @PreAuthorize("hasAuthority('bpmn:leave:add')")
    public R<Long> add(@Valid @RequestBody LeaveCreateRequest req) {
        Long id = workflowService.saveLeave(req, SecurityUtils.currentAccount());
        return R.ok(id);
    }

    /** 我的申请分页（恒按当前登录人，id 倒序，含全部状态） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('bpmn:leave:list')")
    public R<PageResult<LeaveVo>> page(PageQuery query) {
        PageResult<LeaveVo> page = manageService.pageListMy(query, SecurityUtils.currentAccount());
        return R.ok(page);
    }

    /** 请假单详情（leave 主体 + steps 时间线三源拼装） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('bpmn:leave:list')")
    public R<LeaveDetailVo> detail(@PathVariable("id") Long id) {
        LeaveDetailVo detail = manageService.findById(id);
        return R.ok(detail);
    }

    /** 撤销请假（仅申请人本人且审批中；同事务删实例 + 置已撤销） */
    @PutMapping("/cancel/{id}")
    @PreAuthorize("hasAuthority('bpmn:leave:cancel')")
    public R<Void> cancel(@PathVariable("id") Long id) {
        workflowService.cancelLeave(id, SecurityUtils.currentAccount());
        return R.ok();
    }

    /** 审批人投影（发起弹窗下拉；system /inner/user/all 直通，含停用账号——宽松语义记档） */
    @GetMapping("/approvers")
    @PreAuthorize("hasAuthority('bpmn:leave:add')")
    public R<List<UserOptionVo>> approvers() {
        List<UserOptionVo> approvers = manageService.listApprovers();
        return R.ok(approvers);
    }
}
