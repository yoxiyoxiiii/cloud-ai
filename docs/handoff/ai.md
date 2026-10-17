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

- **待办**：`cloud-base-ai/src/cloud_base_ai/rag/service.py` 内 collection 名写死 `"test123"`——
  所有知识库共用一个向量集合，无库级隔离；命名策略（如按 kb_id 建集合）待检索轮定夺。
  字面量现有两处：`upload_file_by_kb` 与重嵌入的 `_swap_vectors`（2026-10-16 轮新增），收敛时一并处理。
- **命令**：无（设计项；改动涉及既有向量数据的迁移/兼容）。
- **预期**：collection 名可配置或按知识库隔离，改名不丢既有数据。
- **登记日期**：2026-10-15；2026-10-16（重嵌入轮补记第二处字面量）

## 已删知识库/文件的孤儿向量与孤儿原件对象未清理

- **待办**：删除知识库走 MySQL 三表（ai_kb/ai_kb_file/ai_kb_chunk）软删级联，Milvus 物理向量与
  MinIO 原件对象均不动（collection "test123" 全库共用、向量按 chunk pk 存；原件对象键即
  ai_knowledge_base_files.file_path）。检索轮做 collection 库级隔离命名策略时，按 deleted=1 的
  chunk id 统一清理物理向量并迁移存量，同时按 file_path 清理 MinIO 孤儿原件对象。
- **命令**：无（设计项，随检索轮 collection 命名策略一并定夺）。
- **预期**：孤儿向量不进入检索结果（检索上线前治理完成）；已删数据可按软删标记追溯。
- **登记日期**：2026-10-15（列表管理轮，c2 软删记档口径拍板）；2026-10-16（MinIO 原件轮补记对象清理）

## 重嵌入的父块分片大小未开放

- **待办**：重新向量化端点的父块分片大小固定 500（`service.py` 常量 `PARENT_CHUNK_SIZE_FIXED`），
  文件表无父块参数快照列——开放需 DDL 加列 + 契约/前端弹窗同步（子块两参已有快照列与弹窗预填先例）。
- **命令**：无（设计项，待真实需求出现再评估）。
- **预期**：若开放，入参/快照/弹窗三处与子块两参同构，上传链同步参数化（上传侧目前写死 128/20/500）。
- **登记日期**：2026-10-16（重新向量化轮）

## 重嵌入的跨会话并发防重未做

- **待办**：同一文件的并发重嵌入不设防。两次并发交错的理论最坏情形留一代「中间代」向量
  （其 MySQL 行已被后一次运行软删、但两次的删除清单都不含它）——由该文件**下一次重嵌入**的
  历代清单收敛；在此之前该代向量命中后被软删过滤，不产生脏内容（残余形态详见当轮 spec §5）。
  若未来要防：候选是文件行状态列条件写（需 DDL）或进程内 per-file 锁（多实例失效）。
- **命令**：无（设计项；同步单文件形态下并发概率低，拍板接受）。
- **预期**：并发残留在有限次重嵌入内自愈，检索侧无脏内容透出。
- **登记日期**：2026-10-16（重新向量化轮）

## 重嵌入的批量/异步化未做

- **待办**：当前为同步单文件（前端 loading + 120s 超时）；KB 级批量重嵌入与后台任务模型
  随检索轮评估（批量场景下同步等待不可行，需任务表+进度查询或消息驱动）。
- **命令**：无（设计项）。
- **预期**：批量入口复用本轮单文件编排（世代置换语义不变），异步化引入后 5008 重试收敛路径保持。
- **登记日期**：2026-10-16（重新向量化轮）

## MCP 通道已接入但对话编排未接（工具建好了，LLM 还没用上）

- **待办**：对话链路把 `visible_tools` 清单注入模型并回填工具调用结果（langchain.mcp MCPAdapter
  适配）——本轮只交付通道原语（`mcp/client.py`/`mcp/perm.py`）与错误语义（5009/5010）。
- **外部系统**：cloud-system MCP server（streamable，端点 :9202/mcp）。**寻址取舍**：AI 侧直连
  9202 不走网关——网关刻意屏蔽 `/system/mcp`（服务间面不外露），内网直连是既定设计；
  双主体头 `X-MCP-Client-Secret`（值同 Nacos，本地 .env `CLOUD_BASE_AI_MCP__CLIENT_SECRET`）
  + `Authorization: Bearer <用户 JWT>`。
- **命令**（真连接冒烟，仓库根执行）：
  `C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python cloud-base-ai/scripts/smoke/smoke_mcp_system.py`
  （前置：sso/system/gateway/Redis/Nacos 在运行）
- **预期**：编排轮接入后工具面经契约 `2026-10-17-mcp-tools-api.md` §5 的 TOOL_PERMS 呈现，
  服务端最终权限裁决语义不变（PERMISSION_DENIED 走 isError 分支）。
- **登记日期**：2026-10-17（MCP server 轮 Phase 3）
