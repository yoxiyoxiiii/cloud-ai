package com.cloudai.task.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.task.api.domain.TaskSubmitRequest;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import com.cloudai.task.runtime.TaskHandlerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交校验链单测（契约 §2/§9）：6001 未注册 → 6002 参数（params 非 JSON 对象/数值越界）
 * → 6003 幂等键（查重与 DuplicateKey 并发兜底，msg 含既有 taskId）；缺省值兜底。
 */
@ExtendWith(MockitoExtension.class)
class TaskSubmitServiceTest {

    @Mock
    private AsyncTaskMapper mapper;
    @Mock
    private TaskHandlerRegistry registry;

    @InjectMocks
    private TaskSubmitService service;

    private TaskSubmitRequest request(String taskType, String params, String bizKey,
                                      Integer maxRetries, Integer timeoutSeconds) {
        TaskSubmitRequest req = new TaskSubmitRequest();
        req.setTaskType(taskType);
        req.setParams(params);
        req.setBizKey(bizKey);
        req.setMaxRetries(maxRetries);
        req.setTimeoutSeconds(timeoutSeconds);
        req.setSubmitterAccount("admin");
        req.setSubmitSource("cloud-system");
        return req;
    }

    @Test
    void unregisteredType_rejected6001() {
        when(registry.containsType("nope")).thenReturn(false);
        assertThatThrownBy(() -> service.submit(request("nope", null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6001);
        verify(mapper, never()).save(any());
    }

    @Test
    void invalidParams_rejected6002() {
        when(registry.containsType("demo-batch")).thenReturn(true);
        // 非 JSON
        assertThatThrownBy(() -> service.submit(request("demo-batch", "not-json", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6002);
        // JSON 但非对象（数组）
        assertThatThrownBy(() -> service.submit(request("demo-batch", "[1,2]", null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6002);
        // 数值越界
        assertThatThrownBy(() -> service.submit(request("demo-batch", null, null, 11, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6002);
        assertThatThrownBy(() -> service.submit(request("demo-batch", null, null, null, 5)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6002);
    }

    @Test
    void bizKeyConflict_rejected6003_withExistingTaskId() {
        when(registry.containsType("demo-batch")).thenReturn(true);
        AsyncTask existing = new AsyncTask();
        existing.setId(42L);
        when(mapper.findByTypeBiz("demo-batch", "order-1")).thenReturn(existing);
        assertThatThrownBy(() -> service.submit(request("demo-batch", null, "order-1", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6003)
                .hasMessageContaining("42");
    }

    @Test
    void concurrentDuplicateKey_rejected6003() {
        when(registry.containsType("demo-batch")).thenReturn(true);
        when(mapper.findByTypeBiz("demo-batch", "order-1")).thenReturn(null);
        when(mapper.save(any())).thenThrow(new DuplicateKeyException("uk_type_biz"));
        assertThatThrownBy(() -> service.submit(request("demo-batch", null, "order-1", null, null)))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 6003);
    }

    @Test
    void blankBizKey_skipsDedupProbe() {
        when(registry.containsType("demo-batch")).thenReturn(true);
        when(mapper.save(any())).thenAnswer(inv -> {
            ((AsyncTask) inv.getArgument(0)).setId(9L);
            return 1;
        });
        Long id = service.submit(request("demo-batch", "{\"total\": 10}", " ", null, null));
        assertThat(id).isEqualTo(9L);
        verify(mapper, never()).findByTypeBiz(any(), any());
    }

    /** 落库实体口径钉：排队态零进度、缺省 maxRetries=3/timeout=600、审计字段直传 */
    @Test
    void saveTask_defaultsApplied() {
        when(registry.containsType("demo-batch")).thenReturn(true);
        ArgumentCaptor<AsyncTask> captor = ArgumentCaptor.forClass(AsyncTask.class);
        service.submit(request("demo-batch", "{\"total\": 10}", null, null, null));
        verify(mapper).save(captor.capture());
        AsyncTask saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo(AsyncTask.StatusEnum.QUEUED.getCode());
        assertThat(saved.getProgress()).isZero();
        assertThat(saved.getRetryCount()).isZero();
        assertThat(saved.getMaxRetries()).isEqualTo(3);
        assertThat(saved.getTimeoutSeconds()).isEqualTo(600);
        assertThat(saved.getSubmitBy()).isEqualTo("admin");
        assertThat(saved.getSubmitSource()).isEqualTo("cloud-system");
        assertThat(saved.getTaskType()).isEqualTo("demo-batch");
    }
}
