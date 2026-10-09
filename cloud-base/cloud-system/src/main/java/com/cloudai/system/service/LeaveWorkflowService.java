package com.cloudai.system.service;

import cn.hutool.core.util.IdUtil;
import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.bpmn.api.projection.ApprovalProjectionReconciler;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.constant.LeaveStatus;
import com.cloudai.system.dto.LeaveCreateRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mq.LeaveCreateTxExecutor;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.common.rocketmq.tx.TxMessageSendException;
import com.cloudai.common.rocketmq.tx.TxMessageSender;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 请假写路径编排（契约 2026-10-09-rocketmq-tx-approval-api §2.1 + 设计 D2 形态裁定；
 * 投影轮 2026-10-09 D6/D8 改版）：发起走 MQ 事务消息（编排化去 @Transactional——insert sys_leave
 * 纯业务行与 mq_tx_log 同在 starter listener 单事务，COMMIT 即本地已提交）；审批人校验 3023 本库前置；
 * approvalId/status 由投影事件秒级派生（JOIN 读，业务零回写）。
 * 撤销仍 Feign 同步（契约 §4.2 签名/错误码零变化）：4011→3020、4012→3021、其余/传输异常→3022；
 * 成功后框架对账组件按需即时对账（best-effort，失败秒级由 TERMINAL 事件收敛）。
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
    private static final int ERR_MQ_UNAVAILABLE = 3025;

    /** 平台侧错误码（契约 §6 bpmn 4xxx 账本，撤销转译映射源） */
    private static final int PLATFORM_TERMINAL = 4011;
    private static final int PLATFORM_NOT_APPLIER = 4012;

    private static final String BUSINESS_TYPE_LEAVE = "leave";

    private final SysLeaveMapper leaveMapper;
    private final SysUserMapper userMapper;
    private final BpmnApprovalClient approvalClient;
    private final TxMessageSender txMessageSender;
    private final ApprovalProjectionReconciler projectionReconciler;

    /** 发起请假（§2.1 语义修订版）：3019 日期 → 3023 审批人本库前置 → snowflake 预生成 id（设计 R2，
     *  半消息体发送前需知 businessKey）→ TX_APPROVAL_CREATE 事务半消息（本地事务=insert sys_leave 纯业务行
     *  + mq_tx_log）→ 同步返回新请假单 id（签名/响应零变化）；半消息失败 3025 本地零行 */
    public Long saveLeave(LeaveCreateRequest req, String operator) {
        LocalDate[] range = parseDateRange(req);
        requireApproverExists(req.getApprover());
        SysLeave leave = buildLeave(req, range, operator);
        leave.setId(IdUtil.getSnowflakeNextId());

        ApprovalCreateInnerRequest payload = buildCreateRequest(leave, operator);
        try {
            txMessageSender.sendTransactional(ApprovalMqTopics.TOPIC_TX_APPROVAL_CREATE, null,
                    BUSINESS_TYPE_LEAVE + ":" + leave.getId(), payload,
                    LeaveCreateTxExecutor.CHANNEL, leave);
        } catch (TxMessageSendException e) {
            log.error("发起审批事务半消息发送失败: leaveId={}", leave.getId(), e);
            throw new BusinessException(ERR_MQ_UNAVAILABLE, "消息服务不可用");
        }
        return leave.getId();
    }

    /** 撤销请假（§4.2 收敛方式修订版）：3018→3021→3020 本地校验（JOIN 派生 status 判终态）→
     *  Feign 平台撤销（bpmn 侧 TERMINAL/3 事务半消息）→ 框架对账按需即时回写投影（best-effort，
     *  失败秒级由事件收敛）——用户可见「撤销成功即列表已撤销」语义等价（设计 D8） */
    public void cancelLeave(Long id, String operator) {
        SysLeaveVo leave = requireLeave(id);
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
        // 撤销成功即时对账（组件内 best-effort 全吞不抛；Feign 降级时 TERMINAL 事务半消息兜底收敛）
        projectionReconciler.reconcileByBusiness(BUSINESS_TYPE_LEAVE, List.of(String.valueOf(id)));
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

    /** 审批人本库前置（契约 §2.1）：account 存在性（deleted=0 无 status 过滤=含停用，与平台
     *  listAll 投影同口径）；无效 3023，msg 逐字与平台 4013 同文案 */
    private void requireApproverExists(String approver) {
        if (userMapper.findByAccount(approver) == null) {
            throw new BusinessException(ERR_APPROVER_INVALID, "审批人无效: " + approver);
        }
    }

    /** 手写 SQL 无自动填充：审计四值显式构造（插入时 update 值 = create 值）；
     *  id 由调用方 snowflake 预生成显式写入（设计 R2）；纯业务行无状态列（投影派生读） */
    private SysLeave buildLeave(LeaveCreateRequest req, LocalDate[] range, String operator) {
        SysLeave leave = new SysLeave();
        leave.setTitle(req.getTitle());
        leave.setLeaveType(Integer.valueOf(req.getLeaveType()));
        leave.setStartDate(range[0]);
        leave.setEndDate(range[1]);
        leave.setReason(req.getReason());
        leave.setApplyUser(operator);
        leave.setApprover(req.getApprover());
        LocalDateTime now = LocalDateTime.now();
        leave.setCreateBy(operator);
        leave.setCreateTime(now);
        leave.setUpdateBy(operator);
        leave.setUpdateTime(now);
        return leave;
    }

    /** 流1 消息体（契约 §1.2：复用 ApprovalCreateInnerRequest，businessKey=leaveId） */
    private ApprovalCreateInnerRequest buildCreateRequest(SysLeave leave, String operator) {
        ApprovalCreateInnerRequest createReq = new ApprovalCreateInnerRequest();
        createReq.setBusinessType(BUSINESS_TYPE_LEAVE);
        createReq.setBusinessKey(String.valueOf(leave.getId()));
        createReq.setTitle(leave.getTitle());
        createReq.setApplyUser(operator);
        createReq.setApprover(leave.getApprover());
        return createReq;
    }

    // ---- 撤销脚手架 ----

    /** 详情读（JOIN 派生 status 判终态；无行 3018） */
    private SysLeaveVo requireLeave(Long id) {
        SysLeaveVo leave = leaveMapper.findById(BUSINESS_TYPE_LEAVE, id);
        if (leave == null) {
            throw new BusinessException(ERR_LEAVE_NOT_FOUND, "请假单不存在");
        }
        return leave;
    }

    /** 撤销校验序（契约 §4.2）：3018（已查行）→ 3021 仅申请人本人 → 3020 已终态（派生 status 含 4 同拒） */
    private void checkCancelable(SysLeaveVo leave, String operator) {
        if (!leave.getApplyUser().equals(operator)) {
            throw new BusinessException(ERR_NOT_APPLIER, "仅申请人本人可撤销");
        }
        if (LeaveStatus.isTerminal(leave.getStatus())) {
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
