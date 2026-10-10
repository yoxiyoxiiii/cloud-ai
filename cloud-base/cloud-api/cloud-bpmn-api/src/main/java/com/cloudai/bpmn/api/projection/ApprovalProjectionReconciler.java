package com.cloudai.bpmn.api.projection;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.bpmn.api.projection.ApprovalProjectionDao.ProjectionRow;
import com.cloudai.bpmn.api.projection.ApprovalProjectionDao.ReconcileDiff;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 投影定时对账 + 按需对账（契约 §1.4 / 设计 D7，Q3=B 纠偏上移框架层）：
 * 定时轮次拉投影非终态行（id 游标分批，首轮延迟一个间隔）→ Feign /inner/approval/status-list（同模块
 * client，降级体系复用）→ diff 回写投影表。撤销链路经 {@link #reconcileByBusiness} best-effort 即时对账。
 * <p>对账不建行（框架不知业务键全集——SUCCESS 事件极端丢失的建行兜底记移交全量对账模式）；
 * Feign 失败/降级 R 本轮放弃 log.error 不抛（对账不重试，事件路径独立收敛）；
 * 平台无单的键跳过（不可推进）。bean 经 ApprovalProjectionAutoConfiguration 注册（默认关）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ApprovalProjectionReconciler {

    /** 对账回写审计人：定时任务/按需对账无用户上下文，系统操作者记档 */
    static final String AUDIT_OPERATOR = "approval-reconcile";

    private final ApprovalProjectionDao projectionDao;
    private final BpmnApprovalClient approvalClient;
    private final ApprovalProjectionProperties properties;

    /** 单轮对账入口（xxl-job CRON 调度：薄壳 ApprovalProjectionReconcileJobHandler → 本方法；异常全吞语义不变——下轮再试） */
    public void reconcileActive() {
        try {
            int rounds = reconcileByCursor();
            if (rounds > 0) {
                log.info("定时对账完成: batches={}", rounds);
            }
        } catch (Exception e) {
            // 对账不抛不重试（设计 D7）：本轮放弃，事件路径独立收敛，下轮再试
            log.error("定时对账轮次失败，本轮放弃", e);
        }
    }

    /** 按需对账（撤销链路成功后即时收敛）：best-effort——失败不抛不阻塞，TERMINAL 事务半消息兜底 */
    public void reconcileByBusiness(String businessType, List<String> businessKeys) {
        if (businessKeys == null || businessKeys.isEmpty()) {
            return;
        }
        try {
            for (int from = 0; from < businessKeys.size(); from += properties.getReconcileBatchSize()) {
                List<String> batch = businessKeys.subList(from,
                        Math.min(from + properties.getReconcileBatchSize(), businessKeys.size()));
                List<ProjectionRow> rows = projectionDao.findByBusiness(businessType, batch);
                reconcileRows(rows);
            }
        } catch (Exception e) {
            log.error("按需对账失败（best-effort，事件兜底收敛）: businessType={}, keys={}",
                    businessType, businessKeys.size(), e);
        }
    }

    /** 游标分批推进（cursor 与行是否改写无关，天然终止；每批一次 Feign ≤100） */
    private int reconcileByCursor() {
        int batches = 0;
        long cursor = 0L;
        while (true) {
            List<ProjectionRow> batch = projectionDao.listActive(cursor, properties.getReconcileBatchSize());
            if (batch.isEmpty()) {
                break;
            }
            reconcileRows(batch);
            batches++;
            cursor = batch.get(batch.size() - 1).id();
            if (batch.size() < properties.getReconcileBatchSize()) {
                break;
            }
        }
        return batches;
    }

    /** 一批行的对账：按 businessType 分组逐组拉真相 → diff 回写 */
    private void reconcileRows(List<ProjectionRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Map<String, List<ProjectionRow>> byType = rows.stream()
                .collect(Collectors.groupingBy(ProjectionRow::businessType));
        for (Map.Entry<String, List<ProjectionRow>> group : byType.entrySet()) {
            List<String> keys = group.getValue().stream().map(ProjectionRow::businessKey).toList();
            Map<String, InnerApprovalStatusVo> truth = fetchPlatformStatus(group.getKey(), keys);
            LocalDateTime now = LocalDateTime.now();
            for (ProjectionRow row : group.getValue()) {
                applyTruth(row, truth.get(row.businessKey()), now);
            }
        }
    }

    /** 单批 Feign 回源（失败抛出由调用方口径处置：定时=本轮放弃 / 按需=best-effort 吞） */
    private Map<String, InnerApprovalStatusVo> fetchPlatformStatus(String businessType, List<String> keys) {
        ApprovalStatusQueryInnerRequest req = new ApprovalStatusQueryInnerRequest();
        req.setBusinessType(businessType);
        req.setBusinessKeys(keys);
        R<List<InnerApprovalStatusVo>> response;
        try {
            response = approvalClient.statusList(req);
        } catch (Exception e) {
            throw new IllegalStateException("对账状态查询 Feign 调用失败: keys=" + keys.size(), e);
        }
        if (response == null || response.getCode() != ErrorCode.SUCCESS.getCode() || response.getData() == null) {
            throw new IllegalStateException("对账状态查询平台返回失败: code="
                    + (response == null ? null : response.getCode()));
        }
        Map<String, InnerApprovalStatusVo> byKey = new HashMap<>();
        for (InnerApprovalStatusVo vo : response.getData()) {
            byKey.put(vo.getBusinessKey(), vo);
        }
        return byKey;
    }

    /** 真相回写：平台无单跳过（不建行不推进）；有单则 diff（SQL 条件写幂等，已收敛零更新） */
    private void applyTruth(ProjectionRow row, InnerApprovalStatusVo truth, LocalDateTime now) {
        if (truth == null || truth.getStatus() == null || truth.getApprovalId() == null) {
            return;
        }
        int platformStatus;
        try {
            platformStatus = Integer.parseInt(truth.getStatus());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("平台状态值非法: " + truth.getStatus(), e);
        }
        Long approvalId = Long.valueOf(truth.getApprovalId());
        projectionDao.applyReconcile(new ReconcileDiff(row.businessType(), row.businessKey(),
                platformStatus, approvalId, truth.getProcessInstanceId()), AUDIT_OPERATOR, now);
    }
}
