package com.cloudai.task.service;

import com.cloudai.task.api.domain.TaskSubmitRequest;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import com.cloudai.task.runtime.TaskHandlerRegistry;
import com.cloudai.common.core.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/**
 * 任务提交（契约 2026-11-10-task-center-api §2）：类型校验 6001 → 参数校验 6002 →
 * 幂等查重 6003 → 落库（DuplicateKey 并发兜底 6003）。单表单语句零事务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskSubmitService {

    /** 错误码 6xxx task 段（契约 §9） */
    private static final int ERR_TYPE_UNREGISTERED = 6001;
    private static final int ERR_PARAMS_INVALID = 6002;
    private static final int ERR_BIZ_KEY_CONFLICT = 6003;

    /** maxRetries 默认与范围（契约 §2） */
    private static final int DEFAULT_MAX_RETRIES = 3;
    private static final int MAX_RETRIES_MIN = 0;
    private static final int MAX_RETRIES_MAX = 10;

    /** timeoutSeconds 默认与范围（契约 §2） */
    private static final int DEFAULT_TIMEOUT_SECONDS = 600;
    private static final int TIMEOUT_MIN = 10;
    private static final int TIMEOUT_MAX = 86400;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final AsyncTaskMapper mapper;
    private final TaskHandlerRegistry registry;

    /** 提交任务：校验链 → 落库（status=0 排队），返回任务 ID */
    public Long submit(TaskSubmitRequest req) {
        if (!registry.containsType(req.getTaskType())) {
            throw new BusinessException(ERR_TYPE_UNREGISTERED, "任务类型未注册: " + req.getTaskType());
        }
        assertParamsValid(req);
        assertBizKeyFree(req);
        AsyncTask task = buildTask(req);
        try {
            mapper.save(task);
        } catch (DuplicateKeyException e) {
            // 并发双击/重试风暴的 TOCTOU 兜底：查重通过后仍可能撞 uk_type_biz
            log.error("任务提交唯一键冲突: type={}, bizKey={}", req.getTaskType(), req.getBizKey(), e);
            throw new BusinessException(ERR_BIZ_KEY_CONFLICT, "幂等键已存在");
        }
        return task.getId();
    }

    /** params 为 null 合法（列 DEFAULT NULL）；非 null 必须可解析且为 JSON 对象，数值范围越界同码 */
    private void assertParamsValid(TaskSubmitRequest req) {
        if (req.getParams() != null) {
            try {
                JsonNode node = JSON.readTree(req.getParams());
                if (!node.isObject()) {
                    throw new BusinessException(ERR_PARAMS_INVALID, "params 必须为 JSON 对象文本");
                }
            } catch (BusinessException e) {
                throw e;
            } catch (Exception e) {
                throw new BusinessException(ERR_PARAMS_INVALID, "params 不是合法 JSON: " + req.getParams());
            }
        }
        if (req.getMaxRetries() != null
                && (req.getMaxRetries() < MAX_RETRIES_MIN || req.getMaxRetries() > MAX_RETRIES_MAX)) {
            throw new BusinessException(ERR_PARAMS_INVALID, "maxRetries 越界（0-10）: " + req.getMaxRetries());
        }
        if (req.getTimeoutSeconds() != null
                && (req.getTimeoutSeconds() < TIMEOUT_MIN || req.getTimeoutSeconds() > TIMEOUT_MAX)) {
            throw new BusinessException(ERR_PARAMS_INVALID,
                    "timeoutSeconds 越界（10-86400）: " + req.getTimeoutSeconds());
        }
    }

    /** 幂等键查重（uk_type_biz 等值）：任意状态已存在即 6003，msg 携带既有任务 ID */
    private void assertBizKeyFree(TaskSubmitRequest req) {
        if (req.getBizKey() == null || req.getBizKey().isBlank()) {
            return;
        }
        AsyncTask existing = mapper.findByTypeBiz(req.getTaskType(), req.getBizKey());
        if (existing != null) {
            throw new BusinessException(ERR_BIZ_KEY_CONFLICT, "幂等键已存在，任务ID: " + existing.getId());
        }
    }

    /** 组装落库实体：排队态零进度，默认值兜底，审计时间由 XML 的 NOW() 落库 */
    private AsyncTask buildTask(TaskSubmitRequest req) {
        AsyncTask task = new AsyncTask();
        task.setTaskType(req.getTaskType());
        task.setBizKey(req.getBizKey());
        task.setStatus(AsyncTask.StatusEnum.QUEUED.getCode());
        task.setProgress(0);
        task.setParams(req.getParams());
        task.setRetryCount(0);
        task.setMaxRetries(req.getMaxRetries() == null ? DEFAULT_MAX_RETRIES : req.getMaxRetries());
        task.setTimeoutSeconds(req.getTimeoutSeconds() == null ? DEFAULT_TIMEOUT_SECONDS : req.getTimeoutSeconds());
        task.setSubmitBy(req.getSubmitterAccount());
        task.setSubmitSource(req.getSubmitSource());
        return task;
    }
}
