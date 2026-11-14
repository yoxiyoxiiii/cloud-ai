package com.cloudai.aggregate.controller;

import com.cloudai.aggregate.service.WorkbenchService;
import com.cloudai.aggregate.vo.WorkbenchVo;
import com.cloudai.common.core.domain.R;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工作台聚合端点（契约 2026-11-14-aggregate-workbench-api §1：登录即可无 perms，
 * 权限把关在下游段——段准入预判 + 下游 @PreAuthorize 双层）。
 */
@RestController
@RequiredArgsConstructor
public class WorkbenchController {

    private final WorkbenchService workbenchService;

    /** 工作台三段聚合：我的待办 top5 + 我的审批 top5 + 未读通知摘要（段式失败状态） */
    @GetMapping("/workbench")
    public R<WorkbenchVo> workbench() {
        WorkbenchVo workbench = workbenchService.workbench();
        return R.ok(workbench);
    }
}
