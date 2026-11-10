package com.cloudai.system.job;

import com.cloudai.system.service.InnerSecretService;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * inner 面共享密钥轮换任务薄壳（xxl-job admin 任务 id 12、组 3 cloud-system，CRON 每分钟；
 * 契约 2026-11-09-inner-access-secret-api v2 §3）。编排与业务判定全在 InnerSecretService
 * （单一代码路径约束——手动端点与任务共用同一方法），handler 只做调度接线与失败留痕。
 */
@Slf4j
@Component
public class InnerSecretRotateJobHandler {

    private final InnerSecretService innerSecretService;

    public InnerSecretRotateJobHandler(InnerSecretService innerSecretService) {
        this.innerSecretService = innerSecretService;
    }

    /**
     * 每分钟轮次：清扫到期行 → 活跃集自愈重发 → 到提前预算的最新钥轮换 → 活跃集空告警。
     * 失败处置：service 内已按类型告警（日志 ERROR + admin 站内信，SETNX 去重 1 小时），
     * 此处记根因后重抛——xxl 侧留失败记录，下一分钟轮次幂等自愈。
     */
    @XxlJob("innerSecretRotateJobHandler")
    public void sweepAndRotate() {
        try {
            innerSecretService.sweepAndRotate();
        } catch (Exception e) {
            log.error("inner 密钥轮换任务执行失败（已告警，下一分钟轮次自愈重试）", e);
            throw e;
        }
    }
}
