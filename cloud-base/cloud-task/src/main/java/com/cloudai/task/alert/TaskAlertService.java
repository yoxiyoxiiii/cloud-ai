package com.cloudai.task.alert;

import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.NotifyClient;
import com.cloudai.system.api.domain.NotifySendResult;
import com.cloudai.system.api.domain.SendByCodeRequest;
import com.cloudai.task.entity.AsyncTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 任务失败站内信告警（契约 2026-11-10-task-center-api §11）：任务首次进入终态失败
 * （自动重试耗尽/超时/僵尸兜底）时按模板 code 发 admin 角色。
 * 软失败语义：Feign 降级/异常/业务失败仅 log.error 吞掉——告警不阻断任务终态推进、不重试。
 * 「首次」判定由调用方（引擎/清扫）以 retry_count<=max_retries 完成：手动重试会把
 * retry_count 顶过 max，再失败不再发（同任务一次）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskAlertService {

    /** 告警模板 code（sys_notify_template 种子 id 5） */
    static final String TEMPLATE_CODE = "task_fail_alert";

    /** 收件维度：2按角色 */
    private static final int RECEIVER_TYPE_ROLE = 2;

    /** admin 角色 id（告警对象拍板：提交人反查链路记演进项） */
    private static final long ROLE_ADMIN = 1L;

    /** reason 截断上限（防模板渲染后超出消息内容列宽 1000 触发 3046） */
    private static final int REASON_MAX_LEN = 200;

    private final NotifyClient notifyClient;

    /** 发送失败告警（吞一切异常，软失败） */
    public void alertTaskFailed(AsyncTask task, String reason) {
        try {
            SendByCodeRequest request = new SendByCodeRequest();
            request.setTemplateCode(TEMPLATE_CODE);
            request.setReceiverType(RECEIVER_TYPE_ROLE);
            request.setRoleIds(java.util.List.of(ROLE_ADMIN));
            request.setLinkUrl("/task/async");
            request.setVariables(Map.of(
                    "taskType", task.getTaskType(),
                    "taskId", String.valueOf(task.getId()),
                    "reason", truncate(reason)));
            R<NotifySendResult> resp = notifyClient.sendByCode(request);
            if (resp == null || resp.getCode() != ErrorCode.SUCCESS.getCode()) {
                log.error("任务失败告警发送未成功: taskId={}, type={}, resp={}",
                        task.getId(), task.getTaskType(), resp == null ? "null" : resp.getCode() + ":" + resp.getMsg());
            }
        } catch (Exception e) {
            log.error("任务失败告警发送异常（软失败，不重试）: taskId={}, type={}",
                    task.getId(), task.getTaskType(), e);
        }
    }

    private String truncate(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() <= REASON_MAX_LEN ? reason : reason.substring(0, REASON_MAX_LEN);
    }
}
