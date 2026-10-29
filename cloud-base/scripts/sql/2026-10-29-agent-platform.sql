-- =====================================================================
-- 平台 Agent 管理五表 + 工具/菜单种子（2026-10-29 agent 平台轮）
-- 契约 docs/superpowers/contracts/2026-10-29-agent-platform-api.md（§2/§6.1）
-- 设计 docs/superpowers/specs/2026-10-29-agent-platform-design.md §2
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) cloud_ai: SHOW TABLES LIKE 'ai_agent%'; -- 应 0 行（五表均未建）
--   2) cloud_system: SELECT id FROM sys_menu WHERE id IN (186,187,188,189,190); -- 应 0 行
--   3) cloud_system: SELECT id FROM sys_menu WHERE id = 18; -- 应 1 行（父目录「AI 助手」在）
-- 幂等口径：显式 id INSERT 不幂等（前置断言 2 防占用）；绑定段撞 uk_role_menu 报错属预期。
-- 基线同步：cloud_ai.sql / cloud_system.sql 已同步本文件语义——新环境直接跑基线，无需本增量。
-- 权限快照时效：菜单落库后 admin 须重新登录（或 refresh），perms 快照含 ai:agent:* 才生效
--   （快照登录时定格，落库前登录的会话不含新权限）。
-- =====================================================================
USE cloud_ai;

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

-- 工具注册表（字典表，种子手工登记，管理面只读、不提供编辑）：
-- description 是注入 LLM 的工具 doc、input_schema 是服务端定义的参数面（模型不能自造参数），
-- 两列与 required_perms 逐字同步 MCP 契约 2026-10-17-mcp-tools-api.md §3.1/§3.2、§4.1/§4.2、
-- §2 权限档位（工具名/权限档位变更须先修契约再改种子）；
-- 无软删无 update 审计（种子数据）；查询仅全量 list 走全表，量级个位数，不建二级索引。
CREATE TABLE ai_tool
(
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '工具ID（主键）',
    tool_key       VARCHAR(100)  NOT NULL COMMENT '工具标识（对齐 MCP 工具名，如 system_query_users）',
    name           VARCHAR(50)   NOT NULL COMMENT '显示名（管理页勾选展示）',
    description    VARCHAR(1024) NOT NULL COMMENT '工具描述（注入 LLM 的工具 doc；与 MCP 契约逐字同步）',
    input_schema   TEXT          NOT NULL COMMENT '入参 JSON Schema 文本（运行动态构造 pydantic 入参模型）',
    required_perms VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '所需权限标识（逗号分隔，空=登录即可；运行门按 ⊆ 用户 perms 过滤）',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登记时间（DB 时钟生成）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_key (tool_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='平台工具注册表（MCP 工具的登记台账，种子驱动）';

-- 工具种子两行（description/input_schema 与 MCP 契约逐字同步；name 为管理页显示名）
INSERT INTO ai_tool (tool_key, name, description, input_schema, required_perms) VALUES
('system_query_users', '查询系统用户',
 '分页查询系统用户列表（只读）。返回账号、昵称、部门、状态等；不含密码。需要权限 system:user:list。',
 '{"type": "object", "properties": {"pageNum": {"type": "integer", "minimum": 1, "default": 1, "description": "页码，从 1 开始"}, "pageSize": {"type": "integer", "minimum": 1, "maximum": 200, "default": 10, "description": "每页条数，上限 200（对齐分页插件 maxLimit）"}}, "additionalProperties": false}',
 'system:user:list'),
('system_query_dict_items', '查询字典项',
 '按字典键查询启用中的字典项（只读）。返回 value（存库值）/label（展示标签）/sort（排序）。登录即可调用，无需特定权限。',
 '{"type": "object", "properties": {"dictKey": {"type": "string", "minLength": 1, "description": "字典类型键，如 user_status"}}, "required": ["dictKey"], "additionalProperties": false}',
 '');

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

-- 运行审计（append-only：仅 insert 无 update/delete；清理任务随量级出现再议，见实施计划移交备忘）：
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

-- =====================================================================
-- 菜单种子（cloud_system 库；契约 §6.1——管理面 186-189 挂 186「智能体管理」，
-- 190「智能体对话」与 186 同级挂 18「AI 助手」下；181-185 已占，id 接续 186 起）
-- =====================================================================
USE cloud_system;

INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(186, 18,  '智能体管理', 'ai:agent:list',   'C', '/agent/manage', 'Monitor',       3, 1, NOW()),
(187, 186, '新增',       'ai:agent:add',    'F', '',              '',              1, 1, NOW()),
(188, 186, '编辑',       'ai:agent:update', 'F', '',              '',              2, 1, NOW()),
(189, 186, '删除',       'ai:agent:delete', 'F', '',              '',              3, 1, NOW()),
(190, 18,  '智能体对话', 'ai:agent:run',    'C', '/agent/chat',   'ChatLineSquare', 4, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (186, 187, 188, 189, 190);

commit;
