-- =====================================================================
-- AI 知识库菜单种子（cloud-base-ai 知识库管理面，2026-10-14 首立 / 2026-10-15 修订）
-- 契约 docs/superpowers/contracts/2026-10-14-ai-kb-api.md（§6）
-- 两段：182 C「知识库」perms ai:kb:list（查询端点与菜单）挂 18 M「AI 助手」；
--       183 F「新增」perms ai:kb:add（创建+上传按钮）挂 182 下——F 型 path/icon 空，
--       形态沿基线 172/173 先例。2026-10-15 修订：182 由 ai:kb:add 改 ai:kb:list
--       （查询与创建权限拆分），按钮权限移交 183。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT id FROM sys_menu WHERE id IN (182, 183);  -- 应 0 行（物理判断 id 占用）
--   2) SELECT id, name FROM sys_menu WHERE id = 18;      -- 应 1 行（父菜单在）
--   应急分支（断言 1 失败 = 2026-10-14 旧版 182 已按 ai:kb:add 落库）：停下回报主控，
--   改跑 UPDATE sys_menu SET perms='ai:kb:list' WHERE id=182 后仅执行 183 段与绑定段。
-- 幂等口径：显式 id INSERT 不幂等（前置断言 1 防占用）；绑定段撞 uk_role_menu 报错属预期。
-- 基线同步：cloud_system.sql 已同步 182/183 段——新环境直接跑基线，无需本增量。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）perms 快照含 ai:kb:list / ai:kb:add 才生效。
-- =====================================================================
USE cloud_system;

INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(182, 18,   '知识库', 'ai:kb:list', 'C', '/rag/kb', 'Collection', 2, 1, NOW()),
(183, 182,  '新增',   'ai:kb:add',  'F', '',        '',           1, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (182, 183);

commit;
