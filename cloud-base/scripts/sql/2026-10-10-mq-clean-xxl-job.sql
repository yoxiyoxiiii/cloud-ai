-- =====================================================================
-- MQ 两表清理任务种子（2026-10-10 迁移轮：MqTableCleanJob @Scheduled → xxl-job，@Scheduled 存量清零收官）
-- 前置：2026-10-09-xxl-job-init.sql 已执行。
-- 不可重放：固定主键 id 8/9——重放需先 DELETE FROM xxl_job_info WHERE id IN (8, 9)。
-- 形态：两组各一任务（每实例清自己库的表），handler 名两组同名（demo 任务已实证同名合法）；
--       CRON 保持原 @Scheduled 默认节奏每日 03:00；clean-cron 配置键退役（治理权移交 admin）。
-- =====================================================================
USE `xxl_job`;

INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`, `trigger_status`)
VALUES (8, 3, 'system-mq-table-clean', now(), now(), 'cloudai', '', 'CRON', '0 0 3 * * ?',
        'DO_NOTHING', 'FIRST', 'mqTableCleanJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1),
       (9, 4, 'bpmn-mq-table-clean',   now(), now(), 'cloudai', '', 'CRON', '0 0 3 * * ?',
        'DO_NOTHING', 'FIRST', 'mqTableCleanJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1);

commit;
