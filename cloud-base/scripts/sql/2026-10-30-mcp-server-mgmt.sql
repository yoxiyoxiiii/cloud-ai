-- =====================================================================
-- MCP Server 配置化管理：ai_mcp_server 注册表 + ai_tool 扩列 + 菜单种子（2026-10-30 轮）
-- 契约 docs/superpowers/contracts/2026-10-30-mcp-server-mgmt-api.md（§5）
-- 设计 docs/superpowers/specs/2026-10-30-mcp-server-mgmt-design.md（§4）
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) cloud_ai: SHOW TABLES LIKE 'ai_mcp_server'; -- 应 0 行（表未建）
--   2) cloud_ai: SELECT COUNT(*) FROM ai_tool; -- 应 2（既有两行种子），
--      且 SHOW COLUMNS FROM ai_tool LIKE 'server_id'; -- 应 0 行（列未加）
--   3) cloud_system: SELECT id FROM sys_menu WHERE id IN (191,192,193,194,195); -- 应 0 行
--   4) cloud_system: SELECT id FROM sys_menu WHERE id = 18; -- 应 1 行（父目录「AI 助手」在）
-- 幂等口径：显式 id INSERT 不幂等（前置断言 3 防占用）；ai_tool ALTER 不幂等（断言 2 防重放）。
-- 基线同步：cloud_ai.sql / cloud_system.sql 已同步本文件语义——新环境直接跑基线，无需本增量。
-- 权限快照时效：菜单落库后 admin 须重新登录（或 refresh），perms 快照含 ai:mcp:* 才生效。
-- =====================================================================
USE cloud_ai;

-- MCP 服务注册表：外部/内置 server 连接配置的真相源（非敏感字段入库；
-- 凭据真值在 .env 键 CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>，只存键名在 credential_headers_json）。
-- uk_name 兼查重与重名兜底（软删墓碑占键时新建同名报业务码，沿 ai_agent.uk_name 先例）；
-- name 兼凭据 env 键段与管理命名空间，'SYSTEM' 为内置平台服务占用；
-- 列表 ORDER BY id DESC 全表扫管理面规模（个位数到十位数）接受，不建额外索引（同 ai_agent 取舍）。
CREATE TABLE ai_mcp_server
(
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'MCP服务ID（主键）',
    name                   VARCHAR(30)  NOT NULL COMMENT '服务名（大写蛇形，全局唯一；兼作凭据env键段 CLOUD_BASE_AI_MCP__SERVERS__<NAME>__*；SYSTEM=内置平台服务',
    description            VARCHAR(512) NULL COMMENT '描述（管理面备注，可选）',
    url                    VARCHAR(512) NOT NULL COMMENT 'MCP端点地址（http/sse 的完整 URL；内置服务默认 http://127.0.0.1:9202/mcp）',
    transport              VARCHAR(20)  NOT NULL DEFAULT 'http' COMMENT '传输类型：http=streamable HTTP sse=SSE 事件流（stdio 本轮不开放，管理面拒绝保存）',
    headers_json           TEXT         NULL COMMENT '非敏感静态请求头 JSON（字面值入库；secret/token 一律走 credential_headers_json 引用 .env，红线）',
    credential_headers_json TEXT        NULL COMMENT '凭据请求头 JSON：{header名: .env键名}——只存键名不存真值；键名须匹配 ^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$',
    timeout_ms             INT          NOT NULL DEFAULT 30000 COMMENT '单次调用超时毫秒（1000..120000）',
    is_builtin             TINYINT      NOT NULL DEFAULT 0 COMMENT '内置标记：1=平台 cloud-system 服务（不可删不可改名；调用头自动注入用户JWT+服务密钥）',
    status                 TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=启用 1=停用（停用后其工具对 agent 运行不可见，行与工具行保留）',
    create_by              VARCHAR(30)  NULL COMMENT '创建人',
    create_time            DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（DB 时钟生成）',
    update_by              VARCHAR(30)  NULL COMMENT '更新人',
    update_time            DATETIME     NULL COMMENT '更新时间',
    deleted                TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删（软删后其工具对运行不可见，工具行保留）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='MCP服务注册表（外部/内置 server 连接配置；凭据真值在 .env 不入库）';

-- 内置行：cloud-system /mcp 端点（is_builtin=1 不可删不可改名；url 部署差异时管理员可编辑）。
-- 内置行的调用头自动注入当次用户 JWT + X-MCP-Client-Secret（.env 既有键），credential_headers_json 恒 NULL。
INSERT INTO ai_mcp_server (id, name, description, url, transport, timeout_ms, is_builtin, status) VALUES
(1, 'SYSTEM', '平台内置 cloud-system MCP 服务', 'http://127.0.0.1:9202/mcp', 'http', 30000, 1, 0);

-- ai_tool 扩列（设计 §4.2）：server_id 归属 + status 三态 + update_time 同步/审核时间。
-- 既有两行种子回填 server_id=1（内置）+ status=1（已发布）——行为与现状完全等价（这两工具本就「已审核」）。
-- server_id 的 DEFAULT 1 仅是迁移引导值（回填一步到位）；此后所有写入路径（同步/审核）显式传值。
ALTER TABLE ai_tool
    ADD COLUMN server_id BIGINT NOT NULL DEFAULT 1 COMMENT '归属MCP服务ID（ai_mcp_server.id，弱引用不建外键；1=内置平台服务',
    ADD COLUMN status TINYINT NOT NULL DEFAULT 0 COMMENT '工具状态：0=草稿（同步产生，对agent不可见） 1=已发布（审核过，可见） 2=已失效（server不再列出，不可见，行保留）',
    ADD COLUMN update_time DATETIME NULL COMMENT '最近同步/审核时间（DB 时钟生成）';

UPDATE ai_tool SET server_id = 1, status = 1;

-- idx_server_status 命中两条查询：① 同步 upsert 前取某 server 全部工具行（server_id 等值前缀）；
-- ② 工具管理面按 server/状态过滤清单（两列等值）。运行链路以绑定表 uk_agent_tool 的 agent_id 前缀
-- 驱动、按主键点查工具行，server/status 过滤在点查后施加，不依赖该索引（也不需要新索引）。
ALTER TABLE ai_tool ADD INDEX idx_server_status (server_id, status);

-- =====================================================================
-- 菜单种子（cloud_system 库；契约 §5.2——191「MCP服务管理」C 挂 18「AI 助手」下，
-- 192-195 F 按钮挂 191 下；186-190 已被 agent 轮占用，id 接续 191 起）
-- =====================================================================
USE cloud_system;

INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(191, 18,  'MCP服务管理', 'ai:mcp:list',   'C', '/mcp/manage', 'Connection', 5, 1, NOW()),
(192, 191, '新增',        'ai:mcp:add',    'F', '',            '',           1, 1, NOW()),
(193, 191, '编辑',        'ai:mcp:update', 'F', '',            '',           2, 1, NOW()),
(194, 191, '删除',        'ai:mcp:delete', 'F', '',            '',           3, 1, NOW()),
(195, 191, '测试与同步',  'ai:mcp:sync',   'F', '',            '',           4, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (191, 192, 193, 194, 195);

commit;
