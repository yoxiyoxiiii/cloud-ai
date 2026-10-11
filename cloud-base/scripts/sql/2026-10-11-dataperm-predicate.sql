-- 数据权限 L2 结构化谓词（行级条件过滤）存量库增量（契约 2026-10-11-dataperm-predicate-api.md §7 / 设计 D30/D37）。
-- 内容：sys_data_perm_rule 增 predicates 列（行级条件组 JSON 数组串，NULL/空=无条件——
--   存量规则语义零变化，D40 向后兼容红线；条件数≤5/IN 值≤30/序列化≤2000 由配置校验链 3037 把守）。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   SHOW COLUMNS FROM sys_data_perm_rule LIKE 'predicates';
--   -- 应 0 行（列未加过；2026-10-11 实测该列不存在）
-- ⚠️ 不可重放：ALTER ADD COLUMN 重放报 Duplicate column——重放前先 DROP COLUMN predicates。
-- 零新表/零新索引/零种子（设计 §9 索引审查：规则表查询路径零变化，uk 最左前缀不变；
--   业务表可过滤列索引取舍见设计 §9：id 走 PK、leave_type/status 低选择性禁建、
--   start_date/end_date/create_time 全表扫记档、business_type uk 最左前缀 EXPLAIN 抽查）。
-- 基线 cloud_system.sql 已同步加列（双落位先例），新环境直接跑基线无需本增量。

USE cloud_system;

ALTER TABLE sys_data_perm_rule
    ADD COLUMN predicates VARCHAR(2000) NULL
        COMMENT '行级条件组(JSON数组串,如 [{"field":"leaveType","operator":"EQ","values":["1"]}];NULL/空=无条件,档位即全量语义;组内多条件AND,跨规则并集;条件数≤5,IN值≤30)'
        AFTER custom_accounts;
