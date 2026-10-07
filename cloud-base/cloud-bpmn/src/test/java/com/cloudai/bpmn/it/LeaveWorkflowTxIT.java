package com.cloudai.bpmn.it;

import com.cloudai.bpmn.dto.LeaveCreateRequest;
import com.cloudai.bpmn.entity.BpmnLeave;
import com.cloudai.bpmn.mapper.BpmnLeaveMapper;
import com.cloudai.bpmn.service.LeaveWorkflowService;
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
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

/**
 * 同事务原子性实证（设计 D3 / 计划 B4）：saveLeave = insert 业务行 + 启动流程实例 + 回填关联，
 * 三写必须同生共死。以 spy 在「回填」一步注入 RuntimeException，断言业务行与引擎运行实例/历史实例
 * 均未落库（同一 DataSourceTransactionManager 回滚——不存在"业务表成功+引擎失败"孤儿单）。
 * 前置：cloud-system（9202，用户管理）在线供 Feign 审批人校验；本 IT 不依赖 9203。
 */
@Tag("it")
@SpringBootTest
class LeaveWorkflowTxIT {

    @Autowired
    private LeaveWorkflowService workflowService;
    @Autowired
    private RuntimeService runtimeService;
    @Autowired
    private HistoryService historyService;

    @SpyBean
    private BpmnLeaveMapper leaveMapper;

    @Test
    void saveLeave_rollsBackBusinessRowAndEngineInstanceTogether() {
        doThrow(new IllegalStateException("boom after engine start"))
                .when(leaveMapper).updateStatusById(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> workflowService.saveLeave(request(), "admin"))
                .isInstanceOf(IllegalStateException.class);

        ArgumentCaptor<BpmnLeave> captor = ArgumentCaptor.forClass(BpmnLeave.class);
        verify(leaveMapper).save(captor.capture());
        String businessKey = String.valueOf(captor.getValue().getId());

        // 业务表：同事务回滚后行不存在（spy save 已放行真实插入，证明确实写过）
        assertThat(leaveMapper.findById(Long.valueOf(businessKey))).isNull();
        // 引擎运行表：无该实例
        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).singleResult()).isNull();
        // 引擎历史表：同事务一并回滚（无孤儿历史）
        assertThat(historyService.createHistoricProcessInstanceQuery()
                .processInstanceBusinessKey(businessKey).count()).isZero();
    }

    private LeaveCreateRequest request() {
        LeaveCreateRequest req = new LeaveCreateRequest();
        req.setTitle("tx atomicity it");
        req.setLeaveType("3");
        req.setStartDate("2026-10-08");
        req.setEndDate("2026-10-09");
        req.setReason("same tx rollback proof");
        req.setApprover("admin");
        return req;
    }
}
