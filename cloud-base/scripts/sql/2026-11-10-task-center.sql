-- =====================================================================
-- 通用任务中心（2026-11-10 全栈轮）
-- 设计 docs/superpowers/specs/2026-11-10-task-center-design.md（§4 DDL 与索引 / §5.4 清扫任务）
-- 契约 docs/superpowers/contracts/2026-11-10-task-center-api.md（§10 菜单种子 / §11 通知模板）
-- 四段：① 建库 cloud_task + async_task DDL ② 通知模板 id 5 task_fail_alert ③ 菜单种子 40 段 5 行 ④ xxl-job 组 5 + 任务 13
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑；落库经 pymysql，charset utf8mb4 参数化）：
--   1) SHOW TABLES IN cloud_task LIKE 'async_task';                          -- 应 0 行（库可不存在）
--   2) SELECT id FROM cloud_system.sys_menu WHERE id IN (40,401,402,403,404); -- 应 0 行（含墓碑，物理判断 id 占用）
--   3) SELECT id FROM cloud_system.sys_notify_template WHERE id = 5;          -- 应 0 行
--   4) SELECT id FROM xxl_job.xxl_job_group WHERE id = 5;                     -- 应 0 行
--   5) SELECT id FROM xxl_job.xxl_job_info WHERE id = 13;                     -- 应 0 行
-- 不可重放：固定主键（菜单 40 段 / xxl_job 组 5 / 任务 13 / 模板 5）——重放需先 DELETE 对应行。
-- 基线同步：cloud_task.sql 已同步本表 DDL；cloud_system.sql 已同步菜单 40 段 + 模板 5
--   （绑定由基线 sys_role_menu 的 SELECT 全量式天然覆盖）；xxl-job 基线 2026-10-09-xxl-job-init.sql 已同步组 5 / 任务 13。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）进 OnlineSession 快照后 task:* 权限才生效。
-- =====================================================================

-- ---------- ① 任务库与流水表（设计 §4；索引取舍见设计 §4 逐条） ----------
-- 流水式表：任务行无软删语义，终态行到期物理清理（沿 mq_tx_log/sys_shared_secret 先例）——无 deleted 列；
-- uk_type_biz=提交幂等查重 + 并发 TOCTOU 兜底（biz_key 可 NULL：多 NULL 共存=不传键不查重；
--   同 key 终态后不可再提交新行——重跑走重试端点，契约 §2 注明）；
-- idx_status_create=秒级 pickup 认领扫描主路径（status=0 等值 + create_time 升序）+ 状态筛选分页；
-- idx_create_time=无筛选时默认时间倒序分页（idx_status_create 最左列非等值用不上）
CREATE DATABASE IF NOT EXISTS cloud_task DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE cloud_task;

