-- AI 知识库授权表增量脚本（2026-10-18 知识库权限轮）。
-- 幂等口径沿既有脚本：表存在则跳过（重复落库报 uk/表已存在不视为错误）；
-- 基线 cloud_ai.sql 已同步同表定义，环境重建走基线。
-- 索引取舍：uk_kb_subject 兼查重与配置回显（kb_id 前缀）；idx_subject 命中可见性求值
--   「按主体反查 kb_id」主路径；ai_knowledge_base.create_by 不加索引（库表量级十百级，主键倒序扫+IN 过滤，收益为零）。
USE cloud_ai;

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
