-- 数据权限组件化（跨服务求值）存量库增量（契约 2026-10-10-dataperm-component-api.md §7 / 设计 D27）。
-- 内容：admin 角色 bpmn_approval/ALL 行规则种子一行——首个跨服务资源（provider 注册表 registerRemote）。
--   行为变更记档（D27，与 leave 轮 admin 语义变更同款）：admin 审批列表「仅自己发起」→「全部」；
--   无规则普通用户默认 SELF=仅自己，与旧行为逐字一致（向后兼容）。leave 轮 rule（resource='leave'）不动。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   SELECT id FROM sys_data_perm_rule WHERE resource = 'bpmn_approval' AND subject_type = 0 AND subject_id = 1;
--   -- 应 0 行（uk_resource_subject 无冲突；2026-10-10 实测该资源无任何存量行）
-- ⚠️ 不可重放：AUTO_INCREMENT 无显式 id，按 resource/subject 判存——重放先 DELETE 对应行。
-- 无菜单/perms 种子（审批页复用 bpmn:approval:list 既有权限标识，契约 §7.1）。
-- 基线 cloud_system.sql 已同步同款种子，新环境直接跑基线无需本增量。

USE cloud_system;

-- admin 角色行规则（D27 沿 leave 种子先例：row_scope=4 全部档；代码无 admin 特例，规则面前人人平等）
INSERT INTO sys_data_perm_rule (resource, subject_type, subject_id, row_scope, custom_accounts,
                                create_by, create_time, update_by, update_time)
VALUES ('bpmn_approval', 0, 1, 4, NULL, 'admin', NOW(), 'admin', NOW());
