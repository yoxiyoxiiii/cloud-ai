-- =====================================================================
-- AI 对话菜单种子（cloud-base-ai RAG 入门案例——LLM 流式对话，2026-10-13）
-- 设计 docs/superpowers/specs/2026-10-13-ai-rag-chat-design.md（D13）
-- 契约 docs/superpowers/contracts/2026-10-13-ai-rag-chat-api.md（§6.1）
-- 一段：顶级 M「AI 助手」id=18 + 子菜单 C「对话」id=181（perms ai:chat:list，
--       两端点 /ai/rag/chat、/ai/rag/chat/stream 共用一个使用权限，无按钮级 F）。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT id FROM sys_menu WHERE id IN (18, 181);   -- 应 0 行（含墓碑，物理判断 id 占用）
-- 幂等口径：菜单段显式 id INSERT 不幂等（前置断言 1 防占用）；
--   绑定段无防重——重跑撞 uk_role_menu 唯一键报错（数据不脏，基线 SELECT 全量式天然覆盖）。
-- 基线同步：cloud_system.sql 已同步 18 段（绑定由基线 sys_role_menu 的 SELECT 全量式
--   天然覆盖）——新环境直接跑基线，无需本增量。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）进 OnlineSession 快照后
--   ai:chat:list 权限才生效（沿 bpmn-deploy-menu / mq-dlq-governance 先例头注）。
-- =====================================================================
USE cloud_system;

-- AI 对话菜单（契约 §6.1 原文：id 接续——18 段空闲（10-17/20-21/30-34 及百位子段已占），
-- 预留 182/183 给 AI 域后续菜单；M path 空串 + sort 4 = 顶级第 4 个目录）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(18,  0, 'AI 助手', '',             'M', '',          'ChatDotRound', 4, 1, NOW()),
(181, 18, '对话',   'ai:chat:list', 'C', '/rag/chat', 'ChatLineRound', 1, 1, NOW());

-- admin 角色绑定（增量显式 id 列表——基线 sys_role_menu 的 SELECT 全量式天然覆盖）
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (18, 181);

commit;
