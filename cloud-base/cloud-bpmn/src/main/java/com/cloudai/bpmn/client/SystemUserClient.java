package com.cloudai.bpmn.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.UserEntry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * cloud-system 内部用户接口客户端（契约 2026-10-07-bpmn-leave-api §8：inner-api §2.2 端点零变化的第二消费者）。
 * <p>无 fallback：调用方 Service catch 异常 log.error 后转 1002（不引 circuitbreaker，sso 已知取舍同款）。
 * 与 translate-remote-starter 的程序式 SystemTranslateClient 职责分离（本 client 服务审批人校验与选人投影）。</p>
 */
@FeignClient(name = "cloud-system", contextId = "bpmnSystemUserClient", path = "/inner/user")
public interface SystemUserClient {

    /** 全量用户投影（含停用账号——UserEntry 无状态字段，宽松语义记档：发起侧仅校验存在性 4004） */
    @GetMapping("/all")
    R<List<UserEntry>> listAll();
}
