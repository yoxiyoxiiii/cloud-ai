package com.cloudai.system.api.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.DataPermDenyRequest;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import com.cloudai.system.api.fallback.DataPermClientFallbackFactory;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * cloud-system 数据权限求值客户端（契约 2026-10-10-dataperm-component-api §2：/inner/data-perm 两端点，
 * Feign 专用，网关已屏蔽 /system/inner/**）。组件化轮（设计 D18-D23）首个跨服务消费方 = cloud-bpmn。
 * <p>降级语义 fail-closed（D21）：system 不可用/熔断打开期 evaluate 返回 1002——消费方
 * {@code code != SUCCESS} 分支转 BusinessException 拒绝访问，任何分支不得在求值失败时返回数据
 * （数据权限降级放行=越权泄露，方向性错误）；deny 补痕降级仅记日志不阻断已发生的拒绝。</p>
 */
@FeignClient(name = "cloud-system", contextId = "dataPermClient", path = "/inner/data-perm",
        fallbackFactory = DataPermClientFallbackFactory.class)
public interface DataPermClient {

    /** 真实决策求值（provider 恒留痕 D7）：account 显式传入（D20，消费方从自己 SecurityContext 取） */
    @PostMapping("/evaluate")
    R<DataPermScopeVo> evaluate(@RequestBody DataPermEvaluateRequest request);

    /** deny 安全审计补痕（D13/D23）：详情被行级拒绝后消费方调用，不返回任何范围数据 */
    @PostMapping("/deny")
    R<Void> deny(@RequestBody DataPermDenyRequest request);
}
