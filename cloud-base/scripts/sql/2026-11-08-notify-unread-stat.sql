-- 通知未读统计表（notify 域性能优化轮，2026-11-08）：
-- summary 30s 轮询由收件表 COUNT(*) 改为统计表单行 PK 查询；收件表仍是真相源，
-- 统计表由发送/已读三写路径事务内维护，xxl-job 任务 11 每日 03:40 对账全量重建兜底。
-- 幂等可重放：建表 IF NOT EXISTS + 回填先清空后聚合重灌（与运行时对账同构）。
-- 设计 docs/superpowers/specs/2026-11-08-notify-center-design.md §2 第 4 表 / §6 第三步
USE cloud_system;

CREATE TABLE IF NOT EXISTS sys_notify_user_stat (
    user_id         BIGINT      NOT NULL COMMENT '用户ID（sys_user.id，主键=每用户一行）',
    unread_count    INT         NOT NULL DEFAULT 0 COMMENT '未读数（发送+1、已读按影响行数递减；每日对账重建兜底）',
    last_receipt_id BIGINT      NOT NULL DEFAULT 0 COMMENT '该用户最新收件行 id（summary 轮询水位；0=无任何收件）',
    update_time     DATETIME    DEFAULT NULL COMMENT '最近维护时间',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB COMMENT = '站内信未读统计表（收件表派生计数：真相源 sys_notify_user_message，写路径同事务维护+每日对账）';

-- 回填（幂等：先清空再按收件表聚合重灌，与运行时对账路径同构——聚合读 + 普通插入）
DELETE FROM sys_notify_user_stat;
INSERT INTO sys_notify_user_stat (user_id, unread_count, last_receipt_id, update_time)
SELECT user_id, SUM(read_flag = 0), IFNULL(MAX(id), 0), NOW()
  FROM sys_notify_user_message
 GROUP BY user_id;
