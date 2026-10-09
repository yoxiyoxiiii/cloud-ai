package com.cloudai.system.service;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.bpmn.api.projection.ApprovalProjectionReconciler;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.LeaveCreateRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mq.LeaveCreateTxExecutor;
import com.cloudai.common.rocketmq.tx.TxMessageSendException;
import com.cloudai.common.rocketmq.tx.TxMessageSender;
import com.cloudai.system.vo.SysLeaveVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假写路径单测（契约 2026-10-09-rocketmq-tx-approval-api §2.1 + §6 system 3xxx 账本；
 * 投影轮 D6/D8 改版）：发起 MQ 事务消息链路（3019/3023 msg 逐字/3025/半消息参数与 KEYS/
 * 同步返回 leaveId/纯业务行零状态列）+ 撤销 Feign 链路（3018/3020/3021/3022 转译 +
 * 成功后框架对账按需即时回写投影）。
 */
@ExtendWith(MockitoExtension.class)
class LeaveWorkflowServiceTest {

    @Mock
    private SysLeaveMapper leaveMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private BpmnApprovalClient approvalClient;
    @Mock
    private TxMessageSender txMessageSender;
    @Mock
    private ApprovalProjectionReconciler projectionReconciler;
    @InjectMocks
    private LeaveWorkflowService service;

    // ---- saveLeave（MQ 事务消息链路） ----

    @Test
    void saveLeave_happyPath_sendsTxHalfMessageAndReturnsLeaveId() {
        stubApproverFound();

        Long id = service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "userA");

