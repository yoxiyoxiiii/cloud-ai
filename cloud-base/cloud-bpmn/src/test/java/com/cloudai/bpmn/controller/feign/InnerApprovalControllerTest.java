package com.cloudai.bpmn.controller.feign;

import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.service.ApprovalWorkflowService;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * /inner 审批端点委托单测（契约 2026-10-08-approval-platform-api §4）：
 * Feign 专用通道无 @PreAuthorize（网关 inner-block-bpmn 屏蔽外访），三端点纯委托编排服务。
 */
@ExtendWith(MockitoExtension.class)
class InnerApprovalControllerTest {

    @Mock
    private ApprovalWorkflowService workflowService;
    @InjectMocks
    private InnerApprovalController controller;

    @Test
    void create_delegatesToWorkflow() {
        ApprovalCreateInnerRequest req = new ApprovalCreateInnerRequest();
        InnerApprovalCreateVo vo = new InnerApprovalCreateVo();
        vo.setApprovalId("12");
        vo.setStatus("0");
        when(workflowService.createApproval(req)).thenReturn(vo);

        R<InnerApprovalCreateVo> result = controller.create(req);

        verify(workflowService).createApproval(req);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData().getApprovalId()).isEqualTo("12");
        assertThat(result.getData().getStatus()).isEqualTo("0");
    }

    @Test
    void statusList_delegatesToWorkflow() {
        ApprovalStatusQueryInnerRequest req = new ApprovalStatusQueryInnerRequest();
        req.setBusinessType("leave");
        req.setBusinessKeys(List.of("101"));
        InnerApprovalStatusVo vo = new InnerApprovalStatusVo();
        vo.setBusinessKey("101");
        vo.setApprovalId("12");
        vo.setStatus("1");
        when(workflowService.listStatusByBusiness(req)).thenReturn(List.of(vo));

        R<List<InnerApprovalStatusVo>> result = controller.statusList(req);

        verify(workflowService).listStatusByBusiness(req);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).hasSize(1);
        assertThat(result.getData().get(0).getStatus()).isEqualTo("1");
    }

    @Test
    void cancel_delegatesToWorkflow() {
        ApprovalCancelInnerRequest req = new ApprovalCancelInnerRequest();
        req.setBusinessType("leave");
        req.setBusinessKey("7");
        req.setOperator("userA");

        R<Void> result = controller.cancel(req);

        verify(workflowService).cancelByBusiness(req);
        assertThat(result.getCode()).isEqualTo(200);
    }
}
