package com.cloudai.task.job;

import com.cloudai.task.alert.TaskAlertService;
import com.cloudai.task.config.TaskProperties;
import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务中心清扫任务（xxl-job admin 任务 id 13，CRON 每分钟，设计 §5.4）。
 * 单趟两件事：① 僵尸 RUNNING 兜底判死（超时+300s 宽限仍执行中=本地看门狗漏网，覆盖多实例）
 * ② 终态行保留期物理清理（分批 LIMIT，每分钟自然推进）。
 * 异常 log.error 后上抛（admin 红记录可见，无自动重试——下一分钟调度轮即自然重试）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskSweepJobHandler {

    /** 僵尸判死单趟上限（RUNNING 行数=并发上限量级，200 足够） */
    private static final int ZOMBIE_BATCH = 200;

    /** 物理清理单趟批量（每分钟一茬自然推进） */
    private static final int DELETE_BATCH = 200;

    /** 判死 error_msg 文案（超时兜底形态） */
    private static final String ZOMBIE_REASON = "执行超时（僵尸兜底）";

    private final AsyncTaskMapper mapper;
    private final TaskAlertService alertService;
    private final TaskProperties properties;

    /** 清扫单趟：僵尸判死 + 过期终态清理（薄壳编排，逻辑在两个私有方法） */
    @XxlJob("taskSweepJobHandler")
    public void taskSweepJobHandler() throws Exception {
        try {
            int zombies = sweepZombies();
            int deleted = sweepExpired();
            XxlJobHelper.log("task sweep done: zombies=" + zombies + ", deleted=" + deleted);
        } catch (Exception e) {
            log.error("任务清扫单趟异常", e);
            XxlJobHelper.log("task sweep failed: " + e.getMessage());
            throw e;
        }
    }

    /** 僵尸兜底：判死并发告警（首次终态失败判定与引擎超时路径同款——retry_count<=max 才发） */
    private int sweepZombies() {
        List<AsyncTask> zombies = mapper.listZombieRunning(ZOMBIE_BATCH);
        for (AsyncTask zombie : zombies) {
            int affected = mapper.updateTerminal(zombie.getId(), AsyncTask.StatusEnum.FAILED.getCode(),
                    ZOMBIE_REASON, null);
            if (affected > 0) {
                log.error("僵尸任务判死: id={}, type={}, start={}, timeout={}s",
                        zombie.getId(), zombie.getTaskType(), zombie.getStartTime(), zombie.getTimeoutSeconds());
                if (zombie.getRetryCount() != null && zombie.getMaxRetries() != null
                        && zombie.getRetryCount() <= zombie.getMaxRetries()) {
                    alertService.alertTaskFailed(zombie, ZOMBIE_REASON);
                }
            }
        }
        return zombies.size();
    }

    /** 终态行保留期物理清理（待处理/执行中行永不删） */
    private int sweepExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        return mapper.deleteTerminalBefore(cutoff, DELETE_BATCH);
    }
}
