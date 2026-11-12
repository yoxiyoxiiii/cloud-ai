package com.cloudai.task.service;

import com.cloudai.task.convert.TaskConvert;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.vo.TaskDetailVo;
import com.cloudai.task.vo.TaskVo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 实体→VO 转换单测：字段逐一映射（taskId 主键命名、执行明细仅 Detail 层）。
 */
class TaskConvertTest {

    private AsyncTask fullTask() {
        AsyncTask task = new AsyncTask();
        task.setId(11L);
        task.setTaskType("demo-batch");
        task.setBizKey("order-11");
        task.setStatus(2);
        task.setProgress(100);
        task.setPhase("完成");
        task.setParams("{\"total\": 100}");
        task.setResult("{\"processed\": 100}");
        task.setErrorMsg(null);
        task.setCancelRequested(0);
        task.setRetryCount(1);
        task.setMaxRetries(3);
        task.setTimeoutSeconds(600);
        task.setExecutorId("host:9204");
        task.setSubmitBy("admin");
        task.setSubmitSource("cloud-system");
        task.setStartTime(LocalDateTime.of(2026, 11, 10, 10, 0));
        task.setFinishTime(LocalDateTime.of(2026, 11, 10, 10, 0, 30));
        task.setCreateTime(LocalDateTime.of(2026, 11, 10, 10, 0));
        task.setUpdateTime(LocalDateTime.of(2026, 11, 10, 10, 0, 30));
        return task;
    }

    @Test
    void toVo_mapsListLayerFields() {
        TaskVo vo = TaskConvert.toVo(fullTask());
        assertThat(vo.getTaskId()).isEqualTo(11L);
        assertThat(vo.getTaskType()).isEqualTo("demo-batch");
        assertThat(vo.getBizKey()).isEqualTo("order-11");
        assertThat(vo.getStatus()).isEqualTo(2);
        assertThat(vo.getProgress()).isEqualTo(100);
        assertThat(vo.getPhase()).isEqualTo("完成");
        assertThat(vo.getErrorMsg()).isNull();
        assertThat(vo.getRetryCount()).isEqualTo(1);
        assertThat(vo.getMaxRetries()).isEqualTo(3);
        assertThat(vo.getSubmitBy()).isEqualTo("admin");
        assertThat(vo.getSubmitSource()).isEqualTo("cloud-system");
        assertThat(vo.getCreateTime()).isNotNull();
        assertThat(vo.getFinishTime()).isNotNull();
    }

    @Test
    void toDetailVo_extendsWithExecutionDetail() {
        TaskDetailVo vo = TaskConvert.toDetailVo(fullTask());
        assertThat(vo.getTaskId()).isEqualTo(11L);
        assertThat(vo.getParams()).isEqualTo("{\"total\": 100}");
        assertThat(vo.getResult()).isEqualTo("{\"processed\": 100}");
        assertThat(vo.getTimeoutSeconds()).isEqualTo(600);
        assertThat(vo.getCancelRequested()).isZero();
        assertThat(vo.getExecutorId()).isEqualTo("host:9204");
    }
}
