-- =====================================================================
-- inner 面共享密钥生命周期管理（2026-11-09 全栈轮）
-- 设计 docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md（§7 DDL 与索引 / §5 任务）
-- 契约 docs/superpowers/contracts/2026-11-09-inner-secret-admin-api.md（§6 种子）
--      docs/superpowers/contracts/2026-11-09-inner-access-secret-api.md（v2 运行时分发）
-- 四段：① sys_shared_secret DDL ② 站内信内置告警模板 id 4 ③ 菜单种子 22 段 3 行 ④ xxl-job 轮换任务种子（id 12）
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑；落库经 pymysql，charset utf8mb4 参数化）：
--   1) SHOW TABLES IN cloud_system LIKE 'sys_shared_secret';                  -- 应 0 行（表不存在）
--   2) SELECT id FROM cloud_system.sys_menu WHERE id IN (22, 200, 201);       -- 应 0 行（含墓碑，物理判断 id 占用）
--   3) SELECT id FROM cloud_system.sys_notify_template WHERE id = 4;          -- 应 0 行
--   4) SELECT id FROM xxl_job.xxl_job_info WHERE id = 12;                     -- 应 0 行
-- 不可重放：固定主键（菜单 22 段 / xxl_job 任务 id 12 / 模板 id 4）——重放需先 DELETE 对应行。
-- 基线同步：cloud_system.sql 已同步本表 DDL + 菜单 22 段 + 模板 4（绑定由基线 sys_role_menu
--   的 SELECT 全量式天然覆盖）；xxl-job 基线 2026-10-09-xxl-job-init.sql 已同步 id 12 种子段。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）进 OnlineSession 快照后 system:innerSecret:* 权限才生效。
-- =====================================================================
USE cloud_system;

-- ---------- ① 密钥生命周期表（设计 §7；索引取舍见设计 §7 逐条） ----------
-- 追加式流水：不可编辑、不可物理删、状态单调推进、无 deleted 列（沿 mq_tx_log 先例）；
-- uk_scene_seq=轮换 MAX(seq)+1 取值与并发/重复发布幂等兜底 + 「最新生效行」最左前缀取 seq 最大行；
-- idx_scene_status=分页 status 过滤 + 任务清扫 status∈{1,2} 高频每分钟扫描（表极小，两索引已足）
CREATE TABLE sys_shared_secret (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '密钥ID',
    scene         VARCHAR(30)  NOT NULL DEFAULT 'inner' COMMENT '密钥场景：inner=服务间inner面共享密钥（预留未来其他场景如MCP client-secret 复用本表）',
    seq           INT          NOT NULL COMMENT '轮换代数（场景内单调递增，活跃集 version 由此取 MAX）',
    secret_value  VARCHAR(128) NOT NULL COMMENT '密钥真值（32字节随机base64约44字符；仅运行时分发，任何接口永不返回）',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1生效中 2重叠期（已有继任者） 3已过期 4已作废',
    source        TINYINT      NOT NULL DEFAULT 1 COMMENT '来源：1定时任务 2手动操作',
    activate_time DATETIME     NOT NULL COMMENT '生效时间（创建即生效）',
    expire_time   DATETIME     NOT NULL COMMENT '过期时间（=生效时间+有效期，创建时快照）',
    retire_time   DATETIME     DEFAULT NULL COMMENT '退场时间（被继任者顶替时=轮换时刻+重叠窗；NULL=未被顶替；与expire_time取小者为实际退场时刻）',
    create_by     VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '创建人（定时任务=handler名，手动=操作账号）',
    create_time   DATETIME     DEFAULT NULL COMMENT '创建时间（SQL NOW()）',
    update_by     VARCHAR(30)  DEFAULT NULL COMMENT '更新人（状态推进时=handler名或账号）',
    update_time   DATETIME     DEFAULT NULL COMMENT '更新时间（SQL NOW()）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_scene_seq (scene, seq),
    KEY idx_scene_status (scene, status)
) ENGINE = InnoDB COMMENT = '共享密钥生命周期表（追加式流水：不可编辑不可物理删，状态单调推进）';

-- ---------- ② 站内信内置告警模板（契约 §6：轮换任务失败/活跃集为空时按 code 取用发 admin 角色） ----------
INSERT INTO sys_notify_template (id, code, name, title_template, content_template, type, level,
                                 variables_desc, is_builtin, create_by, create_time, update_by, update_time)
VALUES (4, 'inner_secret_alert', 'inner 密钥轮换告警', 'inner 密钥轮换告警',
        '密钥轮换异常：${content}，请及时处理', 2, 1, 'content=告警内容摘要（不含密钥真值）',
        1, 'admin', NOW(), 'admin', NOW());

-- ---------- ③ 菜单种子（契约 §6：22 段 C + 200/201 F，admin 绑定增量显式列表） ----------
-- 22 挂系统管理(parent 10) sort 9（通知管理 sort 8 之后）；密钥不可新增/编辑/物理删——无对应按钮
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(22,  10, '密钥管理', 'system:innerSecret:list',   'C', '/system/inner-secret', 'Key', 9, 1, NOW()),
(200, 22, '立即轮换', 'system:innerSecret:rotate', 'F', '', '', 1, 1, NOW()),
(201, 22, '作废',     'system:innerSecret:revoke', 'F', '', '', 2, 1, NOW());

-- admin 角色绑定（基线 sys_role_menu 的 SELECT 全量式天然覆盖；增量环境跑本段）
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (22, 200, 201);

-- ---------- ④ xxl-job 轮换任务种子（沿 2026-10-10-mq-clean-xxl-job.sql 形态） ----------
-- 任务 id 12、执行器组 3（cloud-system）、CRON 每分钟；handler 与 cloud-system job 包
-- @XxlJob("innerSecretRotateJobHandler") 逐字一致；retry 0（失败自然重试=下一分钟调度轮）；
-- 重放需先 DELETE id=12
USE `xxl_job`;

INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`, `trigger_status`)
VALUES (12, 3, 'system-inner-secret-rotate', now(), now(), 'cloudai', '', 'CRON', '0 * * * * ?',
        'DO_NOTHING', 'FIRST', 'innerSecretRotateJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1);

commit;
