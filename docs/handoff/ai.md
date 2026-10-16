# AI 基座（cloud-base-ai）当前欠账

只记**仍未兑现**的事项，兑现后删除条目。规则见 `docs/multi-agent-dev-sop.md`「验收挂起规则」。

## 外部存储写入与 MySQL 写入不在同一事务（Milvus 向量 + MinIO 原件对象）

- **待办**：设计上传链的一致性补偿。`upload_file_by_kb` 写入顺序为 MinIO 原件 → MySQL 三表 → Milvus 向量：
  前置失败（MinIO 写不进）异常上抛、事务回滚，此时零 DB 写无残留；但任一后置步骤失败（MySQL 提交失败 /
  Milvus 写失败）时，已写入的外部数据不回滚——MinIO 留孤儿原件对象、Milvus 留孤儿向量。
- **命令**：无（设计项）。候选：按 file_path（对象键）反向删除 MinIO 对象 + 按 id 删除向量做补偿；
  或对账任务按 file 行反查外部存储清理无主对象。
- **预期**：失败路径无孤儿对象/孤儿向量；随检索轮 collection 隔离治理一并定夺。
- **登记日期**：2026-10-15；2026-10-16（MinIO 原件轮扩条：原件对象并入同族欠账）

## Milvus collection 名硬编码 test123

- **待办**：`cloud-base-ai/src/cloud_base_ai/rag/service.py` 的 `upload_file_by_kb` 内 collection 名写死 `"test123"`——所有知识库共用一个向量集合，无库级隔离；命名策略（如按 kb_id 建集合）待 ingestion 轮定夺。
- **命令**：无（设计项；改动涉及既有向量数据的迁移/兼容）。
- **预期**：collection 名可配置或按知识库隔离，改名不丢既有数据。
- **登记日期**：2026-10-15

## 已删知识库/文件的孤儿向量与孤儿原件对象未清理

- **待办**：删除知识库走 MySQL 三表（ai_kb/ai_kb_file/ai_kb_chunk）软删级联，Milvus 物理向量与
  MinIO 原件对象均不动（collection "test123" 全库共用、向量按 chunk pk 存；原件对象键即
  ai_knowledge_base_files.file_path）。检索轮做 collection 库级隔离命名策略时，按 deleted=1 的
  chunk id 统一清理物理向量并迁移存量，同时按 file_path 清理 MinIO 孤儿原件对象。
- **命令**：无（设计项，随检索轮 collection 命名策略一并定夺）。
- **预期**：孤儿向量不进入检索结果（检索上线前治理完成）；已删数据可按软删标记追溯。
- **登记日期**：2026-10-15（列表管理轮，c2 软删记档口径拍板）；2026-10-16（MinIO 原件轮补记对象清理）
