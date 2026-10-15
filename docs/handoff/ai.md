# AI 基座（cloud-base-ai）当前欠账

只记**仍未兑现**的事项，兑现后删除条目。规则见 `docs/multi-agent-dev-sop.md`「验收挂起规则」。

## 向量写入与 MySQL 写入不在同一事务

- **待办**：设计入库的一致性补偿。`upload_file_by_kb` 先写 MySQL 后写 Milvus，Milvus 失败时 MySQL 会回滚；但反过来 MySQL 提交失败时，Milvus 里已有孤儿向量。
- **命令**：无（设计项）。候选：先写 Milvus 再提交 MySQL，失败时按 id 删除向量；或用 upsert 保证重试幂等。
- **预期**：重复上传同一文件不产生重复向量，失败路径无孤儿向量。
- **登记日期**：2026-10-15

## Milvus collection 名硬编码 test123

- **待办**：`cloud-base-ai/src/cloud_base_ai/rag/service.py` 的 `upload_file_by_kb` 内 collection 名写死 `"test123"`——所有知识库共用一个向量集合，无库级隔离；命名策略（如按 kb_id 建集合）待 ingestion 轮定夺。
- **命令**：无（设计项；改动涉及既有向量数据的迁移/兼容）。
- **预期**：collection 名可配置或按知识库隔离，改名不丢既有数据。
- **登记日期**：2026-10-15

## 已删知识库/文件的孤儿向量未清理

- **待办**：删除知识库走 MySQL 三表（ai_kb/ai_kb_file/ai_kb_chunk）软删级联，Milvus 物理向量不动
  （collection "test123" 全库共用、向量按 chunk pk 存）。检索轮做 collection 库级隔离命名策略时，
  按 deleted=1 的 chunk id 统一清理物理向量并迁移存量。
- **命令**：无（设计项，随检索轮 collection 命名策略一并定夺）。
- **预期**：孤儿向量不进入检索结果（检索上线前治理完成）；已删数据可按软删标记追溯。
- **登记日期**：2026-10-15（列表管理轮，c2 软删记档口径拍板）
