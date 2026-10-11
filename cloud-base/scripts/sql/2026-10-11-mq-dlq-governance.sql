-- =====================================================================
-- MQ 死信治理（DLQ 监听落库 + 人工重投闭环，2026-10-11）
-- 设计 docs/superpowers/specs/2026-10-11-mq-dlq-governance-design.md（D1-D12，DDL §4 原文）
-- 契约 docs/superpowers/contracts/2026-10-11-mq-dlq-governance-api.md
-- 四段：① mq_dlq_log 建表（cloud_system）② 字典种子 mq_dlq_status ③ 菜单种子 17 段
--       ④ xxl_job 任务种子 id 10（dlqLogCleanJobHandler，xxl_job 库）。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT 1 FROM information_schema.tables WHERE table_schema='cloud_system'
--        AND table_name='mq_dlq_log';                          -- 应 0 行（建表幂等口径=存在即停）
--   2) SELECT id FROM sys_menu WHERE id IN (17,171,172,173);   -- 应 0 行（含墓碑，物理判断 id 占用）
--   3) SELECT id FROM sys_dict_type WHERE dict_key='mq_dlq_status'; -- 应 0 行（含墓碑，物理判断占键）
--   4) SELECT id FROM xxl_job.xxl_job_info WHERE id = 10;      -- 应 0 行（固定主键不可重放）
-- 不可重放：xxl_job 段固定主键 id 10——重放前先 DELETE FROM xxl_job.xxl_job_info WHERE id = 10；
--   菜单段显式 id INSERT 不幂等（前置断言 2 防占用）；字典段 NOT EXISTS 幂等可重跑；
--   绑定段无防重——重跑撞 uk_role_menu 唯一键报错（数据不脏，基线 SELECT 全量式天然覆盖）。
-- 基线同步：cloud_system.sql 已同步表/字典/菜单段（绑定由基线 SELECT 全量式天然覆盖），
--   2026-10-09-xxl-job-init.sql 已同步任务段——新环境直接跑基线，无需本增量。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）进 OnlineSession 快照后
--   system:mqDlq:* 权限才生效（沿 bpmn-deploy-menu 先例头注）。
-- =====================================================================
USE cloud_system;

