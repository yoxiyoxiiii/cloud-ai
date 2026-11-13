-- =====================================================================
-- RAG 检索 MCP 服务化：ai_mcp_server 第二个内置行 RAG(id=2) + 管理员角色授权（2026-11-13 轮）
-- 契约 docs/superpowers/contracts/2026-11-13-rag-mcp-tools-api.md（§1.1/§6）
-- 设计 docs/superpowers/specs/2026-11-13-rag-mcp-server-design.md（§3 D8）
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT id, name FROM ai_mcp_server WHERE id = 1; -- 应 1 行且 name='SYSTEM'
--      （内置先例在；不在说明基线/增量未按序执行）
--   2) SELECT id, name FROM ai_mcp_server WHERE id = 2 OR name = 'RAG'; -- 应 0 行
--      （id=2 与名 'RAG' 均未被占用；已知 id=9/10 是历史冒烟残留（SMOKETEST/SMOKEEXT
--      已软删），与本断言无冲突不动它们）
--   3) SELECT id FROM ai_mcp_server_grant WHERE server_id = 2; -- 应 0 行（防重放）
-- 幂等口径：显式 id INSERT 不幂等（前置断言 2/3 防占用防重放）。
-- 基线同步：cloud_ai.sql 已同步本文件语义——新环境直接跑基线，无需本增量。
-- ai_tool 无种子：rag_search_kb 行由管理员对 RAG 行执行「同步」产生草稿、审核发布
-- （工具 schema 真相源在 rag 服务端，自动发现+审核生效流程，见契约 §4）。
-- =====================================================================
USE cloud_ai;

-- 内置行 RAG：cloud-base-rag 服务 /mcp 端点（与 SYSTEM 行同款 is_builtin=1 保护：
-- 不可删不可改名；调用头自动注入当次用户 JWT + X-MCP-Client-Secret；url 部署差异时管理员可编辑）
INSERT INTO ai_mcp_server (id, name, description, url, transport, timeout_ms, is_builtin, status) VALUES
(2, 'RAG', '平台内置 cloud-base-rag 检索服务', 'http://127.0.0.1:8011/mcp', 'http', 30000, 1, 0);

-- server 级授权：RAG 行授内置管理员角色（sys_role id=1，基线种子恒存在），对齐
-- SYSTEM 行先例——管理员本就持 ai:mcp:list 豁免，此行价值是授权状态在面板可见、
-- 豁免口径收紧时不回退管理员可用性；业务用户用 RAG 工具须管理员另行授权（默认拒绝）。
INSERT INTO ai_mcp_server_grant (server_id, subject_type, subject_id) VALUES (2, 0, 1);
