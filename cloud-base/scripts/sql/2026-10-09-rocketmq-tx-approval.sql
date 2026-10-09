-- MQ 事务消息改造存量库增量（契约 2026-10-09-rocketmq-tx-approval-api / 设计 2026-10-09-rocketmq-tx-approval-design D8）。
-- 两段：cloud_system 域（mq_tx_log + mq_consume_dedup 建表 + sys_leave 注释修订 + 字典种子）/
--       cloud_bpmn 域（mq_tx_log 建表）。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT 1 FROM information_schema.tables WHERE table_schema='cloud_system'
--        AND table_name IN ('mq_tx_log','mq_consume_dedup');   -- 应 0 行（存在即停：核对表结构再定）
--   2) SELECT 1 FROM information_schema.tables WHERE table_schema='cloud_bpmn'
--        AND table_name='mq_tx_log';                           -- 应 0 行（同上）
--   3) SELECT dd.id FROM sys_dict_data dd JOIN sys_dict_type dt ON dd.dict_type_id=dt.id
--        WHERE dt.dict_key='bpmn_approval_status' AND dd.value='4';  -- 应 0 行（value=4 未被占用）
-- INSERT 种子以 FROM DUAL WHERE NOT EXISTS 幂等（MySQL 5.7 裸 SELECT 带 WHERE 需 FROM DUAL）；
-- ALTER 仅三列 COMMENT 修订（无结构/数据变更，重跑无害）；建表幂等口径=前置断言存在即停。
-- 基线 cloud_system.sql / cloud_bpmn.sql 已同步同款定义与种子，新环境直接跑基线无需本增量。

USE cloud_system;

-- mq_tx_log：MQ 事务消息本地事务流水（starter 回查依据；cloud_system=流1 发起通道 leave-create 所在库）
-- 索引设计（设计 D8 取舍）：uk_tx_no 命中回查（checkLocalTransaction 按 tx_no 点查）；
--   idx_business(business_type, business_key) 命中运维排查（按单据查消息历史，低频扫描）；
--   无按时间查询路径不建 create_time 索引（清理任务全表扫，低峰 03:00 可接受——清理表行数=保留期内事务数）。
CREATE TABLE mq_tx_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tx_no         VARCHAR(64)  NOT NULL COMMENT '事务消息流水号（发送前预生成 UUID，userProperty TX_NO 回传；回查唯一依据：行存在=COMMIT 行缺失=ROLLBACK）',
    topic         VARCHAR(64)  NOT NULL COMMENT '目标 topic（TX_APPROVAL_CREATE / APPROVAL_EVENT_NOTIFY）',
    channel       VARCHAR(64)  NOT NULL COMMENT '业务通道标识（starter TxLocalExecutor.channel，如 leave-create/terminal-complete/terminal-cancel）',
    business_type VARCHAR(50)  NOT NULL COMMENT '业务类型（消息业务维度，如 leave）',
    business_key  VARCHAR(64)  NOT NULL COMMENT '业务键（流1=leaveId 流2=approvalId）',
    result_digest VARCHAR(500) NULL     COMMENT '本地事务结果摘要（executor 返回值 JSON 截断，仅运维观测）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（=本地事务提交时间）',
    update_time   DATETIME     NULL     COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tx_no (tx_no),
    KEY idx_business (business_type, business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 事务消息本地事务流水（starter 回查依据；审计保留 N 天后定时清理）';

-- mq_consume_dedup：MQ 消费通用去重表（先插后消费/失败删行放行重试；仅事件消费方 cloud_system）
-- 索引设计（设计 D8 取舍）：uk_group_key(consumer_group, msg_key) 即约束即索引，命中每次消费的前置插行判定；
--   idx_create_time 命中清理任务范围删除（DELETE ... WHERE create_time < ?）。
--   uk 联合总长 (64+190)*4=1016 字节 < 3072 上限。
CREATE TABLE mq_consume_dedup (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    consumer_group VARCHAR(64)  NOT NULL COMMENT '消费组（g_<svc>_<purpose>，如 g_system_approval_event）',
    msg_key        VARCHAR(190) NOT NULL COMMENT '消息幂等键（优先消息 KEYS 业务键，缺省 msgId）',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（=消费开始时间，清理锚点）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_key (consumer_group, msg_key),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 消费通用去重表（先插后消费/失败删行放行重试；N 天定时清理）';

-- sys_leave 注释修订（MQ 形态语义更新；无结构/数据变更）：status 增 4 发起失败终态、
-- approval_id 改事件通知回填、id 改 snowflake 预生成（半消息体发送前需知 businessKey，设计 R2）
ALTER TABLE sys_leave
    MODIFY id BIGINT NOT NULL AUTO_INCREMENT
        COMMENT '请假单ID（即平台 bpmn_approval.business_key；MQ 事务消息形态下 snowflake 预生成显式插入）',
    MODIFY status TINYINT NOT NULL DEFAULT 0
        COMMENT '状态：0审批中 1已通过 2已拒绝 3已撤销 4发起失败（缓存快照，真相源=bpmn_approval.status，读时纠偏；4=消费端确定性失败终态仅 system 产生；字典 bpmn_approval_status）',
    MODIFY approval_id BIGINT NULL
        COMMENT '审批单ID（bpmn_approval.id，事件通知回填，读时纠偏兜底；发起失败为 NULL 可重新发起；撤销后仍保留）';

-- 字典种子：bpmn_approval_status 增「发起失败」(4, sort=5)（契约 §1.2 status 语义；label 文案锁契约）
INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '发起失败', '4', 5, 0, 1, 'system', NOW(), 'system', NOW() FROM sys_dict_type t
 WHERE t.dict_key = 'bpmn_approval_status' AND t.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = '4');

USE cloud_bpmn;

-- mq_tx_log（cloud_bpmn=流2 终态通知通道 terminal-complete/terminal-cancel 所在库；结构与 cloud_system 同款）
CREATE TABLE mq_tx_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tx_no         VARCHAR(64)  NOT NULL COMMENT '事务消息流水号（发送前预生成 UUID，userProperty TX_NO 回传；回查唯一依据：行存在=COMMIT 行缺失=ROLLBACK）',
    topic         VARCHAR(64)  NOT NULL COMMENT '目标 topic（TX_APPROVAL_CREATE / APPROVAL_EVENT_NOTIFY）',
    channel       VARCHAR(64)  NOT NULL COMMENT '业务通道标识（starter TxLocalExecutor.channel，如 leave-create/terminal-complete/terminal-cancel）',
    business_type VARCHAR(50)  NOT NULL COMMENT '业务类型（消息业务维度，如 leave）',
    business_key  VARCHAR(64)  NOT NULL COMMENT '业务键（流1=leaveId 流2=approvalId）',
    result_digest VARCHAR(500) NULL     COMMENT '本地事务结果摘要（executor 返回值 JSON 截断，仅运维观测）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（=本地事务提交时间）',
    update_time   DATETIME     NULL     COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tx_no (tx_no),
    KEY idx_business (business_type, business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 事务消息本地事务流水（starter 回查依据；审计保留 N 天后定时清理）';
