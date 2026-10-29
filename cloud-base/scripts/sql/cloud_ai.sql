CREATE
DATABASE IF NOT EXISTS cloud_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE
cloud_ai;

-- 知识库（索引取舍见 2026-10-13-ai-orm-dao-design.md D8：
--   uk_name=name 查重+墓碑占键兜底（不含 deleted，沿 sys_user uk_account 先例）；
--   file_count/file_chunk_count=库级冗余计数，维护责任在编排层，DEFAULT 0 支持空库省略列）
CREATE TABLE ai_knowledge_base
(
    id               BIGINT      NOT NULL AUTO_INCREMENT COMMENT '知识库ID（主键）',
    name             VARCHAR(50) NOT NULL COMMENT '知识库名称',
    file_count       INT         NOT NULL DEFAULT 0 COMMENT '文档数量',
    file_chunk_count INT         NOT NULL DEFAULT 0 COMMENT '文档分片总数',
    description      VARCHAR(512) NULL COMMENT '描述信息',
    collection_name  VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'Milvus集合名（服务端派生kb_{id}，一库一集合；换embedding维度时改本列切kb_{id}_v2新集合后重嵌入）',
    create_by        VARCHAR(30) NULL COMMENT '创建人',
    create_time      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by        VARCHAR(30) NULL COMMENT '更新人',
    update_time      DATETIME NULL COMMENT '更新时间',
    deleted          TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI知识库';

-- 知识库文件（索引取舍：uk_kb_file_path=同库同路径重复登记防御（2048 bytes，large_prefix ON 时整列，
--   OFF 退 (kb_id, file_path(191)) 前缀）；idx_kb_id 命中按库列文件的 kb_id+deleted 过滤，既有不动；
--   file_chunk_size/overlap/split_type=chunking 参数的文件级快照，ingestion 编排时写入）
CREATE TABLE ai_knowledge_base_files
(
    id                 BIGINT       NOT NULL AUTO_INCREMENT COMMENT '文件ID（主键）',
    kb_id              BIGINT       NOT NULL COMMENT '知识库ID',
    file_path          VARCHAR(512) NOT NULL COMMENT '文档地址',
    file_name          VARCHAR(50)  NOT NULL COMMENT '文档名称',
    file_chunk_count   INT          NOT NULL COMMENT '文档分片数',
    file_chunk_size    INT          NOT NULL COMMENT '分片大小',
    file_chunk_overlap INT          NOT NULL COMMENT '分片重叠大小',
    file_split_type    VARCHAR(50)  NOT NULL COMMENT '分片类型：parent-child（父子分片）/ recursion（递归分片）',
    description        VARCHAR(512) NULL COMMENT '描述信息',
    create_by          VARCHAR(30) NULL COMMENT '创建人',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by          VARCHAR(30) NULL COMMENT '更新人',
    update_time        DATETIME NULL COMMENT '更新时间',
    deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_file_path (kb_id, file_path),
    -- ON 不可用时替换上一行为：UNIQUE KEY uk_kb_file_path (kb_id, file_path(191)),
    KEY                idx_kb_id (kb_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI知识库文件';

-- 文档分块表（索引取舍：idx_kb_parent 的 kb_id 前缀命中按库列块/按库级联删；
--   idx_file 命中文件级联删与按文件列块（file_id 无索引则级联删全表扫）；
--   父子两层同表 chunk_type 区分，向量在库外按 id 关联——MySQL 5.7 无向量检索，本表是块文本的真相源）
CREATE TABLE ai_kb_chunk
(
    id              BIGINT  NOT NULL AUTO_INCREMENT COMMENT '主键（子块进向量库时当 doc_id 用）',
    kb_id           BIGINT  NOT NULL COMMENT '所属知识库（ai_knowledge_base.id）',
    file_id         BIGINT           DEFAULT NULL COMMENT '来源文件（ai_knowledge_base_files.id）',
    chunk_id        VARCHAR(50)      DEFAULT NULL COMMENT '文件块uuid',
    parent_chunk_id VARCHAR(50)      DEFAULT NULL COMMENT '父文件块uuid',
    chunk_type      TINYINT NOT NULL DEFAULT 0 COMMENT '0=父块 1=子块',
    seq_no          INT     NOT NULL DEFAULT 0 COMMENT '块在原文中的顺序号',
    content         TEXT    NOT NULL COMMENT '块文本',
    create_by       VARCHAR(30)      DEFAULT NULL COMMENT '创建人',
    create_time     DATETIME         DEFAULT NULL COMMENT '创建时间',
    update_by       VARCHAR(30)      DEFAULT NULL COMMENT '更新人',
    update_time     DATETIME         DEFAULT NULL COMMENT '更新时间',
    deleted         TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY             idx_kb_parent (kb_id, chunk_id, parent_chunk_id),
    KEY             idx_file (file_id)
) ENGINE = InnoDB COMMENT = '知识库块（父子两层，向量在库外按 id 关联）';

-- 知识库授权（2026-10-18 知识库权限轮，设计 2026-10-18-kb-permission-design.md D4：
--   纯关系表沿 sys_user_role/sys_role_menu 先例——物理删除、无 deleted 列、无 update 审计列；
--   uk_kb_subject 兼查重与配置回显（按 kb_id 前缀取全量授权行）；
--   idx_subject 命中可见性求值主路径「按主体反查 kb_id」；
--   subject 弱引用不建外键（主数据在 cloud_system 库），失效授权永不命中（自愈语义）；
--   create_time 取 DB 时钟 DEFAULT CURRENT_TIMESTAMP（应用层插入不传时间值））
CREATE TABLE ai_kb_grant
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '授权ID（主键）',
    kb_id        BIGINT       NOT NULL COMMENT '知识库ID（ai_knowledge_base.id，弱引用不建外键）',
    subject_type TINYINT      NOT NULL COMMENT '主体类型：0=角色 1=部门 2=用户',
    subject_id   BIGINT       NOT NULL COMMENT '主体ID（sys_role.id / sys_dept.id / sys_user.id，主数据在 cloud_system 库）',
    create_by    VARCHAR(30) NULL COMMENT '授权操作人账号',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间（DB 时钟 SQL NOW() 口径，应用层不传时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_subject (kb_id, subject_type, subject_id),
    KEY idx_subject (subject_type, subject_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='AI 知识库授权（纯关系表：物理删除，随保存全删全插、随知识库删除级联清理）';

-- AI 对话会话（2026-10-19 会话管理轮，设计 2026-10-19-ai-chat-session-design.md §3.1：
--   会话元数据真相源（消息在 Redis Cluster，键 rag:messages:{id}）；
--   idx_user 命中「按用户列活会话」唯一高频查询——二级索引隐式追加主键 id，等值前缀后
--   沿 id 逆序扫描免 filesort；按 session_id 点查走主键；
--   无唯一键：会话是私有资源，重名无害无查重需求（与 kb 的 uk_name 对照）；
--   user_id 弱引用不建外键（主数据在 cloud_system 库））
CREATE TABLE ai_chat_session
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '会话ID（主键）',
    user_id     BIGINT      NOT NULL COMMENT '归属用户ID（sys_user.id，网关 X-User-Id 注入；主数据在 cloud_system 库，弱引用不建外键）',
    title       VARCHAR(64) NOT NULL DEFAULT '新对话' COMMENT '会话标题（默认"新对话"；首问由服务端取前16字符更新）',
    create_by   VARCHAR(30) NULL COMMENT '创建人账号',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（DB 时钟生成，应用层不传时间）',
    update_by   VARCHAR(30) NULL COMMENT '更新人账号',
    update_time DATETIME NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    KEY idx_user (user_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='AI 对话会话（元数据真相源；消息在 Redis Cluster，键 rag:messages:{id}）';

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
