-- =====================================================================
-- Agent 绑定 MCP 服务（server 级订阅语义绑定）：ai_agent_mcp_server 新表（2026-10-31 轮）
-- 契约 docs/superpowers/contracts/2026-10-29-agent-platform-api.md（§8 修订段）
-- 设计 docs/superpowers/specs/2026-10-31-agent-mcp-server-binding-design.md（§4）
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) cloud_ai: SHOW TABLES LIKE 'ai_agent_mcp_server'; -- 应 0 行（表未建，防重放）
--   2) cloud_ai: SELECT COUNT(*) FROM ai_mcp_server; -- 应 ≥1（上一轮内置行 SYSTEM 在，
--      依赖 2026-10-30-mcp-server-mgmt.sql 已跑）
-- 幂等口径：CREATE TABLE 不幂等（前置断言 1 防重放）。本脚本只建表、无数据变更、无
-- cloud_system 段（无新菜单种子——选项端点挂既有 ai:agent:list）。
-- 基线同步：cloud_ai.sql 已同步本建表语句——新环境直接跑基线，无需本增量。
-- =====================================================================
USE cloud_ai;

-- 智能体-MCP服务绑定（纯关系表，沿 ai_agent_tool 先例：物理删除、无 deleted 列、
-- 无 update 审计列；保存 = 按 agent_id 全删全插，同一事务由 service 收口）。
-- 订阅语义：绑定后该 server 名下已发布工具自动进入 agent 工具面（装配每运行查库，
-- server 同步/审核的既有缓存失效联动覆盖新工具自动生效路径）。
-- uk_agent_server(agent_id, server_id) 三用：① 保存全删全插的 DELETE 前缀；
-- ② 装配子查询「SELECT server_id WHERE agent_id=?」前缀反向扫；③ 并发双插查重兜底
-- （IntegrityError）。不为「按 server 反查绑定 agent」建 idx_server_id——该查询暂无
-- 消费方，出现需求时增量 ALTER；对既有表零增量索引（装配查询全命中既有索引：
-- ai_tool.idx_server_status / ai_agent_tool.uk_agent_tool / ai_mcp_server 主键）。
CREATE TABLE ai_agent_mcp_server
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '绑定ID（主键）',
    agent_id    BIGINT      NOT NULL COMMENT '智能体ID（ai_agent.id，弱引用不建外键；agent 软删不级联，绑定行随软删失去可见入口）',
    server_id   BIGINT      NOT NULL COMMENT 'MCP服务ID（ai_mcp_server.id，弱引用不建外键；停用行可绑=预绑定，运行期静默过滤；软删行绑定校验拦 5029）',
    create_by   VARCHAR(30) NULL COMMENT '绑定操作人账号',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间（DB 时钟生成，应用层不传时间值）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_server (agent_id, server_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='智能体-MCP服务绑定（纯关系表：物理删除，随保存全删全插；订阅语义——server名下已发布工具自动进agent工具面）';

commit;
