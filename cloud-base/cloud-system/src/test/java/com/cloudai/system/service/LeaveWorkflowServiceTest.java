package com.cloudai.system.service;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.LeaveCreateRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.entity.SysLeave.StatusEnum;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mq.LeaveCreateTxExecutor;
import com.cloudai.common.rocketmq.tx.TxMessageSendException;
import com.cloudai.common.rocketmq.tx.TxMessageSender;
import com.cloudai.system.entity.SysUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假写路径单测（契约 2026-10-09-rocketmq-tx-approval-api §2.1 + §6 system 3xxx 账本）：
 * 发起 MQ 事务消息链路（3019/3023 msg 逐字/3025/半消息参数与 KEYS/同步返回 leaveId）+
 * 撤销 Feign 链路不变（3018/3020/3021/3022 转译 + 本地置 3）。
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
        assertThat(leave.getStatus()).isEqualTo(StatusEnum.APPROVING.getCode());
        assertThat(leave.getCreateBy()).isEqualTo("userA");
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

    // ---- cancelLeave（Feign 链路不变，契约 §2.3） ----

    @Test
    void cancelLeave_notFoundRejected_3018() {
        when(leaveMapper.findById(9L)).thenReturn(null);
        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(9L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3018);
        assertThat(ex.getMessage()).isEqualTo("请假单不存在");
    }

    @Test
    void cancelLeave_notApplierRejected_3021() {
        when(leaveMapper.findById(5L)).thenReturn(approvingLeave("userA"));
        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userB"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3021);
        verify(approvalClient, never()).cancel(any());
    }

    @Test
    void cancelLeave_terminalRejected_3020() {
        SysLeave approved = approvingLeave("userA");
        approved.setStatus(StatusEnum.APPROVED.getCode());
        when(leaveMapper.findById(5L)).thenReturn(approved);
        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3020);
        verify(approvalClient, never()).cancel(any());
    }

    @Test
    void cancelLeave_failedTerminalRejected_3020() {
        // status=4 发起失败同属终态：不可撤销（isTerminal 涵盖，契约 §2.2）
        SysLeave failed = approvingLeave("userA");
        failed.setStatus(StatusEnum.FAILED.getCode());
        failed.setApprovalId(null);
        when(leaveMapper.findById(5L)).thenReturn(failed);
        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3020);
        verify(approvalClient, never()).cancel(any());
    }

    @Test
    void cancelLeave_feignFailure_3022_localUntouched() {
        when(leaveMapper.findById(5L)).thenReturn(approvingLeave("userA"));
        when(approvalClient.cancel(any())).thenThrow(new RuntimeException("connection refused"));

        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3022);
        // Feign 失败本地不动（契约 §5.4）
        verify(leaveMapper, never()).updateStatusById(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void cancelLeave_platformTerminal_translated_3020_noLocalWrite() {
        when(leaveMapper.findById(5L)).thenReturn(approvingLeave("userA"));
        when(approvalClient.cancel(any())).thenReturn(R.fail(4011, "审批单已终态，不可撤销"));

        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3020);
        verify(leaveMapper, never()).updateStatusById(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void cancelLeave_platformNotApplier_translated_3021() {
        when(leaveMapper.findById(5L)).thenReturn(approvingLeave("userA"));
        when(approvalClient.cancel(any())).thenReturn(R.fail(4012, "仅申请人本人可撤销"));

        BusinessException ex = catchThrowableOfType(() -> service.cancelLeave(5L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3021);
        verify(leaveMapper, never()).updateStatusById(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void cancelLeave_happyPath_marksCancelled() {
        when(leaveMapper.findById(5L)).thenReturn(approvingLeave("userA"));
        when(approvalClient.cancel(any())).thenReturn(R.ok());

        service.cancelLeave(5L, "userA");

        verify(approvalClient).cancel(any());
        verify(leaveMapper).updateStatusById(eq(5L), eq(StatusEnum.CANCELLED.getCode()), eq("userA"), any());
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

    private SysLeave approvingLeave(String applyUser) {
        SysLeave leave = new SysLeave();
        leave.setId(5L);
        leave.setStatus(StatusEnum.APPROVING.getCode());
        leave.setApplyUser(applyUser);
        leave.setApprover("admin");
        leave.setApprovalId(12L);
        return leave;
    }
}
