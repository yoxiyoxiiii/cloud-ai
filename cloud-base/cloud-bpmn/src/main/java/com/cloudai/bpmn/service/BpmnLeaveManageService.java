package com.cloudai.bpmn.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.bpmn.client.SystemUserClient;
import com.cloudai.bpmn.convert.BpmnLeaveConvert;
import com.cloudai.bpmn.entity.BpmnLeave;
import com.cloudai.bpmn.entity.BpmnLeave.StatusEnum;
import com.cloudai.bpmn.mapper.BpmnLeaveMapper;
import com.cloudai.bpmn.util.BpmnDateUtil;
import com.cloudai.bpmn.vo.ApprovalStepVo;
import com.cloudai.bpmn.vo.LeaveDetailVo;
import com.cloudai.bpmn.vo.LeaveVo;
import com.cloudai.bpmn.vo.UserOptionVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.common.translate.domain.UserEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.task.Comment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请假单查询与时间线拼装（设计 D8）。详情三源：请假单行（apply 步）+ ACT_HI_COMMENT（approval 步）
 * + HistoricProcessInstance（end 步），契约 2026-10-07-bpmn-leave-api §2.2/§2.3/§2.5。
 * 只读服务不加事务注解；详情嵌套 VO 不在 TranslateAdvisor 容器下钻范围（候选①挂账）——
 * 译文字段经 TranslationCacheService 手动回填，未命中 null 同降级语义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BpmnLeaveManageService {

    private static final int ERR_LEAVE_NOT_FOUND = 4001;

    private static final String DICT_LEAVE_STATUS = "bpmn_leave_status";
    private static final String DICT_LEAVE_TYPE = "bpmn_leave_type";

    private static final String STEP_APPLY = "apply";
    private static final String STEP_APPROVAL = "approval";
    private static final String STEP_END = "end";

    private final BpmnLeaveMapper leaveMapper;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final SystemUserClient systemUserClient;
    private final TranslationCacheService translationCacheService;

    /** 我的申请分页（恒按申请人，id 倒序，含全部状态） */
    public PageResult<LeaveVo> pageListMy(PageQuery query, String applyUser) {
        IPage<BpmnLeave> page = leaveMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), applyUser);
        List<LeaveVo> rows = page.getRecords().stream().map(BpmnLeaveConvert::toVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    /** 请假单详情：leave 主体 + steps 时间线（apply/approval/end 按时间升序） */
    public LeaveDetailVo findById(Long id) {
        BpmnLeave leave = leaveMapper.findById(id);
        if (leave == null) {
            throw new BusinessException(ERR_LEAVE_NOT_FOUND, "请假单不存在");
        }
        LeaveDetailVo detail = new LeaveDetailVo();
        detail.setLeave(BpmnLeaveConvert.toVo(leave));
        detail.setSteps(assembleSteps(leave));
        fillDetailLabels(detail);
        return detail;
    }

    /** 审批人投影（契约 §2.5：system /inner/user/all 直通，含停用账号——宽松语义记档）；Feign 失败 1002 */
    public List<UserOptionVo> listApprovers() {
        R<List<UserEntry>> response;
        try {
            response = systemUserClient.listAll();
        } catch (Exception e) {
            log.error("system 用户投影 Feign 调用失败", e);
            throw new BusinessException("用户服务不可用");
        }
        List<UserEntry> users = response == null ? null : response.getData();
        if (users == null) {
            log.error("system 用户投影返回异常: {}", response);
            throw new BusinessException("用户服务不可用");
        }
        return users.stream().map(BpmnLeaveManageService::toOptionVo).toList();
    }

    /** 时间线拼装：apply 恒在；审批中单无 end 步（el-steps active=approval）；终态单附 end（结果） */
    private List<ApprovalStepVo> assembleSteps(BpmnLeave leave) {
        List<ApprovalStepVo> steps = new ArrayList<>();
        steps.add(applyStep(leave));
        String processInstanceId = leave.getProcessInstanceId();
        List<Comment> comments = processInstanceId == null
                ? List.of()
                : taskService.getProcessInstanceComments(processInstanceId);
        steps.addAll(approvalSteps(comments));
        if (StatusEnum.of(leave.getStatus()).isTerminal()) {
            steps.add(endStep(leave));
        }
        return steps;
    }

    private ApprovalStepVo applyStep(BpmnLeave leave) {
        ApprovalStepVo step = new ApprovalStepVo();
        step.setStepKey(STEP_APPLY);
        step.setTitle("发起申请");
        step.setOperator(leave.getApplyUser());
        step.setTime(BpmnDateUtil.format(leave.getCreateTime()));
        return step;
    }

    private List<ApprovalStepVo> approvalSteps(List<Comment> comments) {
        List<ApprovalStepVo> steps = new ArrayList<>();
        for (Comment comment : comments) {
            ApprovalStepVo step = new ApprovalStepVo();
            step.setStepKey(STEP_APPROVAL);
            step.setTitle("审批意见");
            step.setOperator(comment.getUserId());
            step.setComment(comment.getFullMessage());
            step.setTime(BpmnDateUtil.format(comment.getTime()));
            steps.add(step);
        }
        return steps;
    }

    /** end 步：有实例取引擎结束时间；撤销单（实例已删/关联清空）取状态变更时间；result=状态同文案 */
    private ApprovalStepVo endStep(BpmnLeave leave) {
        ApprovalStepVo step = new ApprovalStepVo();
        step.setStepKey(STEP_END);
        step.setTitle("流程结束");
        step.setResult(resultText(leave.getStatus()));
        if (leave.getProcessInstanceId() != null) {
            HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(leave.getProcessInstanceId())
                    .singleResult();
            if (historic != null) {
                step.setTime(BpmnDateUtil.format(historic.getEndTime()));
            }
        }
        if (step.getTime() == null) {
            step.setTime(BpmnDateUtil.format(leave.getUpdateTime()));
        }
        return step;
    }

    /** 手动翻译回填（列表端点走注解；详情嵌套结构 Advisor 不下钻——候选①挂账的官方绕行） */
    private void fillDetailLabels(LeaveDetailVo detail) {
        LeaveVo leave = detail.getLeave();
        Map<String, String> statusLabels = translationCacheService.findDictLabels(
                DICT_LEAVE_STATUS, Set.of(leave.getStatus()));
        leave.setStatusLabel(statusLabels.get(leave.getStatus()));
        Map<String, String> typeLabels = translationCacheService.findDictLabels(
                DICT_LEAVE_TYPE, Set.of(leave.getLeaveType()));
        leave.setLeaveTypeLabel(typeLabels.get(leave.getLeaveType()));
        Map<String, String> userNames = translationCacheService.findUserNames(userParties(leave));
        leave.setApplyUserName(userNames.get(leave.getApplyUser()));
        leave.setApproverName(userNames.get(leave.getApprover()));
        Map<String, String> operatorNames = operatorNames(detail.getSteps());
        for (ApprovalStepVo step : detail.getSteps()) {
            if (step.getOperator() != null) {
                step.setOperatorName(operatorNames.get(step.getOperator()));
            }
        }
    }

    /** 申请/审批双方账号集：同人自审批（applyUser==approver）合法存在——HashSet 去重，禁 Set.of（重复元素抛异常） */
    private Set<String> userParties(LeaveVo leave) {
        Set<String> parties = new java.util.HashSet<>();
        if (leave.getApplyUser() != null) {
            parties.add(leave.getApplyUser());
        }
        if (leave.getApprover() != null) {
            parties.add(leave.getApprover());
        }
        return parties;
    }

    private Map<String, String> operatorNames(List<ApprovalStepVo> steps) {
        Set<String> operators = new java.util.HashSet<>();
        for (ApprovalStepVo step : steps) {
            if (step.getOperator() != null) {
                operators.add(step.getOperator());
            }
        }
        return operators.isEmpty() ? new HashMap<>() : translationCacheService.findUserNames(operators);
    }

    /** 仅 end 步 result 文案（与字典 bpmn_leave_status label 同文案，契约 §2.3） */
    private String resultText(Integer status) {
        StatusEnum statusEnum = StatusEnum.of(status);
        return switch (statusEnum) {
            case APPROVED -> "已通过";
            case REJECTED -> "已拒绝";
            case CANCELLED -> "已撤销";
            case APPROVING -> null;
        };
    }

    private static UserOptionVo toOptionVo(UserEntry entry) {
        UserOptionVo vo = new UserOptionVo();
        vo.setId(entry.getId());
        vo.setAccount(entry.getAccount());
        vo.setNickname(entry.getNickname());
        return vo;
    }
}
