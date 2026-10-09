package com.cloudai.system.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.system.dto.LeaveCreateRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.entity.SysLeave.StatusEnum;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;

/**
 * 请假写路径编排（契约 2026-10-08-approval-platform-api §5.1/§5.4，设计 D6 事务边界）：
 * system 本地事务 + Feign，无全局原子性——发起 Feign 失败本地全回滚；
 * 孤儿审批单窗口（Feign 成功+本地后续失败）记档可撤销兜底。
 * 平台错误码转译：4013→3023、4015→3024、其余 4xxx/传输异常→3022；撤销 4011→3020、4012→3021。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveWorkflowService {

    private static final int ERR_LEAVE_NOT_FOUND = 3018;
    private static final int ERR_DATE_INVALID = 3019;
    private static final int ERR_LEAVE_TERMINAL = 3020;
    private static final int ERR_NOT_APPLIER = 3021;
    private static final int ERR_APPROVAL_UNAVAILABLE = 3022;
    private static final int ERR_APPROVER_INVALID = 3023;
    private static final int ERR_LEAVE_EXISTS = 3024;

    /** 平台侧错误码（契约 §6 bpmn 4xxx 账本，转译映射源） */
    private static final int PLATFORM_APPROVER_INVALID = 4013;
    private static final int PLATFORM_APPROVAL_EXISTS = 4015;
    private static final int PLATFORM_TERMINAL = 4011;
    private static final int PLATFORM_NOT_APPLIER = 4012;

    private static final String BUSINESS_TYPE_LEAVE = "leave";

    private final SysLeaveMapper leaveMapper;
    private final BpmnApprovalClient approvalClient;

    /** 发起请假（§5.1）：本地事务内 insert(审批中) → Feign 发起审批 → 回填 approval_id → 提交；
     *  Feign 失败全回滚（sys_leave 零行），平台 4013→3023/4015→3024/其余→3022 */
    @Transactional(rollbackFor = Exception.class)
    public Long saveLeave(LeaveCreateRequest req, String operator) {
        LocalDate[] range = parseDateRange(req);
        SysLeave leave = buildLeave(req, range, operator);
        leaveMapper.save(leave);

        ApprovalCreateInnerRequest createReq = buildCreateRequest(leave, operator);
        InnerApprovalCreateVo created = createApproval(createReq, req.getApprover());

        leaveMapper.updateApprovalId(leave.getId(), Long.valueOf(created.getApprovalId()),
                operator, LocalDateTime.now());
        return leave.getId();
    }

    /** 撤销请假（§5.4）：3018→3021→3020 本地校验 → Feign 平台撤销 → 成功本地置 3；
     *  Feign 失败本地不动（3022/转译同上）——本地单语句写不加事务注解 */
    public void cancelLeave(Long id, String operator) {
        SysLeave leave = requireLeave(id);
        checkCancelable(leave, operator);

        ApprovalCancelInnerRequest cancelReq = new ApprovalCancelInnerRequest();
        cancelReq.setBusinessType(BUSINESS_TYPE_LEAVE);
        cancelReq.setBusinessKey(String.valueOf(id));
        cancelReq.setOperator(operator);
        R<Void> response;
        try {
            response = approvalClient.cancel(cancelReq);
        } catch (Exception e) {
            log.error("撤销审批 Feign 调用失败: leaveId={}", id, e);
            throw new BusinessException(ERR_APPROVAL_UNAVAILABLE, "审批服务不可用");
        }
        if (response == null || response.getCode() != ErrorCode.SUCCESS.getCode()) {
            throw translateCancelFailure(response, id);
        }
        leaveMapper.updateStatusById(id, StatusEnum.CANCELLED.getCode(), operator, LocalDateTime.now());
    }

    // ---- 发起脚手架 ----

    /** 日期解析与校验（DTO @Pattern 已拦格式，此处解析防御）：不可解析/结束早于开始 → 3019 */
    private LocalDate[] parseDateRange(LeaveCreateRequest req) {
        LocalDate start;
        LocalDate end;
        try {
            start = LocalDate.parse(req.getStartDate());
            end = LocalDate.parse(req.getEndDate());
        } catch (DateTimeParseException e) {
            log.error("请假日期不可解析: start={}, end={}", req.getStartDate(), req.getEndDate());
            throw new BusinessException(ERR_DATE_INVALID, "请假日期无效：结束日期不能早于开始日期");
        }
        if (end.isBefore(start)) {
            throw new BusinessException(ERR_DATE_INVALID, "请假日期无效：结束日期不能早于开始日期");
        }
        return new LocalDate[]{start, end};
    }

    /** 手写 SQL 无自动填充：审计四值显式构造（插入时 update 值 = create 值） */
    private SysLeave buildLeave(LeaveCreateRequest req, LocalDate[] range, String operator) {
        SysLeave leave = new SysLeave();
        leave.setTitle(req.getTitle());
        leave.setLeaveType(Integer.valueOf(req.getLeaveType()));
        leave.setStartDate(range[0]);
        leave.setEndDate(range[1]);
        leave.setReason(req.getReason());
        leave.setStatus(StatusEnum.APPROVING.getCode());
        leave.setApplyUser(operator);
        leave.setApprover(req.getApprover());
        LocalDateTime now = LocalDateTime.now();
        leave.setCreateBy(operator);
        leave.setCreateTime(now);
        leave.setUpdateBy(operator);
        leave.setUpdateTime(now);
        return leave;
    }

    private ApprovalCreateInnerRequest buildCreateRequest(SysLeave leave, String operator) {
        ApprovalCreateInnerRequest createReq = new ApprovalCreateInnerRequest();
        createReq.setBusinessType(BUSINESS_TYPE_LEAVE);
        createReq.setBusinessKey(String.valueOf(leave.getId()));
        createReq.setTitle(leave.getTitle());
        createReq.setApplyUser(operator);
        createReq.setApprover(leave.getApprover());
        return createReq;
    }

    /** Feign 发起 + 平台码转译；传输异常直接抛（@Transactional 回滚本地 insert） */
    private InnerApprovalCreateVo createApproval(ApprovalCreateInnerRequest req, String approver) {
        R<InnerApprovalCreateVo> response;
        try {
            response = approvalClient.create(req);
        } catch (Exception e) {
            log.error("发起审批 Feign 调用失败: businessKey={}", req.getBusinessKey(), e);
            throw new BusinessException(ERR_APPROVAL_UNAVAILABLE, "审批服务不可用");
        }
        if (response == null || response.getCode() != ErrorCode.SUCCESS.getCode() || response.getData() == null) {
            throw translateCreateFailure(response, approver);
        }
        return response.getData();
    }

    /** 发起失败转译：4013→3023（msg 逐字「审批人无效: {approver}」）/ 4015→3024（「该请假单已存在审批」）/ 其余→3022 */
    private BusinessException translateCreateFailure(R<InnerApprovalCreateVo> response, String approver) {
        int platformCode = response == null ? 0 : response.getCode();
        if (platformCode == PLATFORM_APPROVER_INVALID) {
            return new BusinessException(ERR_APPROVER_INVALID, "审批人无效: " + approver);
        }
        if (platformCode == PLATFORM_APPROVAL_EXISTS) {
            return new BusinessException(ERR_LEAVE_EXISTS, "该请假单已存在审批");
        }
        log.error("发起审批平台返回失败: code={}, msg={}", platformCode,
                response == null ? null : response.getMsg());
        return new BusinessException(ERR_APPROVAL_UNAVAILABLE, "审批服务不可用");
    }

    // ---- 撤销脚手架 ----

    private SysLeave requireLeave(Long id) {
        SysLeave leave = leaveMapper.findById(id);
        if (leave == null) {
            throw new BusinessException(ERR_LEAVE_NOT_FOUND, "请假单不存在");
        }
        return leave;
    }

    /** 撤销校验序（契约 §5.4）：3018（已查行）→ 3021 仅申请人本人 → 3020 已终态 */
    private void checkCancelable(SysLeave leave, String operator) {
        if (!leave.getApplyUser().equals(operator)) {
            throw new BusinessException(ERR_NOT_APPLIER, "仅申请人本人可撤销");
        }
        if (StatusEnum.of(leave.getStatus()).isTerminal()) {
            throw new BusinessException(ERR_LEAVE_TERMINAL, "请假单已终态，不可撤销");
        }
    }

    /** 撤销失败转译：4011→3020 / 4012→3021 / 其余→3022（本地状态不动） */
    private BusinessException translateCancelFailure(R<Void> response, Long id) {
        int platformCode = response == null ? 0 : response.getCode();
        if (platformCode == PLATFORM_TERMINAL) {
            return new BusinessException(ERR_LEAVE_TERMINAL, "请假单已终态，不可撤销");
        }
        if (platformCode == PLATFORM_NOT_APPLIER) {
            return new BusinessException(ERR_NOT_APPLIER, "仅申请人本人可撤销");
        }
        log.error("撤销审批平台返回失败: leaveId={}, code={}, msg={}", id, platformCode,
                response == null ? null : response.getMsg());
        return new BusinessException(ERR_APPROVAL_UNAVAILABLE, "审批服务不可用");
    }
}
