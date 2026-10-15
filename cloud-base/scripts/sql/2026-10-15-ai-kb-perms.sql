-- =====================================================================
-- AI 知识库按钮权限种子增量（2026-10-15 列表管理轮）
-- 契约 docs/superpowers/contracts/2026-10-14-ai-kb-api.md（§8）
-- 两行：184 F「编辑」perms ai:kb:update sort 2、185 F「删除」perms ai:kb:delete
--       sort 3，均挂 182 C「知识库」下——F 型 path/icon 空，形态沿 183 先例
--       （2026-10-14-ai-kb-create.sql）。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT id FROM sys_menu WHERE id IN (184, 185);  -- 应 0 行（物理判断 id 占用）
--   2) SELECT id, name FROM sys_menu WHERE id = 182;     -- 应 1 行（父菜单在）
-- 幂等口径：显式 id INSERT 不幂等（前置断言 1 防占用）；绑定段撞 uk_role_menu 报错属预期。
-- 基线同步：cloud_system.sql 已同步 184/185 段——新环境直接跑基线，无需本增量。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）perms 快照含 ai:kb:update /
--   ai:kb:delete 才生效（快照登录时定格，落库前登录的会话不含新权限）。
-- =====================================================================
USE cloud_system;

INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(184, 182,  '编辑',   'ai:kb:update', 'F', '', '', 2, 1, NOW()),
(185, 182,  '删除',   'ai:kb:delete', 'F', '', '', 3, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (184, 185);

commit;
