package com.cloudai.bpmn.api.projection;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 审批投影定时对账 xxl-job 薄壳（2026-10-10 对账迁移轮：@Scheduled fixedDelay → admin CRON 调度，
 * 节奏治理权移交 admin 控制台，Nacos reconcile-interval-ms 键退役）。
 * 语义零变化：编排与吞异常全在 {@link ApprovalProjectionReconciler#reconcileActive()}——
 * admin 侧恒成功（失败观察走服务日志 log.error，与 @Scheduled 时代逐字等价；handleFail
 * 可见性增强记移交）。job 落 api jar、宿主 cloud-system（业务服务零代码，框架层承载）。
 * <p>bean 经 ApprovalProjectionAutoConfiguration 注册（同 enabled 门控）；
 * XxlJobSpringExecutor 收集容器全部 @XxlJob 方法（含自动装配 bean）。
 * admin 任务：id 7（执行器组 cloud-system）CRON 0 * * * * ? 每分钟一轮（脚本种子 B3；
 * plan 原文 0/60 在 xxl-job 3.5.0 秒域步进 &gt;=60 非法，等价替换记档）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ApprovalProjectionReconcileJobHandler {

    private final ApprovalProjectionReconciler reconciler;

    /** 单轮对账薄壳（零业务逻辑；XxlJobHelper.log 留 admin 执行日志痕） */
    @XxlJob("approvalProjectionReconcileJobHandler")
    public void approvalProjectionReconcileJobHandler() {
        XxlJobHelper.log("approval projection reconcile round start");
        reconciler.reconcileActive();
        XxlJobHelper.log("approval projection reconcile round end");
    }
}
