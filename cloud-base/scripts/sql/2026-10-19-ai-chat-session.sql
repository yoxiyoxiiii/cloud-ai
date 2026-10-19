-- AI 对话会话（2026-10-19 会话管理轮，设计 docs/superpowers/specs/2026-10-19-ai-chat-session-design.md §3.1）
-- 增量脚本：会话元数据真相源（消息在 Redis Cluster，键 rag:messages:{id}）。
-- 索引取舍：idx_user(user_id, deleted) 命中全表唯一高频查询「列表：WHERE user_id=? AND deleted=0
--   ORDER BY id DESC LIMIT」——InnoDB 二级索引隐式追加主键 id，等值前缀后可沿 id 逆序扫描免
--   filesort，无需显式三列索引；按 session_id 的点查走主键。
-- 无唯一键：会话是私有资源，重名无害、无查重需求（与 kb 的 uk_name 形成对照——kb 名称是全局
--   管理命名空间，会话标题只是展示文案）。
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
