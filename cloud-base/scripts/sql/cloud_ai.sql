CREATE DATABASE IF NOT EXISTS cloud_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE cloud_ai;

-- 知识库（索引取舍见 2026-10-13-ai-orm-dao-design.md D8：
--   uk_name=name 查重+墓碑占键兜底（不含 deleted，沿 sys_user uk_account 先例）；
--   file_count/file_chunk_count=库级冗余计数，维护责任在编排层，DEFAULT 0 支持空库省略列）
CREATE TABLE ai_knowledge_base
(
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '知识库ID（主键）',
    name             VARCHAR(50)  NOT NULL COMMENT '知识库名称',
    file_count       INT          NOT NULL DEFAULT 0 COMMENT '文档数量',
    file_chunk_count INT          NOT NULL DEFAULT 0 COMMENT '文档分片总数',
    description      VARCHAR(512) NULL COMMENT '描述信息',
    create_by        VARCHAR(30)  NULL COMMENT '创建人',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by        VARCHAR(30)  NULL COMMENT '更新人',
    update_time      DATETIME     NULL COMMENT '更新时间',
    deleted          TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
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
    create_by          VARCHAR(30)  NULL COMMENT '创建人',
    create_time        DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by          VARCHAR(30)  NULL COMMENT '更新人',
    update_time        DATETIME     NULL COMMENT '更新时间',
    deleted            TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_kb_file_path (kb_id, file_path),
    -- ON 不可用时替换上一行为：UNIQUE KEY uk_kb_file_path (kb_id, file_path(191)),
    KEY idx_kb_id (kb_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='AI知识库文件';
