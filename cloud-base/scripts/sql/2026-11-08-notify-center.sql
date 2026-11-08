-- =====================================================================
-- 站内消息通知中心（notify 域，2026-11-08 全栈轮）
-- 设计 docs/superpowers/specs/2026-11-08-notify-center-design.md（§2 三表 / §6 清理）
-- 契约 docs/superpowers/contracts/2026-11-08-notify-api.md（§5 菜单种子）
-- 四段：①三表 DDL ②内置模板种子 2 条 ③菜单种子 19 段 5 行 ④xxl-job 清理任务种子（id 11）
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑，落库脚本 scripts/sql/2026-11-08-notify-center-apply.py 已内置）：
--   1) SHOW TABLES IN cloud_system LIKE 'sys_notify_%';            -- 应 0 行（三表不存在）
--   2) SELECT id FROM cloud_system.sys_menu WHERE id IN (19,196,197,198,199);  -- 应 0 行（含墓碑，物理判断 id 占用）
--   3) SELECT id FROM xxl_job.xxl_job_info WHERE id = 11;          -- 应 0 行
-- 不可重放：固定主键（菜单 19 段 / xxl_job 任务 id 11 / 模板 id 1/2）——重放需先 DELETE 对应行。
-- 基线同步：cloud_system.sql 已同步三表 DDL + 菜单 19 段 + 内置模板（绑定由基线 sys_role_menu
--   的 SELECT 全量式天然覆盖）；xxl-job 基线 2026-10-09-xxl-job-init.sql 已同步 id 11 种子段。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）进 OnlineSession 快照后 system:notify:* 权限才生效。
-- =====================================================================
USE cloud_system;

-- ---------- ① 三表 DDL（设计 §2.1，索引取舍见设计 §2.2） ----------

