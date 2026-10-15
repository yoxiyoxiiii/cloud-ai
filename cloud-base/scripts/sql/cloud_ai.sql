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
