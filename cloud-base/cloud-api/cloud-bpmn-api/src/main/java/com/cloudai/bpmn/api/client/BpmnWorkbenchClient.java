package com.cloudai.bpmn.api.client;

import com.cloudai.bpmn.api.domain.ApprovalVo;
import com.cloudai.bpmn.api.domain.TaskVo;
import com.cloudai.bpmn.api.fallback.BpmnWorkbenchClientFallbackFactory;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * cloud-bpmn 工作台查询客户端（契约 2026-11-14-aggregate-workbench-api §7：复用下游外部用户面
 * GET /task/todo 与 GET /approval/page，非 /inner 面——消费方为聚合服务 cloud-aggregate，
 * 调用时由其用户头转发拦截器携带网关注入的 X-User-* 五头，下游 @PreAuthorize 照常把关）。
 * 出参为镜像模型（domain/TaskVo、domain/ApprovalVo），字段冻结随契约 §3 同步。
 */
@FeignClient(name = "cloud-bpmn", contextId = "bpmnWorkbenchClient",
        fallbackFactory = BpmnWorkbenchClientFallbackFactory.class)
public interface BpmnWorkbenchClient {

    /** 我的待办分页（下游 perms bpmn:task:list；聚合场景固定 pageNum=1&pageSize=5） */
    @GetMapping("/task/todo")
    R<PageResult<TaskVo>> todo(@RequestParam("pageNum") Integer pageNum,
                               @RequestParam("pageSize") Integer pageSize);

    /** 我的审批分页（下游 perms bpmn:approval:list；数据权限与 title 列级在下游求值） */
    @GetMapping("/approval/page")
    R<PageResult<ApprovalVo>> page(@RequestParam("pageNum") Integer pageNum,
                                   @RequestParam("pageSize") Integer pageSize);
}
