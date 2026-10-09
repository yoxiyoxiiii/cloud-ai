package com.cloudai.system.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.ApprovalCancelRequest;
import com.cloudai.system.dto.ApprovalCreateRequest;
import com.cloudai.system.dto.ApprovalStatusQueryRequest;
import com.cloudai.system.vo.ApprovalCreateVo;
import com.cloudai.system.vo.ApprovalStatusVo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * cloud-bpmn 审批平台 /inner 客户端（契约 2026-10-08-approval-platform-api §4：
 * create/status-list/cancel 三端点；网关屏蔽 /bpmn/inner/**，Feign 走注册发现直连内网）。
 * <p>无 fallback：调用方 Service catch 异常 log.error 后转 3022/降级快照（sso/bpmn 同款已知取舍）；
 * 镜像 DTO/VO 与 bpmn 侧字段逐字对齐（服务间契约，字段演进两侧同步）。</p>
 */
@FeignClient(name = "cloud-bpmn", contextId = "systemBpmnApprovalClient", path = "/inner/approval")
public interface BpmnApprovalClient {

    /** 发起审批（uk 查重→insert→启动实例，bpmn 同事务）；返回审批单 id 与初始状态 */
    @PostMapping("/create")
    R<ApprovalCreateVo> create(@RequestBody ApprovalCreateRequest req);

    /** 批量查状态（businessKey 全集回包，无审批单的键 null）；纠偏回源单批 ≤100 由调用方分批 */
    @PostMapping("/status-list")
    R<List<ApprovalStatusVo>> statusList(@RequestBody ApprovalStatusQueryRequest req);

    /** 按业务键撤销审批（4010→4012→4011 校验序在 bpmn 侧） */
    @PostMapping("/cancel")
    R<Void> cancel(@RequestBody ApprovalCancelRequest req);
}
