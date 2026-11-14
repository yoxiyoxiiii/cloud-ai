-- Agent 服务基线库 cloud_ai_agent（2026-11-14 三工程拆分轮自 cloud_ai.sql 拆出：
--   agent+mcp 域 8 表与三段种子，DDL/种子与原文件逐行一致。
--   库名换 cloud_ai_agent，其余零改动；全量 DDL 真相源以本文件为 agent 域基准。）
CREATE DATABASE IF NOT EXISTS cloud_ai_agent DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE cloud_ai_agent;

-- ========== 平台 Agent 管理（2026-10-29 agent 平台轮，增量脚本 2026-10-29-agent-platform.sql） ==========
-- 契约 docs/superpowers/contracts/2026-10-29-agent-platform-api.md；设计 2026-10-29-agent-platform-design.md §2

-- 智能体定义：管理员配置的提示词/温度/状态/配置版本，运行端点按行组装 DeepAgent。
-- uk_name 兼查重与重名兜底（软删墓碑占键时新建同名报业务码，沿知识库 uk_name 先例）；
-- 按 id 点查走主键；列表 ORDER BY id DESC 全表扫管理面规模可接受，不建额外索引。
CREATE TABLE ai_agent
(
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '智能体ID（主键）',
    name           VARCHAR(50)  NOT NULL COMMENT '名称（全局唯一的管理命名空间）',
    description    VARCHAR(512) NULL COMMENT '描述（管理面备注，可选；契约 §2.1/§2.2/§2.3 出入参字段）',
    system_prompt  TEXT         NOT NULL COMMENT '系统提示词（管理员静态模板，不渲染任何变量；用户输入只进 user 消息位）',
    temperature    DECIMAL(3,2) NOT NULL DEFAULT 0.70 COMMENT 'LLM 采样温度（0~2，服务端钳制）',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0=启用 1=停用（停用拒绝新运行与创建会话，历史会话可回显可删除）',
    config_version INT          NOT NULL DEFAULT 1 COMMENT '配置版本（每次成功编辑 SQL 原子 +1，运行时缓存失效键）',
    create_by      VARCHAR(30)  NULL COMMENT '创建人',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（DB 时钟生成，应用层不传时间）',
    update_by      VARCHAR(30)  NULL COMMENT '更新人',
    update_time    DATETIME     NULL COMMENT '更新时间',
    deleted        TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删（软删后会话与运行审计保留）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='平台智能体定义（提示词+工具配置动态组装 DeepAgent 的配置源）';

-- 工具注册表（2026-10-30 MCP server 管理轮扩列：server_id 归属 + status 三态 + update_time）：
-- 平台自研工具（内置 SYSTEM 行）种子登记、回填已发布；外部 server 工具经同步产生草稿、审核发布
-- （契约 2026-10-30-mcp-server-mgmt-api.md §7.1——对原「管理面不提供编辑」的增量翻案，
-- 可编辑项仅 required_perms 与显示名）；description/input_schema 的真相源：内置=2026-10-17-mcp-tools-api.md，
-- 外部=server 本身（同步更新）；对 agent 可见 = status=1 且归属 server 启用未删。
-- uk_tool_key 为跨 server 全局唯一（工具命名冲突的最终防线）；
-- idx_server_status 命中「取某 server 全部工具行」（同步）与「按 server/状态过滤清单」（管理面）；
-- 无软删（失效态由 status=2 承载，行保留供审计与复活）。
CREATE TABLE ai_tool
(
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '工具ID（主键）',
    tool_key       VARCHAR(100)  NOT NULL COMMENT '工具标识（对齐 MCP 工具名，如 system_query_users；外部工具=server 上报原名直存不加前缀）',
    name           VARCHAR(50)   NOT NULL COMMENT '显示名（管理页勾选展示；初始=tool_key，审核时可改）',
    description    VARCHAR(1024) NOT NULL COMMENT '工具描述（注入 LLM 的工具 doc；内置与 MCP 契约逐字同步，外部以 server 同步为准）',
    input_schema   TEXT          NOT NULL COMMENT '入参 JSON Schema 文本（运行动态构造 pydantic 入参模型；审核时校验可构建）',
    required_perms VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '所需权限标识（逗号分隔，空=登录即可；运行门按 ⊆ 用户 perms 过滤）',
    server_id      BIGINT        NOT NULL DEFAULT 1 COMMENT '归属MCP服务ID（ai_mcp_server.id，弱引用不建外键；1=内置平台服务',
    status         TINYINT       NOT NULL DEFAULT 0 COMMENT '工具状态：0=草稿（同步产生，对agent不可见） 1=已发布（审核过，可见） 2=已失效（server不再列出，不可见，行保留）',
    update_time    DATETIME      NULL COMMENT '最近同步/审核时间（DB 时钟生成）',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（DB 时钟生成）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_key (tool_key),
    KEY idx_server_status (server_id, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='平台工具注册表（MCP 工具的登记台账：内置种子+外部同步草稿审核）';

-- 工具种子两行（description/input_schema 与 MCP 契约逐字同步；name 为管理页显示名；
-- server_id=1 内置 SYSTEM 行、status=1 已发布——与现状行为等价，这两工具本就「已审核」）
INSERT INTO ai_tool (tool_key, name, description, input_schema, required_perms, server_id, status) VALUES
('system_query_users', '查询系统用户',
 '分页查询系统用户列表（只读）。返回账号、昵称、部门、状态等；不含密码。需要权限 system:user:list。',
 '{"type": "object", "properties": {"pageNum": {"type": "integer", "minimum": 1, "default": 1, "description": "页码，从 1 开始"}, "pageSize": {"type": "integer", "minimum": 1, "maximum": 200, "default": 10, "description": "每页条数，上限 200（对齐分页插件 maxLimit）"}}, "additionalProperties": false}',
 'system:user:list', 1, 1),
('system_query_dict_items', '查询字典项',
 '按字典键查询启用中的字典项（只读）。返回 value（存库值）/label（展示标签）/sort（排序）。登录即可调用，无需特定权限。',
 '{"type": "object", "properties": {"dictKey": {"type": "string", "minLength": 1, "description": "字典类型键，如 user_status"}}, "required": ["dictKey"], "additionalProperties": false}',
 '', 1, 1);

-- 智能体-工具绑定（纯关系表，沿 ai_kb_grant 先例：物理删除、无 deleted 列、无 update 审计列）：
-- 保存 = 按 agent_id 全删全插（同一事务）；uk_agent_tool(agent_id, tool_id) 前缀 agent_id
-- 兼命中唯一高频查询「按 agent 查绑定清单」（编辑回显 + 运行装配）；
-- 按 tool_id 反查（工具下线找受影响 agent）当前无消费方，不建索引。
CREATE TABLE ai_agent_tool
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '绑定ID（主键）',
    agent_id    BIGINT      NOT NULL COMMENT '智能体ID（ai_agent.id，弱引用不建外键）',
    tool_id     BIGINT      NOT NULL COMMENT '工具ID（ai_tool.id，弱引用不建外键）',
    create_by   VARCHAR(30) NULL COMMENT '绑定操作人账号',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '绑定时间（DB 时钟生成，应用层不传时间值）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_agent_tool (agent_id, tool_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='智能体-工具绑定（纯关系表：物理删除，随保存全删全插）';

-- 智能体-MCP服务绑定（2026-10-31 server 级订阅绑定轮新增；增量脚本
-- 2026-10-31-agent-mcp-server-binding.sql）：纯关系表，沿 ai_agent_tool 先例物理删除、
-- 保存 = 按 agent_id 全删全插；订阅语义——绑定后该 server 名下已发布工具自动进入
-- agent 工具面（装配每运行查库，工具同步/审核的缓存失效联动覆盖自动生效）。
-- uk_agent_server(agent_id, server_id) 三用：保存全删全插 DELETE 前缀 / 装配子查询
-- 前缀反向扫 / 并发双插查重兜底；按 server 反查暂无消费方不建索引（需求出现再增量）。
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

-- 智能体会话元数据（对齐 ai_chat_session 先例；消息在 Redis Cluster 键 agent:messages:{id}）：
-- idx_agent_user(agent_id, user_id, deleted) 命中唯一高频查询「某用户在某智能体下的会话列表
-- （id 倒序）」——二级索引隐式追加主键 id，等值前缀后沿 id 逆序扫描免 filesort；
-- 跨用户会话查询当前无（隐私边界，不做）；agent/user 弱引用不建外键（主数据在 ai_agent/cloud_system）。
CREATE TABLE ai_agent_session
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '会话ID（主键，Redis 消息键 agent:messages:{id} 同源）',
    agent_id    BIGINT      NOT NULL COMMENT '智能体ID（ai_agent.id，弱引用不建外键；停用/已删智能体的历史会话仍可列表回显删除）',
    user_id     BIGINT      NOT NULL COMMENT '归属用户ID（sys_user.id，网关 X-User-Id 注入；主数据在 cloud_system 库，弱引用不建外键）',
    title       VARCHAR(64) NOT NULL DEFAULT '新对话' COMMENT '会话标题（默认"新对话"；首问由服务端取前16字符更新）',
    create_by   VARCHAR(30) NULL COMMENT '创建人账号',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（DB 时钟生成，应用层不传时间）',
    update_by   VARCHAR(30) NULL COMMENT '更新人',
    update_time DATETIME NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    KEY idx_agent_user (agent_id, user_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='智能体会话元数据（消息在 Redis Cluster，键 agent:messages:{id}）';

-- 运行审计（append-only：仅 insert 无 update/delete；清理任务随量级出现再议）：
-- status 0=成功 1=失败（失败=异常终止；模型对工具错误的正常转述属成功）；
-- idx_agent_time(agent_id, create_time) 命中管理页「按智能体查运行历史（分页，时间倒序）」唯一查询路径。
CREATE TABLE ai_agent_run_log
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '日志ID（主键）',
    agent_id    BIGINT       NOT NULL COMMENT '智能体ID（ai_agent.id，弱引用不建外键；智能体软删后审计保留）',
    session_id  BIGINT       NOT NULL COMMENT '会话ID（ai_agent_session.id，弱引用不建外键）',
    user_id     BIGINT       NOT NULL COMMENT '运行者用户ID（sys_user.id，主数据在 cloud_system 库，弱引用不建外键）',
    status      TINYINT      NOT NULL COMMENT '运行状态：0=成功 1=失败（失败=异常终止；模型对工具错误的正常转述属成功）',
    tool_calls  TEXT         NOT NULL COMMENT '工具调用明细 JSON 数组 [{tool_key,arguments,ok,error}]（无工具运行为空数组 []）',
    error_msg   VARCHAR(512) NULL COMMENT '失败摘要（不含 API key 与上游原文）',
    duration_ms INT          NOT NULL COMMENT '耗时毫秒',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '运行结束落库时间（DB 时钟生成）',
    PRIMARY KEY (id),
    KEY idx_agent_time (agent_id, create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='智能体运行审计（append-only：运行者/状态/工具调用明细/耗时/失败摘要）';

-- ========== MCP Server 配置化管理（2026-10-30 MCP server 管理轮，增量脚本 2026-10-30-mcp-server-mgmt.sql） ==========
-- 契约 docs/superpowers/contracts/2026-10-30-mcp-server-mgmt-api.md；设计 2026-10-30-mcp-server-mgmt-design.md §4

-- MCP 服务注册表：外部/内置 server 连接配置的真相源（非敏感字段入库；凭据真值在 .env 键
-- CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>，DB 只存键名在 credential_headers_json——红线）。
-- uk_name 兼查重与重名兜底（软删墓碑占键时新建同名报业务码，沿 ai_agent.uk_name 先例）；
-- name 兼凭据 env 键段与管理命名空间，'SYSTEM'/'RAG' 为平台内置服务占用；
-- 列表 ORDER BY id DESC 全表扫管理面规模（个位数到十位数）接受，不建额外索引（同 ai_agent 取舍）。
CREATE TABLE ai_mcp_server
(
    id                     BIGINT       NOT NULL AUTO_INCREMENT COMMENT 'MCP服务ID（主键）',
    name                   VARCHAR(30)  NOT NULL COMMENT '服务名（大写蛇形，全局唯一；兼作凭据env键段 CLOUD_BASE_AI_MCP__SERVERS__<NAME>__*；SYSTEM/RAG=平台内置服务占用）',
    description            VARCHAR(512) NULL COMMENT '描述（管理面备注，可选）',
    url                    VARCHAR(512) NOT NULL COMMENT 'MCP端点地址（http/sse 的完整 URL；内置服务默认 http://127.0.0.1:9202/mcp）',
    transport              VARCHAR(20)  NOT NULL DEFAULT 'http' COMMENT '传输类型：http=streamable HTTP sse=SSE 事件流（stdio 本轮不开放，管理面拒绝保存）',
    headers_json           TEXT         NULL COMMENT '非敏感静态请求头 JSON（字面值入库；secret/token 一律走 credential_headers_json 引用 .env，红线）',
    credential_headers_json TEXT        NULL COMMENT '凭据请求头 JSON：{header名: .env键名}——只存键名不存真值；键名须匹配 ^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$',
    timeout_ms             INT          NOT NULL DEFAULT 30000 COMMENT '单次调用超时毫秒（1000..120000）',
    is_builtin             TINYINT      NOT NULL DEFAULT 0 COMMENT '内置标记：1=平台内置 MCP 服务（cloud-system、cloud-base-rag 等平台内服务；不可删不可改名；调用头自动注入用户JWT+服务密钥）',
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

-- 内置行两行（is_builtin=1 不可删不可改名；url 部署差异时管理员可编辑）：
-- SYSTEM=cloud-system /mcp；RAG=cloud-base-rag /mcp（rag_search_kb 检索工具，
-- 2026-11-13 轮）。内置行的调用头自动注入当次用户 JWT + X-MCP-Client-Secret
-- （.env 既有键），credential_headers_json 恒 NULL。
INSERT INTO ai_mcp_server (id, name, description, url, transport, timeout_ms, is_builtin, status) VALUES
(1, 'SYSTEM', '平台内置 cloud-system MCP 服务', 'http://127.0.0.1:9202/mcp', 'http', 30000, 1, 0),
(2, 'RAG', '平台内置 cloud-base-rag 检索服务', 'http://127.0.0.1:8011/mcp', 'http', 30000, 1, 0);

-- ========== MCP Server 用户级权限（2026-10-30 MCP server 权限轮，增量脚本 2026-10-30-mcp-server-permission.sql） ==========
-- 契约 docs/superpowers/contracts/2026-10-30-mcp-server-permission-api.md；设计 2026-10-30-mcp-server-permission-design.md §1.1

-- MCP 服务用户授权（纯关系表，沿 ai_kb_grant 先例：物理删除、无 deleted 列、无 update
-- 审计列；保存 = 按 server_id 全删全插，同一事务由 service 收口）。
-- 默认拒绝 + 管理豁免：无授权行的 server 对运行用户不可用；持 ai:mcp:list 者豁免
-- （能管即能用，豁免只免本授权门——工具级 required_perms 门对所有人照常生效）。
-- uk_server_subject 兼查重与授权回显（server_id 前缀）；idx_subject 命中运行面主路径
-- 「按主体反查可用 server id 集」（三分支 OR + distinct，每运行至多一次）；
-- 主体弱引用不建外键（主数据在 cloud_system 库），失效授权永不命中（自愈语义）。
CREATE TABLE ai_mcp_server_grant
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '授权ID（主键）',
    server_id    BIGINT       NOT NULL COMMENT 'MCP服务ID（ai_mcp_server.id，弱引用不建外键；内置 SYSTEM 行同样可授权）',
    subject_type TINYINT      NOT NULL COMMENT '主体类型：0=角色 1=部门 2=用户',
    subject_id   BIGINT       NOT NULL COMMENT '主体ID（sys_role.id / sys_dept.id / sys_user.id，主数据在 cloud_system 库）',
    create_by    VARCHAR(30)  NULL COMMENT '授权操作人账号',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间（DB 时钟口径，应用层不传时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_server_subject (server_id, subject_type, subject_id),
    KEY idx_subject (subject_type, subject_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='MCP服务用户授权（纯关系表：物理删除，随保存全删全插；默认拒绝+管理豁免，运行面实时求值）';

-- 初始授权两行：SYSTEM 与 RAG 两个内置 server 各授内置管理员角色（sys_role id=1，
-- 基线种子恒存在）。管理员本就持 ai:mcp:list 豁免，此行价值是授权状态在面板可见、
-- 豁免口径收紧时不回退管理员可用性（设计 §1.3 取舍）；业务用户上线后需管理员手工
-- 授权（默认拒绝的已接受代价）。
INSERT INTO ai_mcp_server_grant (server_id, subject_type, subject_id) VALUES (1, 0, 1), (2, 0, 1);