CREATE TABLE async_task (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '任务ID（对外字符串化，全局 Jackson Long→String 口径）',
    task_type        VARCHAR(50)   NOT NULL COMMENT '任务类型码（handler 注册键，如 demo-batch）',
    biz_key          VARCHAR(100)  DEFAULT NULL COMMENT '业务幂等键（NULL=不查重；非空时与 task_type 库级唯一，防双击/重试风暴重复提交）',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0排队 1执行中 2成功 3失败(含超时) 4已取消',
    progress         INT           NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
    phase            VARCHAR(50)   DEFAULT NULL COMMENT '执行阶段描述（handler 上报，如 批处理中）',
    params           JSON          DEFAULT NULL COMMENT '任务参数（提交方 JSON 对象文本，handler 按类型自解析）',
    result           JSON          DEFAULT NULL COMMENT '成功结果摘要（handler 返回，JSON 文本）',
    error_msg        VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（异常摘要或超时说明；成功/取消为 NULL）',
    cancel_requested TINYINT       NOT NULL DEFAULT 0 COMMENT '协作式取消标志：1=已请求取消（handler 检查点生效后转取消终态）',
    retry_count      INT           NOT NULL DEFAULT 0 COMMENT '已重试次数（自动+手动共计数）',
    max_retries      INT           NOT NULL DEFAULT 3 COMMENT '最大自动重试次数（提交可指定；耗尽后失败为终态）',
    timeout_seconds  INT           NOT NULL DEFAULT 600 COMMENT '执行超时秒数（提交可指定；超时判失败）',
    executor_id      VARCHAR(64)   DEFAULT NULL COMMENT '认领执行器标识 host:port（多实例演进预留，CAS 认领写入口径）',
    submit_by        VARCHAR(50)   DEFAULT NULL COMMENT '提交人账号（业务方从登录态显式传入，审计用）',
    submit_source    VARCHAR(50)   DEFAULT NULL COMMENT '提交来源服务名（如 cloud-system，列表筛选用）',
    start_time       DATETIME      DEFAULT NULL COMMENT '开始执行时间（超时判定基准）',
    finish_time      DATETIME      DEFAULT NULL COMMENT '终态时间',
    create_time      DATETIME      DEFAULT NULL COMMENT '提交时间',
    update_time      DATETIME      DEFAULT NULL COMMENT '状态/进度更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (task_type, biz_key),
    KEY idx_status_create (status, create_time),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '异步任务（通用任务中心）';

-- ---------- ② 站内信内置告警模板（契约 §11：任务首次终态失败时按 code 取用发 admin 角色） ----------
USE cloud_system;

INSERT INTO sys_notify_template (id, code, name, title_template, content_template, type, level,
                                 variables_desc, is_builtin, create_by, create_time, update_by, update_time)
VALUES (5, 'task_fail_alert', '异步任务失败提醒', '异步任务失败提醒',
        '异步任务 ${taskType}（ID: ${taskId}）执行失败：${reason}，请前往任务中心处理。',
        2, 1, 'taskType=任务类型码；taskId=任务ID；reason=失败原因摘要', 1, 'admin', NOW(), 'admin', NOW());

-- ---------- ③ 菜单种子（契约 §10：顶级 M 40 + 页面 C 401 + 三按钮 F 402/403/404） ----------
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(40,  0,   '任务中心',   '',                    'M', '',          'Timer', 5, 1, NOW()),
(401, 40,  '异步任务',   'task:asyncTask:list', 'C', '/task/async', 'List', 1, 1, NOW()),
(402, 401, '取消任务',   'task:asyncTask:cancel', 'F', '', '', 1, 1, NOW()),
(403, 401, '重试任务',   'task:asyncTask:retry',  'F', '', '', 2, 1, NOW()),
(404, 401, '提交演示任务', 'task:demo:submit',    'F', '', '', 3, 1, NOW());

-- admin 角色绑定（基线 sys_role_menu 的 SELECT 全量式天然覆盖；增量环境跑本段）
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (40, 401, 402, 403, 404);

-- ---------- ④ xxl-job 执行器组与清扫任务（沿 2026-11-09-inner-secret-rotation.sql 段④ 形态） ----------
-- 组 5 app_name=cloud-task 与 Nacos xxl.job.executor.appname 一致（自动注册 address_type=0）；
-- 任务 13 CRON 每分钟单趟=僵尸 RUNNING 兜底判死 + 终态行保留期物理清理；
-- handler 对应 cloud-task job 包 @XxlJob("taskSweepJobHandler")；retry 0（失败自然重试=下一分钟调度轮）；
-- 重放需先 DELETE 组 5 / 任务 13
USE `xxl_job`;

INSERT INTO `xxl_job_group`(`id`, `app_name`, `name`, `address_type`, `address_list`, `access_token`, `update_time`)
VALUES (5, 'cloud-task', 'cloud-task执行器', 0, NULL, 'default_token', now());

INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`, `trigger_status`)
VALUES (13, 5, 'task-center-sweep', now(), now(), 'cloudai', '', 'CRON', '0 * * * * ?',
        'DO_NOTHING', 'FIRST', 'taskSweepJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1);

commit;
