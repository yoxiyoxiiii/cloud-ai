package com.cloudai.bpmn.api.projection;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.bpmn.api.projection.ApprovalProjectionDao.ProjectionRow;
import com.cloudai.bpmn.api.projection.ApprovalProjectionDao.ReconcileDiff;
import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账组件单测（契约 2026-10-09-approval-projection-api §1.4 / 设计 D7）：
 * 定时轮次游标分批推进（终态即停）/ diff 回写（平台无单跳过）/ Feign 失败本轮放弃不抛 /
 * 按需对账批切分（201 键→3 批）+ best-effort 吞异常。
 */
@ExtendWith(MockitoExtension.class)
class ApprovalProjectionReconcilerTest {

    @Mock
    private ApprovalProjectionDao projectionDao;
    @Mock
    private BpmnApprovalClient approvalClient;

    private ApprovalProjectionProperties properties;

    private ApprovalProjectionReconciler reconciler;

    @BeforeEach
    void setUp() {
        properties = new ApprovalProjectionProperties();
        properties.setConsumerGroup("g_system_approval_event");
        properties.setReconcileBatchSize(100);
        reconciler = new ApprovalProjectionReconciler(projectionDao, approvalClient, properties);
    }

    // ---- 定时轮次：游标分批 + diff 回写 ----

    @Test
    void reconcileActive_cursorAdvancesUntilShortBatch() {
        // 批1（满 2/上限2）→ 批2（1 行短批终止）：两次拉取、两组 Feign、diff 逐行回写
        properties.setReconcileBatchSize(2);
        when(projectionDao.listActive(0L, 2)).thenReturn(List.of(row(1L, "101", 0), row(2L, "102", 0)));
        when(projectionDao.listActive(2L, 2)).thenReturn(List.of(row(3L, "103", 0)));
        when(approvalClient.statusList(any())).thenAnswer(inv -> {
            ApprovalStatusQueryInnerRequest req = inv.getArgument(0);
            return R.ok(req.getBusinessKeys().stream().map(k -> statusVo(k, "12", "1", "pid-" + k)).toList());
        });

        reconciler.reconcileActive();

        ArgumentCaptor<ApprovalStatusQueryInnerRequest> captor =
                ArgumentCaptor.forClass(ApprovalStatusQueryInnerRequest.class);
        verify(approvalClient, times(2)).statusList(captor.capture());
        assertThat(captor.getAllValues()).extracting(ApprovalStatusQueryInnerRequest::getBusinessKeys)
                .containsExactly(List.of("101", "102"), List.of("103"));
        verify(projectionDao, times(3)).applyReconcile(any(ReconcileDiff.class), anyString(), any());
    }

    @Test
    void reconcileActive_diffCarriesPlatformTruth() {
        when(projectionDao.listActive(0L, 100)).thenReturn(List.of(row(1L, "101", 0)));
        when(approvalClient.statusList(any()))
                .thenReturn(R.ok(List.of(statusVo("101", "88", "2", "pid-101"))));

        reconciler.reconcileActive();

        ArgumentCaptor<ReconcileDiff> diff = ArgumentCaptor.forClass(ReconcileDiff.class);
        verify(projectionDao).applyReconcile(diff.capture(), eq("approval-reconcile"), any());
        assertThat(diff.getValue().businessType()).isEqualTo("leave");
        assertThat(diff.getValue().businessKey()).isEqualTo("101");
        assertThat(diff.getValue().approvalStatus()).isEqualTo(2);
        assertThat(diff.getValue().approvalId()).isEqualTo(88L);
        assertThat(diff.getValue().processInstanceId()).isEqualTo("pid-101");
    }

    @Test
    void reconcileActive_platformMissingKeySkipped_noWrite() {
        // 平台无单的键（status=null）不推进不建行——对账只写投影已有行
        when(projectionDao.listActive(0L, 100)).thenReturn(List.of(row(1L, "101", 0)));
        when(approvalClient.statusList(any()))
                .thenReturn(R.ok(List.of(statusVo("101", null, null, null))));

        reconciler.reconcileActive();

        verify(projectionDao, never()).applyReconcile(any(), anyString(), any());
    }

    @Test
    void reconcileActive_feignFailureSwallowed_roundAbandoned() {
        // 对账不抛不重试（设计 D7）：Feign 异常 → 本轮放弃，下轮再试
        when(projectionDao.listActive(anyLong(), anyInt())).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> reconciler.reconcileActive()).doesNotThrowAnyException();
        verify(approvalClient, never()).statusList(any());
    }

    @Test
    void reconcileActive_degradedResponseSwallowed() {
        // 降级 R（code=1002，resilience4j fallback）同系统态：本轮放弃不抛
        when(projectionDao.listActive(anyLong(), anyInt())).thenReturn(List.of(row(1L, "101", 0)));
        when(approvalClient.statusList(any())).thenReturn(R.fail(1002, "cloud-bpmn 服务不可用"));

        assertThatCode(() -> reconciler.reconcileActive()).doesNotThrowAnyException();
        verify(projectionDao, never()).applyReconcile(any(), anyString(), any());
    }

    // ---- 按需对账（撤销链路）：批切分 + best-effort ----

    @Test
    void reconcileByBusiness_splitsKeysIntoBatches() {
        // 201 键 → 3 批（100+100+1），每批一次 findByBusiness（契约 §4.2 单批 ≤100）；
        // 行集空 → reconcileRows 短路不触 Feign（切分断言不依赖回源）
        when(projectionDao.findByBusiness(anyString(), anyList())).thenReturn(List.of());

        List<String> keys = java.util.stream.LongStream.rangeClosed(1, 201)
                .mapToObj(String::valueOf).toList();
        reconciler.reconcileByBusiness("leave", keys);

        ArgumentCaptor<List<String>> batch = ArgumentCaptor.forClass(List.class);
        verify(projectionDao, times(3)).findByBusiness(eq("leave"), batch.capture());
        assertThat(batch.getAllValues()).extracting(List::size).containsExactly(100, 100, 1);
    }

    @Test
    void reconcileByBusiness_feignFailureBestEffort_noThrow() {
        when(projectionDao.findByBusiness(anyString(), anyList()))
                .thenReturn(List.of(row(1L, "101", 0)));
        when(approvalClient.statusList(any())).thenThrow(new RuntimeException("connection refused"));

        assertThatCode(() -> reconciler.reconcileByBusiness("leave", List.of("101")))
                .doesNotThrowAnyException();
    }

    @Test
    void reconcileByBusiness_emptyKeysNoop() {
        reconciler.reconcileByBusiness("leave", List.of());

        verifyNoInteractionsAll();
    }

    // ---- 脚手架 ----

    private void verifyNoInteractionsAll() {
        verify(projectionDao, never()).findByBusiness(anyString(), anyList());
        verify(approvalClient, never()).statusList(any());
    }

    private ProjectionRow row(long id, String businessKey, int approvalStatus) {
        return new ProjectionRow(id, "leave", businessKey, null, 0, approvalStatus);
    }

    private InnerApprovalStatusVo statusVo(String businessKey, String approvalId, String status, String pid) {
        InnerApprovalStatusVo vo = new InnerApprovalStatusVo();
        vo.setBusinessKey(businessKey);
        vo.setApprovalId(approvalId);
        vo.setStatus(status);
        vo.setProcessInstanceId(pid);
        return vo;
    }
}
