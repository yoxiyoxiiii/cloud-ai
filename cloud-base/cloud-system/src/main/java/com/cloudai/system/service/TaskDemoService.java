package com.cloudai.system.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.DemoTaskSubmitRequest;
import com.cloudai.task.api.client.TaskSubmitClient;
import com.cloudai.task.api.domain.TaskSubmitRequest;
import com.cloudai.task.api.domain.TaskSubmitResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 演示任务提交（契约 2026-11-10-task-center-api §7）：模拟业务模块经 Feign 走
 * cloud-task /inner/task/submit。提交人取当前登录账号（inner 面无登录态，显式传字段不透传 header）。
 * 远端错误码/降级 msg 原样透出（HTTP 200 + body code/msg），消费方零翻译。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskDemoService {

    /** 演示 handler 类型码（cloud-task DemoBatchHandler 注册键） */
    private static final String DEMO_TASK_TYPE = "demo-batch";

    /** 提交来源标识（任务列表「提交方」审计） */
    private static final String SUBMIT_SOURCE = "cloud-system";

    /** 任务参数非法（task 段错误码，契约 §9——与 cloud-task 侧同码） */
    private static final int ERR_PARAMS_INVALID = 6002;

    /** total 范围与默认（契约 §7） */
    private static final int TOTAL_DEFAULT = 100;
    private static final int TOTAL_MIN = 1;
    private static final int TOTAL_MAX = 10000;

    /** failPercent 范围与默认（契约 §7） */
    private static final int FAIL_PERCENT_DEFAULT = 0;
    private static final int FAIL_PERCENT_MIN = 0;
    private static final int FAIL_PERCENT_MAX = 100;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final TaskSubmitClient taskSubmitClient;

    /** 提交演示任务：本地范围校验（6002）→ Feign 提交 → 结果码非 200 原样抛出透出 */
    public TaskSubmitResult submit(DemoTaskSubmitRequest req) {
        int total = normalized(req == null ? null : req.getTotal(), TOTAL_DEFAULT, TOTAL_MIN, TOTAL_MAX, "total");
        int failPercent = normalized(req == null ? null : req.getFailPercent(),
                FAIL_PERCENT_DEFAULT, FAIL_PERCENT_MIN, FAIL_PERCENT_MAX, "failPercent");
        R<TaskSubmitResult> response;
        try {
            response = taskSubmitClient.submit(buildSubmitRequest(total, failPercent));
        } catch (Exception e) {
            // 熔断关闭/未包装时的二层兜底（fallbackFactory 生效时不进此分支）
            log.error("演示任务提交 Feign 调用失败: total={}, failPercent={}", total, failPercent, e);
            throw new BusinessException(ErrorCode.BUSINESS_ERROR.getCode(), "cloud-task 服务不可用");
        }
        if (response == null || response.getCode() != ErrorCode.SUCCESS.getCode()) {
            // 降级（1002 msg）与远端业务错误（6001/6002/6003）统一原样透出
            int code = response == null ? ErrorCode.BUSINESS_ERROR.getCode() : response.getCode();
            String msg = response == null ? "cloud-task 服务不可用" : response.getMsg();
            throw new BusinessException(code, msg);
        }
        return response.getData();
    }

    /** 数值归一：null 补默认，越界 6002（本地拦截，不打到远端再退回） */
    private int normalized(Integer value, int defaultValue, int min, int max, String field) {
        if (value == null) {
            return defaultValue;
        }
        if (value < min || value > max) {
            throw new BusinessException(ERR_PARAMS_INVALID,
                    field + " 越界（" + min + "-" + max + "）: " + value);
        }
        return value;
    }

    /** 组装服务间提交请求：params 由两字段序列化为 JSON 对象文本（handler 自解析） */
    private TaskSubmitRequest buildSubmitRequest(int total, int failPercent) {
        TaskSubmitRequest submit = new TaskSubmitRequest();
        submit.setTaskType(DEMO_TASK_TYPE);
        submit.setParams(toParamsJson(total, failPercent));
        submit.setSubmitterAccount(SecurityUtils.currentAccount());
        submit.setSubmitSource(SUBMIT_SOURCE);
        return submit;
    }

    /** params JSON 构造（变量键与 DemoBatchHandler.DemoParams 对齐；序列化失败按不可达处理） */
    private String toParamsJson(int total, int failPercent) {
        try {
            return JSON.writeValueAsString(new DemoParams(total, failPercent));
        } catch (Exception e) {
            log.error("演示任务 params 序列化失败: total={}, failPercent={}", total, failPercent, e);
            throw new BusinessException(ERR_PARAMS_INVALID, "任务参数构造失败");
        }
    }

    /** params 载荷（Jackson 反序列化目标，字段名即 JSON 键） */
    private record DemoParams(int total, int failPercent) {
    }
}
