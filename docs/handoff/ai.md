# AI 基座（cloud-base-ai）当前欠账

只记**仍未兑现**的事项，兑现后删除条目。规则见 `docs/multi-agent-dev-sop.md`「验收挂起规则」。

## 知识库菜单种子 182 未落库

- **待办**：把「知识库」菜单种子（perms `ai:kb:add`）写入 `cloud_system` 库，并回查绑定。
- **命令**：脚本已备于 `cloud-base/scripts/sql/2026-10-14-ai-kb-create.sql`，含前置断言头注（182 空闲、父菜单 18 存在）。用 pymysql 执行（见 CLAUDE.md「对 MySQL 读写中文一律 pymysql 优先」）。
- **预期**：`sys_menu` 出现 id=182，`perms=ai:kb:add`、`path=/rag/kb`，并绑定到 admin 角色。落库后 admin 需重新登录，权限快照才会刷新。
- **缺什么**：MySQL（此前因机器重启停服）。
- **登记日期**：2026-10-14

## 知识库创建端点未做双路实证

- **待办**：`POST /rag/kb` 的运行时验证——本地直连 8010 四个分支 + 经网关全链路。
- **命令**：直连需手工带 `X-User-Perms: ai:kb:add` 与 `X-User-Account: admin` 请求头；网关路径先登录 admin。请求体全 ASCII。
- **预期**：200 返回新 id、重名返回 5003、无权限返回 403、空 name 返回 1001；库里 `create_by=admin`。结果回填 `docs/superpowers/contracts/2026-10-14-ai-kb-api.md` 第 4 节。
- **缺什么**：MySQL、Nacos、网关。
- **登记日期**：2026-10-14

## 分块表 ai_kb_chunk 未在真库建表

- **待办**：把 `cloud-base/scripts/sql/cloud_ai.sql` 中 `ai_kb_chunk` 段落库，并用真库验证 DAO。
- **命令**：先用 pymysql 查 `information_schema` 确认表不存在，再执行该段 DDL；之后上传一个文件走完整入库链路。
- **预期**：表含 `idx_kb_parent`、`idx_file` 两个索引；上传后 MySQL 有父块与子块行，Milvus 的 `chunk` 集合有对应子块向量。
- **缺什么**：MySQL。
- **登记日期**：2026-10-15

## 向量写入与 MySQL 写入不在同一事务

- **待办**：设计入库的一致性补偿。`upload_file_by_kb` 先写 MySQL 后写 Milvus，Milvus 失败时 MySQL 会回滚；但反过来 MySQL 提交失败时，Milvus 里已有孤儿向量。
- **命令**：无（设计项）。候选：先写 Milvus 再提交 MySQL，失败时按 id 删除向量；或用 upsert 保证重试幂等。
- **预期**：重复上传同一文件不产生重复向量，失败路径无孤儿向量。
- **登记日期**：2026-10-15
