package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.LeaveCreateRequest;
import com.cloudai.system.service.LeaveManageService;
import com.cloudai.system.service.LeaveWorkflowService;
import com.cloudai.system.vo.SysLeaveDetailVo;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.system.vo.UserOptionVo;
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
 * 请假端点（契约 2026-10-08-approval-platform-api §5，网关前缀 /system/leave——
 * v1 bpmn 域六端点迁移版，perms 换 system 域）。分页/撤销恒按当前登录人。
 */
@RestController
@RequestMapping("/leave")
@RequiredArgsConstructor
public class LeaveController {

    private final LeaveManageService manageService;
    private final LeaveWorkflowService workflowService;

    /** 发起请假（本地事务 + Feign 发起审批；返回新请假单 ID） */
    @PostMapping
    @PreAuthorize("hasAuthority('system:leave:add')")
    public R<Long> add(@Valid @RequestBody LeaveCreateRequest req) {
        Long id = workflowService.saveLeave(req, SecurityUtils.currentAccount());
        return R.ok(id);
    }

    /** 我的请假分页（id 倒序；状态读时纠偏，Feign 失败降级快照） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:leave:list')")
    public R<PageResult<SysLeaveVo>> page(PageQuery query) {
        PageResult<SysLeaveVo> page = manageService.pageListMy(query, SecurityUtils.currentAccount());
        return R.ok(page);
    }

    /** 请假单详情（单行纠偏；不含时间线/图——前端按 approvalId 另调平台端点） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:leave:list')")
    public R<SysLeaveDetailVo> detail(@PathVariable("id") Long id) {
        SysLeaveDetailVo detail = manageService.findById(id, SecurityUtils.currentAccount());
        return R.ok(detail);
    }

    /** 撤销请假（3018→3021→3020 本地校验 → 平台撤销 → 本地置已撤销；Feign 失败本地不动） */
    @PutMapping("/cancel/{id}")
    @PreAuthorize("hasAuthority('system:leave:cancel')")
    public R<Void> cancel(@PathVariable("id") Long id) {
        workflowService.cancelLeave(id, SecurityUtils.currentAccount());
        return R.ok();
    }

    /** 审批人投影（仅启用账号；发起弹窗选人） */
    @GetMapping("/approvers")
    @PreAuthorize("hasAuthority('system:leave:add')")
    public R<List<UserOptionVo>> approvers() {
        List<UserOptionVo> approvers = manageService.listApprovers();
        return R.ok(approvers);
    }
}
