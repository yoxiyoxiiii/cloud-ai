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
