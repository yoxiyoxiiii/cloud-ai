-- =====================================================================
-- AI 知识库菜单种子（cloud-base-ai 知识库管理面——create_kb 首端点，2026-10-14）
-- 契约 docs/superpowers/contracts/2026-10-14-ai-kb-api.md（§3）
-- 一段：C「知识库」id=182（perms ai:kb:add，POST /ai/rag/kb）挂 18 M「AI 助手」。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT id FROM sys_menu WHERE id = 182;   -- 应 0 行（物理判断 id 占用）
--   2) SELECT id, name FROM sys_menu WHERE id = 18;  -- 应 1 行（父菜单在）
-- 幂等口径：显式 id INSERT 不幂等（前置断言 1 防占用）；绑定段撞 uk_role_menu 报错属预期。
-- 基线同步：cloud_system.sql 已同步 182 段——新环境直接跑基线，无需本增量。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）perms 快照含 ai:kb:add 才生效。
-- =====================================================================
USE cloud_system;

INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(182, 18, '知识库', 'ai:kb:add', 'C', '/rag/kb', 'Collection', 2, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (182);

commit;
