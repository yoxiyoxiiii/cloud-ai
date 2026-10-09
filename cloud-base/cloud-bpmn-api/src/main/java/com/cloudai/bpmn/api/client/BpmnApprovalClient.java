package com.cloudai.bpmn.api.client;

import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.bpmn.api.fallback.BpmnApprovalClientFallbackFactory;
import com.cloudai.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * cloud-bpmn 审批平台 /inner 客户端（契约 2026-10-08-approval-platform-api §4：
 * create/status-list/cancel 三端点；网关屏蔽 /bpmn/inner/**，Feign 走注册发现直连内网）。
 * <p>统一声明归位 cloud-bpmn-api（2026-10-09）：消费方引 jar + @EnableFeignClients(clients=...) 显式接入；
 * 降级走 fallbackFactory（resilience4j，需消费方开 spring.cloud.openfeign.circuitbreaker.enabled=true），
 * 返回中性失败 R 由调用方既有 code!=SUCCESS 分支转译域码（system 侧 3022——等价迁移）。</p>
 */
@FeignClient(name = "cloud-bpmn", contextId = "systemBpmnApprovalClient", path = "/inner/approval",
        fallbackFactory = BpmnApprovalClientFallbackFactory.class)
public interface BpmnApprovalClient {

    /** 发起审批（uk 查重→insert→启动实例，bpmn 同事务）；返回审批单 id 与初始状态 */
    @PostMapping("/create")
    R<InnerApprovalCreateVo> create(@RequestBody ApprovalCreateInnerRequest req);

    /** 批量查状态（businessKey 全集回包，无审批单的键 null）；纠偏回源单批 ≤100 由调用方分批 */
    @PostMapping("/status-list")
    R<List<InnerApprovalStatusVo>> statusList(@RequestBody ApprovalStatusQueryInnerRequest req);

    /** 按业务键撤销审批（4010→4012→4011 校验序在 bpmn 侧） */
    @PostMapping("/cancel")
    R<Void> cancel(@RequestBody ApprovalCancelInnerRequest req);
}
