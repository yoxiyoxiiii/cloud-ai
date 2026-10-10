package com.cloudai.bpmn.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.bpmn.convert.BpmnApprovalConvert;
import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnApproval.StatusEnum;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.util.BpmnDateUtil;
import com.cloudai.bpmn.vo.ApprovalDetailVo;
import com.cloudai.bpmn.vo.ApprovalDiagramVo;
import com.cloudai.bpmn.vo.ApprovalStepVo;
import com.cloudai.bpmn.vo.ApprovalVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.HistoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricActivityInstance;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.history.HistoricProcessInstanceQuery;
import org.flowable.engine.task.Comment;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审批单查询与时间线拼装（契约 2026-10-08-approval-platform-api §3）。详情三源：
 * bpmn_approval 行（apply 步）+ ACT_HI_COMMENT（approval 步）+ HistoricProcessInstance（end 步）。
 * 只读服务不加事务注解；详情嵌套 VO 不在 TranslateAdvisor 容器下钻范围（候选①挂账）——
 * 译文字段经 TranslationCacheService 手动回填，未命中 null 同降级语义。
 * 图数据（§3.4）：实例定位精确 id 优先、businessKey=approvalId 回退（限定本类型流程 key 取最新，
 * 防旧时代/异类型同号历史实例多行——singleResult 撞两行抛 FlowableException，F9 实证修复）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalQueryService {

    private static final int ERR_APPROVAL_NOT_FOUND = 4010;

    private static final String DICT_APPROVAL_STATUS = "bpmn_approval_status";

    private static final String STEP_APPLY = "apply";
    private static final String STEP_APPROVAL = "approval";
    private static final String STEP_END = "end";

    private final BpmnApprovalMapper approvalMapper;
    private final BusinessTypeRegistry businessTypeRegistry;
    private final TaskService taskService;
    private final HistoryService historyService;
    private final RuntimeService runtimeService;
    private final TranslationCacheService translationCacheService;

    /** 我的审批分页（恒按申请人，id 倒序，含全部状态与业务类型——契约 §3.1） */
    public PageResult<ApprovalVo> pageListMy(PageQuery query, String applyUser) {
        IPage<BpmnApproval> page = approvalMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), applyUser);
        List<ApprovalVo> rows = new ArrayList<>();
        Map<String, BpmnBusinessType> configCache = new HashMap<>();
        for (BpmnApproval approval : page.getRecords()) {
            rows.add(toEnrichedVo(approval, configCache));
        }
        return PageResult.of(page.getTotal(), rows);
    }

    /** 审批单详情（§3.2）：approval 主体 + steps 时间线（apply/approval/end 按时间升序） */
    public ApprovalDetailVo findById(Long id) {
        BpmnApproval approval = requireApproval(id);
        ApprovalDetailVo detail = new ApprovalDetailVo();
        detail.setApproval(toEnrichedVo(approval, new HashMap<>()));
        detail.setSteps(assembleSteps(approval));
        fillDetailLabels(detail);
        return detail;
    }

    /** 审批单图数据（§3.4）：实例定位（精确 id 优先，businessKey 回退取最新）→ 高亮四字段；
     *  历史缺失防御态返回 definitionId=null 空集合（不设错误码，前端隐藏图区） */
    public ApprovalDiagramVo findDiagram(Long id) {
        BpmnApproval approval = requireApproval(id);
        HistoricProcessInstance historic = findHistoricInstance(approval);
        if (historic == null) {
            return emptyDiagram();
        }
        return buildDiagram(historic);
    }

    /** 实例定位：审批单存有实例 id（§3.1 非撤销态）时精确锚定；撤销单实例 id 已清空，
     *  回退 businessKey=approvalId 查询限定本类型流程 key、按启动时间倒序取首条——
     *  旧时代（businessKey=旧业务 id）/异类型同号历史实例并存时多行容忍，不再 singleResult */
    private HistoricProcessInstance findHistoricInstance(BpmnApproval approval) {
        if (approval.getProcessInstanceId() != null) {
            HistoricProcessInstance exact = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(approval.getProcessInstanceId())
                    .singleResult();
            if (exact != null) {
                return exact;
            }
        }
        HistoricProcessInstanceQuery query =
                historyService.createHistoricProcessInstanceQuery()
                        .processInstanceBusinessKey(String.valueOf(approval.getId()));
        String processKey = processKeyOf(approval.getBusinessType());
        if (processKey != null) {
            query.processDefinitionKey(processKey);
        }
        return query.orderByProcessInstanceStartTime().desc()
                .list().stream().findFirst().orElse(null);
    }

    /** 业务类型配置 → 流程 key（配置缺失返回 null，回退查询不限定流程 key） */
    private String processKeyOf(String businessType) {
        BpmnBusinessType config = businessTypeRegistry.findByTypeCodeOrNull(businessType);
        return config == null ? null : config.getProcessKey();
    }

    // ---- 主体与配置渲染 ----

    /** 实体转 VO + 配置渲染回填（businessTypeName/detailPath）；配置缺失置 null 记档不炸（契约 §9 降级） */
    private ApprovalVo toEnrichedVo(BpmnApproval approval, Map<String, BpmnBusinessType> configCache) {
        ApprovalVo vo = BpmnApprovalConvert.toVo(approval);
        String typeCode = approval.getBusinessType();
        BpmnBusinessType config = configCache.computeIfAbsent(typeCode,
                code -> businessTypeRegistry.findByTypeCodeOrNull(code));
        if (config == null) {
            log.error("业务类型配置缺失，businessTypeName/detailPath 置 null: {}", typeCode);
            return vo;
        }
        vo.setBusinessTypeName(config.getTypeName());
        // {businessKey} 渲染源 = 审批单 id（契约 §1「bpmn_approval.id 即 businessKey」域语言，§2.1 示例）
        vo.setDetailPath(businessTypeRegistry.renderDetailPath(config.getDetailRoute(),
                String.valueOf(approval.getId())));
        return vo;
    }

    private BpmnApproval requireApproval(Long id) {
        BpmnApproval approval = approvalMapper.findById(id);
        if (approval == null) {
            throw new BusinessException(ERR_APPROVAL_NOT_FOUND, "审批单不存在");
        }
        return approval;
    }

    // ---- 时间线三源拼装 ----

    /** apply 恒在；审批中单无 end 步（el-steps active=approval）；终态单附 end（结果） */
    private List<ApprovalStepVo> assembleSteps(BpmnApproval approval) {
        List<ApprovalStepVo> steps = new ArrayList<>();
        steps.add(applyStep(approval));
        String processInstanceId = approval.getProcessInstanceId();
        List<Comment> comments = processInstanceId == null
                ? List.of()
                : taskService.getProcessInstanceComments(processInstanceId);
        steps.addAll(approvalSteps(comments));
        if (StatusEnum.of(approval.getStatus()).isTerminal()) {
            steps.add(endStep(approval));
        }
        return steps;
    }

    private ApprovalStepVo applyStep(BpmnApproval approval) {
        ApprovalStepVo step = new ApprovalStepVo();
        step.setStepKey(STEP_APPLY);
        step.setTitle("发起申请");
        step.setOperator(approval.getApplyUser());
        step.setTime(BpmnDateUtil.format(approval.getCreateTime()));
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
    private ApprovalStepVo endStep(BpmnApproval approval) {
        ApprovalStepVo step = new ApprovalStepVo();
        step.setStepKey(STEP_END);
        step.setTitle("流程结束");
        step.setResult(resultText(approval.getStatus()));
        if (approval.getProcessInstanceId() != null) {
            HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                    .processInstanceId(approval.getProcessInstanceId())
                    .singleResult();
            if (historic != null) {
                step.setTime(BpmnDateUtil.format(historic.getEndTime()));
            }
        }
        if (step.getTime() == null) {
            step.setTime(BpmnDateUtil.format(approval.getUpdateTime()));
        }
        return step;
    }

    /** 仅 end 步 result 文案（与字典 bpmn_approval_status label 同文案，契约 §3.2 沿 v1 §2.3） */
    private String resultText(Integer status) {
        StatusEnum statusEnum = StatusEnum.of(status);
        return switch (statusEnum) {
            case APPROVED -> "已通过";
            case REJECTED -> "已拒绝";
            case CANCELLED -> "已撤销";
            case APPROVING -> null;
        };
    }

    // ---- 嵌套 VO 手动翻译 ----

    /** 手动翻译回填（列表端点走注解；详情嵌套结构 Advisor 不下钻——候选①挂账的官方绕行） */
    private void fillDetailLabels(ApprovalDetailVo detail) {
        ApprovalVo approval = detail.getApproval();
        Map<String, String> statusLabels = translationCacheService.findDictLabels(
                DICT_APPROVAL_STATUS, Set.of(approval.getStatus()));
        approval.setStatusLabel(statusLabels.get(approval.getStatus()));
        Map<String, String> userNames = translationCacheService.findUserNames(userParties(approval));
        approval.setApplyUserName(userNames.get(approval.getApplyUser()));
        approval.setApproverName(userNames.get(approval.getApprover()));
        Map<String, String> operatorNames = operatorNames(detail.getSteps());
        for (ApprovalStepVo step : detail.getSteps()) {
            if (step.getOperator() != null) {
                step.setOperatorName(operatorNames.get(step.getOperator()));
            }
        }
    }

    /** 申请/审批双方账号集：同人自审批（applyUser==approver）合法存在——HashSet 去重，禁 Set.of（重复元素抛异常） */
    private Set<String> userParties(ApprovalVo approval) {
        Set<String> parties = new HashSet<>();
        if (approval.getApplyUser() != null) {
            parties.add(approval.getApplyUser());
        }
        if (approval.getApprover() != null) {
            parties.add(approval.getApprover());
        }
        return parties;
    }

    private Map<String, String> operatorNames(List<ApprovalStepVo> steps) {
        Set<String> operators = new HashSet<>();
        for (ApprovalStepVo step : steps) {
            if (step.getOperator() != null) {
                operators.add(step.getOperator());
            }
        }
        return operators.isEmpty() ? new HashMap<>() : translationCacheService.findUserNames(operators);
    }

    // ---- 图数据三态 ----

    /** 组装高亮四字段：definitionId/processInstanceId/end 取历史实例投影，活动集合走引擎查询 */
    private ApprovalDiagramVo buildDiagram(HistoricProcessInstance historic) {
        ApprovalDiagramVo vo = new ApprovalDiagramVo();
        vo.setDefinitionId(historic.getProcessDefinitionId());
        vo.setProcessInstanceId(historic.getId());
        vo.setActiveActivityIds(activeActivityIds(historic.getId()));
        vo.setCompletedActivityIds(completedActivityIds(historic.getId()));
        vo.setEndActivityId(historic.getEndActivityId());
        return vo;
    }

    /** 当前活动节点：先判运行中（runtime query count>0）再取——getActiveActivityIds
     *  对已结束实例是未定义行为（设计 D3 防御）；终态/撤销恒空数组（三态矩阵） */
    private List<String> activeActivityIds(String processInstanceId) {
        long running = runtimeService.createProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .count();
        if (running == 0) {
            return List.of();
        }
        return runtimeService.getActiveActivityIds(processInstanceId);
    }

    /** 已执行活动 id：ACT_HI_ACTINST 开始时间升序 LinkedHashSet 去重保序
     *  （含网关/事件节点，多余 id 对 Viewer 无害——契约 §3.4） */
    private List<String> completedActivityIds(String processInstanceId) {
        Set<String> ids = new LinkedHashSet<>();
        for (HistoricActivityInstance activity : historyService.createHistoricActivityInstanceQuery()
                .processInstanceId(processInstanceId)
                .orderByHistoricActivityInstanceStartTime().asc()
                .list()) {
            ids.add(activity.getActivityId());
        }
        return new ArrayList<>(ids);
    }

    /** 防御态 VO（理论不发生——历史表对三态均有痕）：definitionId/processInstanceId null + 双空集合 */
    private ApprovalDiagramVo emptyDiagram() {
        ApprovalDiagramVo vo = new ApprovalDiagramVo();
        vo.setActiveActivityIds(List.of());
        vo.setCompletedActivityIds(List.of());
        return vo;
    }
}
