package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnApprovalMapper;
import com.cloudai.bpmn.util.BpmnDateUtil;
import com.cloudai.bpmn.vo.TaskDoneVo;
import com.cloudai.bpmn.vo.TaskVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.common.engine.api.query.Query;
import org.flowable.engine.HistoryService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.flowable.task.api.TaskQuery;
import org.flowable.task.api.history.HistoricTaskInstance;
import org.flowable.task.api.history.HistoricTaskInstanceQuery;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 任务查询通用化（契约 2026-10-08-approval-platform-api §2.1/§2.2）：ACT_RU_TASK 待办 +
 * ACT_HI_TASKINST 已办，businessKey（=审批单 id）回查 bpmn_approval 快照 + 配置表渲染，
 * 零业务表回查、零跨服务。引擎 listPage+count 分页，total=引擎 count 含防御态脏行（脏行常态为零，
 * 仅历史脏数据时 total 虚大——契约记档口径）；businessKey 需经 HistoricProcessInstance 取
 * （Task 接口无投影，历史实例对运行中实例同样有痕）。只读服务不加事务注解。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAppService {

    private static final String ACTIVITY_END_APPROVE = "endApprove";
    private static final String ACTIVITY_END_REJECT = "endReject";

    private final TaskService taskService;
    private final HistoryService historyService;
    private final BpmnApprovalMapper approvalMapper;
    private final BusinessTypeRegistry businessTypeRegistry;

    /** 待办分页（assignee=当前登录人，任务创建时间倒序）；审批单缺失/键异常行跳过并补拉凑满当页 */
    public PageResult<TaskVo> pageListTodo(String assignee, PageQuery query) {
        TaskQuery taskQuery = taskService.createTaskQuery()
                .taskAssignee(assignee)
                .orderByTaskCreateTime().desc();
        long total = taskQuery.count();
        Map<String, BpmnBusinessType> configCache = new HashMap<>();
        List<TaskVo> rows = collectPageRows(taskQuery, total, query, task -> buildTodoVoOrNull(task, configCache));
        return PageResult.of(total, rows);
    }

    /** 已办分页（taskAssignee=当前登录人且 finished，办理时间倒序）；审批单缺失行跳过并补拉凑满当页 */
    public PageResult<TaskDoneVo> pageListDone(String assignee, PageQuery query) {
        HistoricTaskInstanceQuery taskQuery = historyService.createHistoricTaskInstanceQuery()
                .taskAssignee(assignee)
                .finished()
                .orderByHistoricTaskInstanceEndTime().desc();
        long total = taskQuery.count();
        Map<String, BpmnBusinessType> configCache = new HashMap<>();
        List<TaskDoneVo> rows = collectPageRows(taskQuery, total, query, task -> buildDoneVoOrNull(task, configCache));
        return PageResult.of(total, rows);
    }

    /** 补拉循环（todo/done 共用，契约 §2.1 分页语义）：脏行跳过后当页不满且未取尽时续拉下一批凑满一页。
     *  offset 按引擎返回的原始条数推进（按净行数推进会重复扫到已跳过的脏行）；引擎越界返回空批即终止，
     *  防并发收缩场景下 offset 永不追平 total 的死循环。 */
    private <T, V> List<V> collectPageRows(Query<?, T> taskQuery, long total, PageQuery page, Function<T, V> rowMapper) {
        List<V> out = new ArrayList<>();
        int offset = (page.getPageNum() - 1) * page.getPageSize();
        while (out.size() < page.getPageSize() && offset < total) {
            List<T> batch = taskQuery.listPage(offset, page.getPageSize());
            if (batch.isEmpty()) {
                break;
            }
            for (T row : batch) {
                // 凑满即止：续拉批按整页大小拉取，防止超填当页
                if (out.size() >= page.getPageSize()) {
                    break;
                }
                V vo = rowMapper.apply(row);
                if (vo != null) {
                    out.add(vo);
                }
            }
            offset += batch.size();
        }
        return out;
    }

    /** 待办行 → VO；脏行（审批单缺失/键异常）返回 null 由补拉循环跳过 */
    private TaskVo buildTodoVoOrNull(Task task, Map<String, BpmnBusinessType> configCache) {
        BpmnApproval approval = findApproval(task.getProcessInstanceId());
        return approval == null ? null : buildTaskVo(task, approval, configCache);
    }

    /** 已办行 → VO；脏行跳过语义同上 */
    private TaskDoneVo buildDoneVoOrNull(HistoricTaskInstance task, Map<String, BpmnBusinessType> configCache) {
        BpmnApproval approval = findApproval(task.getProcessInstanceId());
        return approval == null ? null : buildDoneVo(task, approval, configCache);
    }

    /** businessKey → bpmn_approval 快照；三重跳过各有 log.warn 痕迹（契约 §2.1/§2.2 防御态）：
     *  实例缺 businessKey / 键非数字 / 键无审批单行（旧轮残留，businessKey=旧业务 id）→ null 跳过 */
    private BpmnApproval findApproval(String processInstanceId) {
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance == null || instance.getBusinessKey() == null) {
            log.warn("任务关联的流程实例无 businessKey，跳过: {}", processInstanceId);
            return null;
        }
        Long approvalId;
        try {
            approvalId = Long.valueOf(instance.getBusinessKey());
        } catch (NumberFormatException e) {
            log.warn("businessKey 非审批单 id，跳过: {}", instance.getBusinessKey());
            return null;
        }
        BpmnApproval approval = approvalMapper.findById(approvalId);
        if (approval == null) {
            log.warn("businessKey 无对应审批单行，跳过: businessKey={}", instance.getBusinessKey());
        }
        return approval;
    }

    private TaskVo buildTaskVo(Task task, BpmnApproval approval, Map<String, BpmnBusinessType> configCache) {
        TaskVo vo = new TaskVo();
        vo.setTaskId(task.getId());
        vo.setApprovalId(String.valueOf(approval.getId()));
        vo.setBusinessType(approval.getBusinessType());
        vo.setTitle(approval.getTitle());
        vo.setApplyUser(approval.getApplyUser());
        vo.setCreateTime(BpmnDateUtil.format(task.getCreateTime()));
        enrichConfig(vo, approval, configCache);
        return vo;
    }

    private TaskDoneVo buildDoneVo(HistoricTaskInstance task, BpmnApproval approval,
                                   Map<String, BpmnBusinessType> configCache) {
        TaskDoneVo vo = new TaskDoneVo();
        vo.setTaskId(task.getId());
        vo.setApprovalId(String.valueOf(approval.getId()));
        vo.setBusinessType(approval.getBusinessType());
        vo.setTitle(approval.getTitle());
        vo.setApplyUser(approval.getApplyUser());
        vo.setCreateTime(BpmnDateUtil.format(task.getCreateTime()));
        enrichConfig(vo, approval, configCache);
        vo.setEndTime(BpmnDateUtil.format(task.getEndTime()));
        vo.setApprove(approveText(task.getProcessInstanceId()));
        vo.setComment(latestComment(task.getProcessInstanceId()));
        vo.setApprovalStatus(approval.getStatus() == null ? null : String.valueOf(approval.getStatus()));
        return vo;
    }

    /** 配置渲染回填：businessTypeName + detailPath（容忍版查询——配置缺失置 null 不炸列表，契约 §9 降级）；
     *  单次列表调用内按 type_code 记忆化（uk_type_code 等值查询，列表内不重复打库） */
    private void enrichConfig(TaskVo vo, BpmnApproval approval, Map<String, BpmnBusinessType> configCache) {
        String typeCode = approval.getBusinessType();
        BpmnBusinessType config = configCache.computeIfAbsent(typeCode,
                code -> businessTypeRegistry.findByTypeCodeOrNull(code));
        if (config == null) {
            log.error("业务类型配置缺失，businessTypeName/detailPath 置 null: {}", typeCode);
            return;
        }
        vo.setBusinessTypeName(config.getTypeName());
        // {businessKey} 渲染源 = 审批单 id（契约 §1 域语言，§2.1 示例 approvalId 12 → approval=12）
        vo.setDetailPath(businessTypeRegistry.renderDetailPath(config.getDetailRoute(),
                String.valueOf(approval.getId())));
    }

    /** 办理结果由实例 endActivityId 判定（endApprove→"true"/endReject→"false"；未知 null） */
    private String approveText(String processInstanceId) {
        HistoricProcessInstance instance = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(processInstanceId)
                .singleResult();
        if (instance == null) {
            return null;
        }
        if (ACTIVITY_END_APPROVE.equals(instance.getEndActivityId())) {
            return "true";
        }
        if (ACTIVITY_END_REJECT.equals(instance.getEndActivityId())) {
            return "false";
        }
        return null;
    }

    /** 实例最新一条审批意见（ACT_HI_COMMENT，时间序末位） */
    private String latestComment(String processInstanceId) {
        List<Comment> comments = taskService.getProcessInstanceComments(processInstanceId);
        if (comments == null || comments.isEmpty()) {
            return null;
        }
        Comment latest = comments.get(comments.size() - 1);
        return latest.getFullMessage();
    }
}