-- 模板表：管理域配置，逻辑删除（沿 sys_user/sys_menu 惯例）；
-- uk_code 兼查重与未来 inner 接口按 code 取模板；小表不建 idx_type（低基数负收益，设计 §2.2）
CREATE TABLE sys_notify_template (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '模板ID',
    code             VARCHAR(50)   NOT NULL COMMENT '模板编码（业务引用键，如 welcome_notice；内置模板供未来 inner 接口按 code 引用）',
    name             VARCHAR(50)   NOT NULL COMMENT '模板名称（管理列表展示）',
    title_template   VARCHAR(100)  NOT NULL COMMENT '标题模板（支持 ${var} 占位符）',
    content_template VARCHAR(1000) NOT NULL COMMENT '内容模板（支持 ${var} 占位符）',
    type             TINYINT       NOT NULL DEFAULT 1 COMMENT '消息类型：1公告 2提醒（发送时快照到消息表）',
    level            TINYINT       NOT NULL DEFAULT 0 COMMENT '消息级别：0普通 1重要 2紧急（发送时快照；映射前端弹框样式与停留时长）',
    variables_desc   VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '变量说明（人工维护的变量名与含义文本，仅文档用途；变量集合以模板内容正则解析为准，不重复存储）',
    is_builtin       TINYINT       NOT NULL DEFAULT 0 COMMENT '内置标记：1=内置模板（禁删，内容可改）0=用户创建',
    create_by        VARCHAR(30)   DEFAULT NULL COMMENT '创建人（登录账号）',
    create_time      DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_by        VARCHAR(30)   DEFAULT NULL COMMENT '更新人（登录账号）',
    update_time      DATETIME      DEFAULT NULL COMMENT '更新时间',
    deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT = '站内信模板表（管理域：逻辑删除）';

-- 消息主表：发送流水，不可编辑、无业务删除入口，过期由清理任务物理删（沿 mq_tx_log 流水表先例）；
-- 本轮唯一写路径为 insert，读路径全经收件表 JOIN 命中 PK，仅主键不建二级索引（设计 §2.2）
CREATE TABLE sys_notify_message (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    template_id    BIGINT        NOT NULL COMMENT '来源模板ID（sys_notify_template.id，发送时快照）',
    title          VARCHAR(100)  NOT NULL COMMENT '消息标题（模板解析后最终文本，快照）',
    content        VARCHAR(1000) NOT NULL COMMENT '消息内容（模板解析后最终文本，快照）',
    type           TINYINT       NOT NULL COMMENT '消息类型：1公告 2提醒（模板快照）',
    level          TINYINT       NOT NULL COMMENT '消息级别：0普通 1重要 2紧急（模板快照）',
    link_url       VARCHAR(200)  DEFAULT NULL COMMENT '前端跳转路由（相对 path 如 /bpmn/task；NULL=纯展示无跳转）',
    receiver_type   TINYINT       NOT NULL COMMENT '接收人维度：1按用户 2按角色 3全员（发送时快照，审计用）',
    receiver_count INT           NOT NULL COMMENT '实际展开收件人数（发送时快照，审计用）',
    create_by      VARCHAR(30)   DEFAULT NULL COMMENT '发送人（登录账号）',
    create_time    DATETIME      DEFAULT NULL COMMENT '发送时间',
    update_by      VARCHAR(30)   DEFAULT NULL COMMENT '更新人（本表不可编辑，恒 NULL，列保留对齐审计基线）',
    update_time    DATETIME      DEFAULT NULL COMMENT '更新时间（本表不可编辑，恒 NULL）',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '站内信消息表（发送流水：不可编辑、无业务删除入口；过期由清理任务物理删，沿 mq_tx_log 流水表先例）';

-- 用户收件表：写扩散（一条消息 N 行），无业务删除入口，过期由清理任务物理删（沿 mq_consume_dedup 先例）；
-- uk_message_user=发送批量插入幂等防御+清理孤儿判断最左前缀；idx_user_read=未读计数/用户分页/全部已读三查询共享；
-- idx_read_time=清理任务第一步范围删（设计 §2.2 逐索引取舍）
CREATE TABLE sys_notify_user_message (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '收件记录ID（用户侧列表行与已读操作的目标标识）',
    message_id  BIGINT      NOT NULL COMMENT '消息ID（sys_notify_message.id）',
    user_id     BIGINT      NOT NULL COMMENT '收件用户ID（sys_user.id）',
    read_flag   TINYINT     NOT NULL DEFAULT 0 COMMENT '已读标记：0未读 1已读（未读=未处理，单状态口径）',
    read_time   DATETIME    DEFAULT NULL COMMENT '已读时间（未读为 NULL）',
    create_by   VARCHAR(30) DEFAULT NULL COMMENT '创建人（=发送人账号，批量展开时统一填写）',
    create_time DATETIME    DEFAULT NULL COMMENT '投递时间（=发送时间）',
    update_by   VARCHAR(30) DEFAULT NULL COMMENT '更新人（已读操作时=操作用户账号）',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_user (message_id, user_id),
    KEY idx_user_read (user_id, read_flag),
    KEY idx_read_time (read_time)
) ENGINE = InnoDB COMMENT = '站内信用户收件表（写扩散：一条消息 N 行；无业务删除入口，过期由清理任务物理删，沿 mq_consume_dedup 先例）';

-- ---------- ② 内置模板种子（契约 §5：2 条 is_builtin=1，欢迎公告无变量 / 审批提醒含 ${nickname} 演示变量链路） ----------
INSERT INTO sys_notify_template (id, code, name, title_template, content_template, type, level,
                                 variables_desc, is_builtin, create_by, create_time, update_by, update_time)
VALUES (1, 'welcome_notice', '欢迎公告', '欢迎加入',
        '欢迎使用本平台，祝你工作顺利！', 1, 0, '', 1, 'admin', NOW(), 'admin', NOW()),
       (2, 'approval_remind', '审批提醒', '审批待办提醒',
        '您好 ${nickname}，您有一条新的审批任务待处理，请及时查看。', 2, 1, 'nickname=用户昵称', 1, 'admin', NOW(), 'admin', NOW());

-- ---------- ③ 菜单种子（契约 §5：19 段 C + 196-199 F，admin 绑定增量显式列表） ----------
-- 19 段经基线全量核对空闲（18 顶级已被 AI 域占用、181-195 已占、196-199 空闲）；sort 8=系统管理第 8 项
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(19,  10, '通知管理',   'system:notify:list',   'C', '/system/notify', 'Bell', 8, 1, NOW()),
(196, 19, '新增模板',   'system:notify:add',    'F', '', '', 1, 1, NOW()),
(197, 19, '修改模板',   'system:notify:edit',   'F', '', '', 2, 1, NOW()),
(198, 19, '删除模板',   'system:notify:remove', 'F', '', '', 3, 1, NOW()),
(199, 19, '发送通知',   'system:notify:send',   'F', '', '', 4, 1, NOW());

-- admin 角色绑定（基线 sys_role_menu 的 SELECT 全量式天然覆盖；增量环境跑本段）
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (19, 196, 197, 198, 199);

-- ---------- ④ xxl-job 清理任务种子（沿 2026-10-10-mq-clean-xxl-job.sql 形态） ----------
-- 任务 id 11、执行器组 3（cloud-system）、CRON 每日 03:40（错峰 03:00 MQ 两表清理 / 03:30 死信清理，设计 §6）；
-- handler 与 cloud-system job 包 @XxlJob("notifyCleanJobHandler") 逐字一致；重放需先 DELETE id=11
USE `xxl_job`;

INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`, `trigger_status`)
VALUES (11, 3, 'system-notify-clean', now(), now(), 'cloudai', '', 'CRON', '0 40 3 * * ?',
        'DO_NOTHING', 'FIRST', 'notifyCleanJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1);

commit;