        // snowflake 预生成（设计 R2）：同步返回 id 即消息 businessKey
        assertThat(id).isNotNull();
        ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> bizArgCaptor = ArgumentCaptor.forClass(Object.class);
        verify(txMessageSender).sendTransactional(eq(ApprovalMqTopics.TOPIC_TX_APPROVAL_CREATE),
                isNull(), eq("leave:" + id), payloadCaptor.capture(),
                eq(LeaveCreateTxExecutor.CHANNEL), bizArgCaptor.capture());
        ApprovalCreateInnerRequest payload = (ApprovalCreateInnerRequest) payloadCaptor.getValue();
        assertThat(payload.getBusinessType()).isEqualTo("leave");
        assertThat(payload.getBusinessKey()).isEqualTo(String.valueOf(id));
        assertThat(payload.getTitle()).isEqualTo("annual leave");
        assertThat(payload.getApplyUser()).isEqualTo("userA");
        assertThat(payload.getApprover()).isEqualTo("admin");
        SysLeave leave = (SysLeave) bizArgCaptor.getValue();
        assertThat(leave.getId()).isEqualTo(id);
        assertThat(leave.getCreateBy()).isEqualTo("userA");
        // 纯业务行无状态列（投影轮 D6）：状态真相=投影 JOIN 派生
        assertThat(leave.getTitle()).isEqualTo("annual leave");
        // insert 归 executor 本地事务——service 不直接写库
        verify(leaveMapper, never()).save(any());
    }

    @Test
    void saveLeave_endBeforeStartRejected_3019() {
        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-10-09", "2026-10-08", "admin"), "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3019);
        assertThat(ex.getMessage()).isEqualTo("请假日期无效：结束日期不能早于开始日期");
        verify(txMessageSender, never()).sendTransactional(anyString(), any(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void saveLeave_unparseableDateRejected_3019() {
        // DTO @Pattern 已拦格式，Service 解析防御（不可解析同归 3019）
        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-13-99", "2026-10-09", "admin"), "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3019);
        verify(txMessageSender, never()).sendTransactional(anyString(), any(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void saveLeave_approverMissingLocally_3023_literalMsg() {
        // 3023 本库前置（契约 §2.1）：findByAccount 查无 → msg 逐字与平台 4013 同文案
        when(userMapper.findByAccount("ghost")).thenReturn(null);

        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-10-08", "2026-10-09", "ghost"), "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3023);
        assertThat(ex.getMessage()).isEqualTo("审批人无效: ghost");
        verify(txMessageSender, never()).sendTransactional(anyString(), any(), anyString(),
                any(), anyString(), any());
    }

    @Test
    void saveLeave_halfMessageFailure_3025() {
        stubApproverFound();
        when(txMessageSender.sendTransactional(anyString(), any(), anyString(), any(), anyString(), any()))
                .thenThrow(new TxMessageSendException("half message failed", null));

        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "userA"),
                BusinessException.class);

        // 半消息失败=本地零写（本地事务从未执行），发起被拒可重试
        assertThat(ex.getCode()).isEqualTo(3025);
        assertThat(ex.getMessage()).isEqualTo("消息服务不可用");
    }

    // ---- cancelLeave（Feign 主路径不变 + 对账收敛，契约 §4.2） ----

    @Test
    void cancelLeave_notFoundRejected_3018() {
        when(leaveMapper.findById("leave", 9L)).thenReturn(null);
        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(9L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3018);
        assertThat(ex.getMessage()).isEqualTo("请假单不存在");
    }

    @Test
    void cancelLeave_notApplierRejected_3021() {
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo("0", 12L));
        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userB"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3021);
        verify(approvalClient, never()).cancel(any());
    }

    @Test
    void cancelLeave_terminalRejected_3020() {
        // 终态判据=JOIN 派生 status（设计 D8）：1/2/3/4 全拒
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo("1", 12L));
        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3020);
        verify(approvalClient, never()).cancel(any());
    }

    @Test
    void cancelLeave_failedTerminalRejected_3020() {
        // status=4 发起失败同属终态：不可撤销（LeaveStatus.isTerminal 涵盖，契约 §2.2）
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo("4", null));
        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3020);
        verify(approvalClient, never()).cancel(any());
    }

    @Test
    void cancelLeave_feignFailure_3022_noReconcile() {
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo("0", 12L));
        when(approvalClient.cancel(any())).thenThrow(new RuntimeException("connection refused"));

        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3022);
        // Feign 失败撤销未生效：不触发对账（投影状态不动）
        verify(projectionReconciler, never()).reconcileByBusiness(anyString(), anyList());
    }

    @Test
    void cancelLeave_platformTerminal_translated_3020() {
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo("0", 12L));
        when(approvalClient.cancel(any())).thenReturn(R.fail(4011, "审批单已终态，不可撤销"));

        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3020);
        verify(projectionReconciler, never()).reconcileByBusiness(anyString(), anyList());
    }

    @Test
    void cancelLeave_platformNotApplier_translated_3021() {
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo("0", 12L));
        when(approvalClient.cancel(any())).thenReturn(R.fail(4012, "仅申请人本人可撤销"));

        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3021);
        verify(projectionReconciler, never()).reconcileByBusiness(anyString(), anyList());
    }

    @Test
    void cancelLeave_happyPath_triggersImmediateReconcile() {
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo("0", 12L));
        when(approvalClient.cancel(any())).thenReturn(R.ok());

        service.cancelLeave(5L, "userA");

        // 撤销成功 → 框架对账组件按需即时回写投影（best-effort，设计 D8）——替代原本地置 3
        verify(approvalClient).cancel(any());
        verify(projectionReconciler).reconcileByBusiness(eq("leave"), eq(List.of("5")));
    }

    // ---- 脚手架 ----

    private void stubApproverFound() {
        SysUser admin = new SysUser();
        admin.setAccount("admin");
        when(userMapper.findByAccount("admin")).thenReturn(admin);
    }

    private LeaveCreateRequest request(String start, String end, String approver) {
        LeaveCreateRequest req = new LeaveCreateRequest();
        req.setTitle("annual leave");
        req.setLeaveType("3");
        req.setStartDate(start);
        req.setEndDate(end);
        req.setReason("trip");
        req.setApprover(approver);
        return req;
    }

    /** JOIN 派生读返 VO（mapper mock 契约面；status 为派生 String 值） */
    private SysLeaveVo vo(String status, Long approvalId) {
        SysLeaveVo vo = new SysLeaveVo();
        vo.setId(5L);
        vo.setStatus(status);
        vo.setApprovalId(approvalId);
        vo.setApplyUser("userA");
        vo.setApprover("admin");
        return vo;
    }
}
