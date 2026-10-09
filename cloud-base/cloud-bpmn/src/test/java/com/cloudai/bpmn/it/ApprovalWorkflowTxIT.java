package com.cloudai.bpmn.it;

import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 同事务原子性实证（设计 D3 / 计划 B2：leave 平台化平移）：createApproval = insert bpmn_approval
 * + 启动流程实例 + 回填实例关联，三写必须同生共死。以 spy 在「回填」一步注入 RuntimeException，
 * 断言审批单行与引擎运行实例/历史实例均未落库（同一 DataSourceTransactionManager 回滚——
 * 不存在"审批表成功+引擎失败"孤儿单）。
 * 前置：cloud-system（9202，用户投影 /inner/user/all）在线供 Feign 审批人校验；本 IT 不依赖 9203。
 */
@Tag("it")
@SpringBootTest
class ApprovalWorkflowTxIT {

    @Autowired
    private ApprovalWorkflowService workflowService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private HistoryService historyService;

    @SpyBean
    private BpmnApprovalMapper approvalMapper;

    @Test
    void createApproval_rollsBackApprovalRowAndEngineInstanceTogether() {
        doThrow(new IllegalStateException("boom after engine start"))
                .when(approvalMapper).updateStatusById(anyLong(), anyInt(), any(), any(), any());

        assertThatThrownBy(() -> workflowService.createApproval(request()))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<BpmnApproval> captor = ArgumentCaptor.forClass(BpmnApproval.class);
        verify(approvalMapper).save(captor.capture());
        String businessKey = String.valueOf(captor.getValue().getId());

        // 审批表：同事务回滚后行不存在（spy save 已放行真实插入，证明确实写过）
        assertThat(approvalMapper.findById(Long.valueOf(businessKey))).isNull();
        // 引擎运行表：无该实例
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).singleResult()).isNull();
        // 引擎历史表：同事务一并回滚（无孤儿历史）
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
    }

    private ApprovalCreateInnerRequest request() {
        ApprovalCreateInnerRequest req = new ApprovalCreateInnerRequest();
        req.setBusinessType("leave");
        req.setBusinessKey("tx-it-" + System.currentTimeMillis());
        req.setTitle("tx atomicity it");
        req.setApplyUser("admin");
        req.setApprover("admin");
        return req;
    }
}