-- ① mq_dlq_log：MQ 死信治理台账（设计 §4 原文采用；落 cloud_system——watch 宿主与治理面同库，
--   与 mq_consume_dedup 同库使重投清行零跨库）
CREATE TABLE mq_dlq_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    msg_id           VARCHAR(64)  NOT NULL COMMENT '死信原消息 msgId（broker 全局唯一；uk 防 watch 重复投递双行；重投产生的新消息为新 msgId 落新行=重投代际链）',
    tx_no            VARCHAR(64)  NULL     COMMENT '事务消息流水号（原消息 userProperty TX_NO，可空；重投时恢复该属性；溯源 mq_tx_log 半消息审计）',
    born_topic       VARCHAR(64)  NOT NULL COMMENT '原 topic（重投目标，TX_APPROVAL_CREATE/APPROVAL_EVENT_NOTIFY）',
    born_group       VARCHAR(64)  NOT NULL COMMENT '死信来源消费组（DLQ topic 名 %DLQ% 前缀 strip 派生；重投前清 mq_consume_dedup 残留行的分组键）',
    watch_group      VARCHAR(64)  NOT NULL COMMENT 'watch 消费组（g_system_dlq_watch_*，落库行来源定位）',
    tag              VARCHAR(64)  NULL     COMMENT '消息 tag（可空；重投时拼 destination=topic:tag）',
    msg_key          VARCHAR(190) NOT NULL COMMENT '消息幂等键（与 DedupRocketMQListener.resolveMsgKey 同口径：KEYS 优先、缺省退化 msgId；重投清 dedup 行与恢复 KEYS header 的判定依据：msg_key≠msg_id 即原始 KEYS）',
    body             TEXT         NULL     COMMENT '消息体原文（UTF-8 解码；超 60000 字节截断——契约消息均为小 JSON，防御上限）',
    reconsume_times  INT          NOT NULL DEFAULT 0 COMMENT '死信时已重试次数（maxReconsumeTimes=3，实测 4：1 次首发+3 次重试，B7 实证）',
    born_time        DATETIME     NOT NULL COMMENT '消息产生时间（bornTimestamp，业务发生时间）',
    dlq_time         DATETIME     NOT NULL COMMENT '进死信时间（storeTimestamp，DLQ 落 broker 时间，列表主排序与清理锚点）',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '处置状态（0待处理 1已重投 2已忽略 3已放弃——resend_count 达上限自动置 3；实体 StatusEnum）',
    resend_count     INT          NOT NULL DEFAULT 0 COMMENT '重投次数（上限 resend-max=5）',
    last_resend_time DATETIME     NULL     COMMENT '最近重投时间',
    operate_account  VARCHAR(64)  NULL     COMMENT '处置操作人（重投/忽略/重置操作账号；watch 落库不填——非处置动作）',
    operate_time     DATETIME     NULL     COMMENT '最近处置时间',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（watch 落库时间）',
    update_time      DATETIME     NULL     COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_status_dlq_time (status, dlq_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 死信治理台账（watch 监听 %DLQ% 落库；人工重投/忽略/重置留痕；终态行 N 天后定时清理，待处理行永不清）';
-- 索引取舍：uk_msg_id 命中 watch 落库幂等（每次消费一次插入冲突判定，uk 即约束即索引）；
--   idx_status_dlq_time 命中列表主路径 WHERE status=? ORDER BY dlq_time DESC（默认待处理+时间倒序）
--   与清理 DELETE WHERE status IN (1,2,3) AND dlq_time<?（复合索引首列 IN 列表多 range 扫描，MySQL 5.7 支持）；
--   born_group/msg_key 过滤走回表（人工级行数，量级远低于分页扫描代价，不单独建索引）；
--   无按 create_time 查询路径不建（沿 mq_tx_log 先例）。uk 单列 64*4=256B 无长度风险。
-- 逻辑删除：无 deleted 列——沿 mq_tx_log/mq_consume_dedup 先例（MQ 审计表物理清理，无 @TableLogic 语义）。

-- ② 字典种子 mq_dlq_status（契约 §2，is_builtin=1；label 文案锁定契约）
INSERT INTO sys_dict_type (dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT 'MQ死信状态', 'mq_dlq_status', 0, 1, 'system', NOW(), 'system', NOW() FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_key = 'mq_dlq_status');

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, v.label, v.value, v.sort, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t
  JOIN (SELECT '待处理' AS label, '0' AS value, 1 AS sort UNION ALL
        SELECT '已重投', '1', 2 UNION ALL
        SELECT '已忽略', '2', 3 UNION ALL
        SELECT '已放弃', '3', 4) v
  WHERE t.dict_key = 'mq_dlq_status' AND t.deleted = 0
    AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = v.value);

-- ③ 菜单种子（契约 §5：系统管理 id=10 下 17 死信治理 C + 171/172/173 按钮级，全 is_builtin=1；
--   icon=Message 为契约指定）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(17,  10, '死信治理', 'system:mqDlq:list',   'C', '/system/mq-dlq', 'Message', 7, 1, NOW()),
(171, 17, '重投',     'system:mqDlq:resend', 'F', '', '', 1, 1, NOW()),
(172, 17, '忽略',     'system:mqDlq:ignore', 'F', '', '', 2, 1, NOW()),
(173, 17, '重置',     'system:mqDlq:reset',  'F', '', '', 3, 1, NOW());

-- admin 角色绑定（增量显式 id 列表——基线 sys_role_menu 的 SELECT 全量式天然覆盖）
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (17, 171, 172, 173);

-- ④ xxl-job 任务种子：dlqLogCleanJobHandler（设计 D10——错峰 03:00 两表清理，每日 03:30；
--   组 3=cloud-system；trigger_status=1 显式列（表默认 0=停止）；glue_updatetime=now()（NULL 即 NPE 卡 pending）；
--   不可重放：重放先 DELETE FROM xxl_job.xxl_job_info WHERE id = 10）
USE `xxl_job`;

INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`, `trigger_status`)
VALUES (10, 3, 'system-dlq-log-clean', now(), now(), 'cloudai', '', 'CRON', '0 30 3 * * ?',
        'DO_NOTHING', 'FIRST', 'dlqLogCleanJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1);

commit;
