package com.cloudai.aggregate.service;

import com.cloudai.bpmn.api.client.BpmnWorkbenchClient;
import com.cloudai.bpmn.api.domain.ApprovalVo;
import com.cloudai.bpmn.api.domain.TaskVo;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.aggregate.security.AggregateUserContext;
import com.cloudai.aggregate.vo.ApprovalSectionVo;
import com.cloudai.aggregate.vo.NotifySectionVo;
import com.cloudai.aggregate.vo.SectionStatusEnum;
import com.cloudai.aggregate.vo.TodoSectionVo;
import com.cloudai.aggregate.vo.WorkbenchVo;
import com.cloudai.system.api.client.NotifyUserClient;
import com.cloudai.system.api.domain.NotifySummaryVo;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.Supplier;

/**
 * 工作台三段编排：身份校验 → 段准入预判 → 并行三段（各自独立 try/catch）→ 装配。
 * 段式失败状态：任何一段异常不外抛、不影响其它段与整体 code=200（契约 §2）。
 * 下游复用外部用户面，X-User-* 五头经 ThreadLocal 上下文透传（并行线程不继承请求上下文）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkbenchService {

    /** todo 段下游 perms（契约 §5：段与权限对应关系冻结，预判与下游 @PreAuthorize 同源同判） */
    private static final String PERM_TASK_LIST = "bpmn:task:list";

    /** approval 段下游 perms */
    private static final String PERM_APPROVAL_LIST = "bpmn:approval:list";

    /** 段取数参数固定（契约 §1.1：聚合服务内写死不暴露给前端，段参数扩展留给未来轮） */
    private static final int FIXED_PAGE_NUM = 1;
    private static final int FIXED_PAGE_SIZE = 5;

    /** Feign 调用异常段文案（契约 §2 转译表：非降级异常的兜底文案） */
    private static final String DOWNSTREAM_CALL_FAILED_MSG = "下游调用异常";

    private final BpmnWorkbenchClient bpmnWorkbenchClient;
    private final NotifyUserClient notifyUserClient;
    private final ThreadPoolExecutor sectionExecutor;

    /** 工作台三段聚合入口（Controller 出参 WorkbenchVo 直达，段内自述状态） */
    public WorkbenchVo workbench() {
        Map<String, String> headers = requireUserHeaders();
        return buildSections(headers);
    }

    /** 读请求头构建不可变五头 Map；缺 X-User-Id（绕过网关直连服务端口）→ 401 */
    private Map<String, String> requireUserHeaders() {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        HttpServletRequest request = attrs == null ? null : attrs.getRequest();
        String userId = request == null ? null : request.getHeader(SecurityConstants.HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED);
        }
        Map<String, String> headers = new HashMap<>();
        collectHeader(headers, SecurityConstants.HEADER_USER_ID, userId);
        collectHeader(headers, SecurityConstants.HEADER_USER_ACCOUNT,
                request.getHeader(SecurityConstants.HEADER_USER_ACCOUNT));
        collectHeader(headers, SecurityConstants.HEADER_USER_PERMS,
                request.getHeader(SecurityConstants.HEADER_USER_PERMS));
        collectHeader(headers, SecurityConstants.HEADER_USER_ROLE_IDS,
                request.getHeader(SecurityConstants.HEADER_USER_ROLE_IDS));
        collectHeader(headers, SecurityConstants.HEADER_USER_DEPT_IDS,
                request.getHeader(SecurityConstants.HEADER_USER_DEPT_IDS));
        return headers;
    }

    /** 非空头才收（网关对空角色/空部门注入空串——跳过，转发语义与下游解析为空列表一致） */
    private void collectHeader(Map<String, String> headers, String name, String value) {
        if (value != null && !value.isEmpty()) {
            headers.put(name, value);
        }
    }

    /** 三段并行提交（各自带 set/clear 上下文包装）→ join 装配；finally 清主线程上下文 */
    private WorkbenchVo buildSections(Map<String, String> headers) {
        AggregateUserContext.set(headers);
        CompletableFuture<TodoSectionVo> todo;
        CompletableFuture<ApprovalSectionVo> approval;
        CompletableFuture<NotifySectionVo> notify;
        try {
            todo = CompletableFuture.supplyAsync(
                    () -> withUserContext(headers, this::buildTodoSection), sectionExecutor);
            approval = CompletableFuture.supplyAsync(
                    () -> withUserContext(headers, this::buildApprovalSection), sectionExecutor);
            notify = CompletableFuture.supplyAsync(
                    () -> withUserContext(headers, this::buildNotifySection), sectionExecutor);
            return assembleWorkbench(todo.join(), approval.join(), notify.join());
        } finally {
            AggregateUserContext.clear();
        }
    }

    /** 段任务上下文包装：开头 set、结束 clear（CallerRuns 退化到主线程执行时同样成立） */
    private <T> T withUserContext(Map<String, String> headers, Supplier<T> task) {
        AggregateUserContext.set(headers);
        try {
            return task.get();
        } finally {
            AggregateUserContext.clear();
        }
    }

    /** todo 段：准入预判（无 bpmn:task:list 不发下游，省掉注定 403 的调用）→ 取数 → 转译装配 */
    private TodoSectionVo buildTodoSection() {
        if (!hasPerm(PERM_TASK_LIST)) {
            return todoSection(SectionStatusEnum.NO_PERM, ErrorCode.FORBIDDEN.getMsg(), null);
        }
        try {
            R<PageResult<TaskVo>> resp = bpmnWorkbenchClient.todo(FIXED_PAGE_NUM, FIXED_PAGE_SIZE);
            return todoSection(statusOf(resp), msgOf(resp), successData(resp));
        } catch (Exception e) {
            log.error("工作台 todo 段下游调用失败", e);
            return todoSection(SectionStatusEnum.DOWNSTREAM_ERROR, DOWNSTREAM_CALL_FAILED_MSG, null);
        }
    }

    /** approval 段：准入预判（无 bpmn:approval:list 不发下游）→ 取数 → 转译装配 */
    private ApprovalSectionVo buildApprovalSection() {
        if (!hasPerm(PERM_APPROVAL_LIST)) {
            return approvalSection(SectionStatusEnum.NO_PERM, ErrorCode.FORBIDDEN.getMsg(), null);
        }
        try {
            R<PageResult<ApprovalVo>> resp = bpmnWorkbenchClient.page(FIXED_PAGE_NUM, FIXED_PAGE_SIZE);
            return approvalSection(statusOf(resp), msgOf(resp), successData(resp));
        } catch (Exception e) {
            log.error("工作台 approval 段下游调用失败", e);
            return approvalSection(SectionStatusEnum.DOWNSTREAM_ERROR, DOWNSTREAM_CALL_FAILED_MSG, null);
        }
    }

    /** notify 段：登录即可无 perms，恒发起（无 7001 路径）→ 取数 → 转译装配 */
    private NotifySectionVo buildNotifySection() {
        try {
            R<NotifySummaryVo> resp = notifyUserClient.summary();
            return notifySection(statusOf(resp), msgOf(resp), successData(resp));
        } catch (Exception e) {
            log.error("工作台 notify 段下游调用失败", e);
            return notifySection(SectionStatusEnum.DOWNSTREAM_ERROR, DOWNSTREAM_CALL_FAILED_MSG, null);
        }
    }

    /** 段准入预判：X-User-Perms 与下游 authorities 同源（网关同注入），预判与下游判定等价 */
    private boolean hasPerm(String perm) {
        Map<String, String> headers = AggregateUserContext.get();
        String perms = headers == null ? null : headers.get(SecurityConstants.HEADER_USER_PERMS);
        if (perms == null || perms.isBlank()) {
            return false;
        }
        return Arrays.stream(perms.split(SecurityConstants.PERMS_SEPARATOR))
                .map(String::trim)
                .anyMatch(perm::equals);
    }

    /** 下游 code → 段状态转译（契约 §2 表）：200→200 / 403→7001 / 其它→7002 */
    private SectionStatusEnum statusOf(R<?> resp) {
        if (resp.getCode() == ErrorCode.SUCCESS.getCode()) {
            return SectionStatusEnum.OK;
        }
        return resp.getCode() == ErrorCode.FORBIDDEN.getCode()
                ? SectionStatusEnum.NO_PERM : SectionStatusEnum.DOWNSTREAM_ERROR;
    }

    /** 段 msg：正常取 SUCCESS 文案、403 沿下游 FORBIDDEN 文案（同源同字）、失败取下游 msg */
    private String msgOf(R<?> resp) {
        if (resp.getCode() == ErrorCode.SUCCESS.getCode()) {
            return ErrorCode.SUCCESS.getMsg();
        }
        return resp.getCode() == ErrorCode.FORBIDDEN.getCode()
                ? ErrorCode.FORBIDDEN.getMsg() : resp.getMsg();
    }

    /** 段 data：仅正常段透传下游数据，失败段恒 null（契约 §1.2，前端不解引用） */
    private <D> D successData(R<D> resp) {
        return resp.getCode() == ErrorCode.SUCCESS.getCode() ? resp.getData() : null;
    }

    private TodoSectionVo todoSection(SectionStatusEnum status, String msg, PageResult<TaskVo> data) {
        TodoSectionVo vo = new TodoSectionVo();
        vo.setStatus(status.getCode());
        vo.setMsg(msg);
        vo.setData(data);
        return vo;
    }

    private ApprovalSectionVo approvalSection(SectionStatusEnum status, String msg, PageResult<ApprovalVo> data) {
        ApprovalSectionVo vo = new ApprovalSectionVo();
        vo.setStatus(status.getCode());
        vo.setMsg(msg);
        vo.setData(data);
        return vo;
    }

    private NotifySectionVo notifySection(SectionStatusEnum status, String msg, NotifySummaryVo data) {
        NotifySectionVo vo = new NotifySectionVo();
        vo.setStatus(status.getCode());
        vo.setMsg(msg);
        vo.setData(data);
        return vo;
    }

    /** 三段装进聚合出参（段间完全隔离：任一段失败不改其它段） */
    private WorkbenchVo assembleWorkbench(TodoSectionVo todo, ApprovalSectionVo approval, NotifySectionVo notify) {
        WorkbenchVo vo = new WorkbenchVo();
        vo.setTodo(todo);
        vo.setApproval(approval);
        vo.setNotify(notify);
        return vo;
    }
}
