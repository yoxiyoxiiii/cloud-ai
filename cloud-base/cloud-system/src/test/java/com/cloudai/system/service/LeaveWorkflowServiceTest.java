package com.cloudai.system.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.client.BpmnApprovalClient;
import com.cloudai.system.dto.LeaveCreateRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.entity.SysLeave.StatusEnum;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.vo.ApprovalCreateVo;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假写路径单测（契约 2026-10-08-approval-platform-api §5.1/§5.4 + §6 system 3xxx 账本：
 * 3018/3019/3020/3021/3022 全触发 + 平台码转译三态 + 3023/3024 msg 逐字 +
 * Feign 失败异常穿透（@Transactional rollbackFor 由代理回滚本地 insert，单测断言穿透与零后续写）。
 */
@ExtendWith(MockitoExtension.class)
class LeaveWorkflowServiceTest {

    @Mock
    private SysLeaveMapper leaveMapper;
    @Mock
    private BpmnApprovalClient approvalClient;
    @InjectMocks
    private LeaveWorkflowService service;

    // ---- saveLeave ----

    @Test
    void saveLeave_happyPath_insertsFeignsAndBackfills() {
        doAnswer(inv -> {
            inv.getArgument(0, SysLeave.class).setId(5L);
            return 1;
        }).when(leaveMapper).save(any(SysLeave.class));
        when(approvalClient.create(any())).thenReturn(R.ok(createVo("12")));

        Long id = service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "userA");

        assertThat(id).isEqualTo(5L);
        ArgumentCaptor<SysLeave> captor = ArgumentCaptor.forClass(SysLeave.class);
        verify(leaveMapper).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(StatusEnum.APPROVING.getCode());
        assertThat(captor.getValue().getApplyUser()).isEqualTo("userA");
        assertThat(captor.getValue().getCreateBy()).isEqualTo("userA");
        verify(leaveMapper).updateApprovalId(eq(5L), eq(12L), eq("userA"), any());
    }

    @Test
    void saveLeave_endBeforeStartRejected_3019() {
        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-10-09", "2026-10-08", "admin"), "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3019);
        assertThat(ex.getMessage()).isEqualTo("请假日期无效：结束日期不能早于开始日期");
        verify(leaveMapper, never()).save(any());
    }

    @Test
    void saveLeave_unparseableDateRejected_3019() {
        // DTO @Pattern 已拦格式，Service 解析防御（不可解析同归 3019）
        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-13-99", "2026-10-09", "admin"), "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3019);
        verify(leaveMapper, never()).save(any());
    }

    @Test
    void saveLeave_feignFailure_3022_penetrates_noFollowUpWrites() {
        doAnswer(inv -> {
            inv.getArgument(0, SysLeave.class).setId(5L);
            return 1;
        }).when(leaveMapper).save(any(SysLeave.class));
        when(approvalClient.create(any())).thenThrow(new RuntimeException("connection refused"));

        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "userA"),
                BusinessException.class);

        // 异常穿透 @Transactional 边界 → 本地 insert 由代理回滚（rollbackFor=Exception）；
        // 单测可断言的零后续写：approval_id 永不回填
        assertThat(ex.getCode()).isEqualTo(3022);
        assertThat(ex.getMessage()).isEqualTo("审批服务不可用");
        verify(leaveMapper, never()).updateApprovalId(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void saveLeave_platformApproverInvalid_translated_3023() {
        doAnswer(inv -> {
            inv.getArgument(0, SysLeave.class).setId(5L);
            return 1;
        }).when(leaveMapper).save(any(SysLeave.class));
        when(approvalClient.create(any())).thenReturn(R.fail(4013, "审批人无效: ghost"));

        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-10-08", "2026-10-09", "ghost"), "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3023);
        // msg 逐字（动态后缀，本地拼接保持与平台同文案）：审批人无效: {approver}
        assertThat(ex.getMessage()).isEqualTo("审批人无效: ghost");
        verify(leaveMapper, never()).updateApprovalId(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void saveLeave_platformExists_translated_3024() {
        doAnswer(inv -> {
            inv.getArgument(0, SysLeave.class).setId(5L);
            return 1;
        }).when(leaveMapper).save(any(SysLeave.class));
        when(approvalClient.create(any())).thenReturn(R.fail(4015, "该业务单据已存在审批"));

        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3024);
        // msg 逐字（契约 §6 system 账本）：该请假单已存在审批
        assertThat(ex.getMessage()).isEqualTo("该请假单已存在审批");
        verify(leaveMapper, never()).updateApprovalId(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void saveLeave_platformOther_translated_3022() {
        doAnswer(inv -> {
            inv.getArgument(0, SysLeave.class).setId(5L);
            return 1;
        }).when(leaveMapper).save(any(SysLeave.class));
        when(approvalClient.create(any())).thenReturn(R.fail(4017, "流程定义未部署"));

        BusinessException ex = catchThrowableOfType(
                () -> service.saveLeave(request("2026-10-08", "2026-10-09", "admin"), "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3022);
        verify(leaveMapper, never()).updateApprovalId(anyLong(), anyLong(), anyString(), any());
    }

    // ---- cancelLeave ----

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

    private ApprovalCreateVo createVo(String approvalId) {
        ApprovalCreateVo vo = new ApprovalCreateVo();
        vo.setApprovalId(approvalId);
        vo.setStatus("0");
        return vo;
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
