-- =====================================================================
-- 审批投影对账任务种子增量（2026-10-10 对账迁移轮：Reconciler @Scheduled → xxl-job CRON）
-- 前置：2026-10-09-xxl-job-init.sql 已执行（库与执行器组存在）。
-- 不可重放：固定主键 id 7——重放需先 DELETE FROM xxl_job_info WHERE id = 7。
-- 语义：调度节奏自 Nacos cloud.bpmn.projection.reconcile-interval-ms（键已退役）移交 admin CRON；
--       trigger_status=1 启动即调度（手工 INSERT 不算 trigger_next_time，默认 0 落在过去——admin 调度线程
--       首轮按 misfire DO_NOTHING 放弃并刷新到下一 CRON 点，不双跑，此后正常节奏）；
--       glue_updatetime 必须 now()（3.5.0 JobTrigger 解引用无空防护）。
-- =====================================================================
USE `xxl_job`;

INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`, `trigger_status`)
VALUES (7, 3, 'approval-projection-reconcile', now(), now(), 'cloudai', '', 'CRON', '0 * * * * ?',
        'DO_NOTHING', 'FIRST', 'approvalProjectionReconcileJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1);

commit;
