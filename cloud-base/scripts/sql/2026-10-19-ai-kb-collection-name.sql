-- 向量集合绑定列（2026-10-19 集合隔离轮，设计 docs/superpowers/specs/2026-10-19-ai-kb-collection-isolation-design.md §4）
-- 一库一集合：集合名由服务端按主键派生（kb_{id}），用户与前端不感知。
-- 索引取舍：零新增——该列无独立查询路径，唯一消费方式是主键查行随行带出；不加 uk：
--   单一写方派生（自增主键不复用、drop 后名字不回收），无撞名路径。
-- 先 DDL 后部署：旧代码不感知新列可继续跑；新代码 SELECT 带新列、表无列即报错。
ALTER TABLE ai_knowledge_base
    ADD COLUMN collection_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'Milvus集合名（服务端派生kb_{id}，一库一集合；换embedding维度时改本列切kb_{id}_v2新集合后重嵌入）' AFTER description;

-- 全量回填（含软删行，统一公式无特判；软删行指向已随共用集合 drop 消失的集合无碍——终态行无人读）
UPDATE ai_knowledge_base SET collection_name = CONCAT('kb_', id);
