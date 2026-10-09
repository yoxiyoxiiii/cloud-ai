package com.cloudai.system.api.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.LoginUserDTO;
import com.cloudai.system.api.domain.UserEntry;
import com.cloudai.system.api.fallback.SystemUserClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * cloud-system 内部用户客户端（契约 2026-10-07-inner-api §2.1/§2.2：/inner/user/{account} + /inner/user/all）。
 * <p>统一声明归位 cloud-system-api（2026-10-09）：sso（登录/refresh）与 bpmn（审批人投影）共用本声明；
 * 降级走 fallbackFactory——返回值按消费方既有终态定制（设计 D4 等价表：2002/1002），
 * 调用方既有 code!=SUCCESS 分支零改动即端到端等价。</p>
 */
@FeignClient(name = "cloud-system", contextId = "systemUserClient", path = "/inner/user",
        fallbackFactory = SystemUserClientFallbackFactory.class)
public interface SystemUserClient {

    /** 按账号取登录聚合（账号不存在 data=null，登录失败语义由 sso 判定） */
    @GetMapping("/{account}")
    R<LoginUserDTO> getUserByAccount(@PathVariable("account") String account);

    /** 全量用户投影（含停用——翻译/审批人存在性校验宽松口径，契约 §2.2） */
    @GetMapping("/all")
    R<List<UserEntry>> listAll();
}
