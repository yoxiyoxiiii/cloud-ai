# 知识库文件重新向量化（reembedding）技术方案（2026-10-16，全栈轮）

> 需求原文：「我现在需要实现知识库文件的重新 embedding 操作，目前有后端接口，可以在现在的接口基础上继续完成」。
> 需求分析已呈用户拍板，四问结论为本文输入（见 §2，不再议）。本文是 cloud-base-ai 后端 + cloud-web 前端
> 的设计依据；API 行为以契约 `docs/superpowers/contracts/2026-10-14-ai-kb-api.md`（2026-10-16 修订版）为准。

## 1. 背景与目标

知识库文件入库后，可能需要重做「分片 → 落库 → 向量化」：换分片参数改善切分效果，或换 embedding 模型后原参刷新向量。本轮交付**单文件粒度的重新向量化端点**与前端入口。

**重写基线**：用户工作区已有未提交半成品（`rag/router.py` 的 GET 端点 + `rag/service.py` 尾部的 `reembedding` 函数）。按拍板口径，路径与意图保留、实现整体重做——半成品中的四个问题（协程未 await、分片函数无参调用、入参无类型、用 `current_session()` 却无 `@transactional` 导致守护测试红）随重写自然消除，不单独立项。

当前检索链路未上线（对话端点是纯生成），向量暂无消费方——本轮一致性目标是「失败可重试收敛」而非「零孤儿」，与既有欠账口径一致（见 §8）。

## 2. 拍板结论（设计输入，终版）

| 问题 | 拍板 |
|---|---|
| 执行形态 | **同步单文件**：请求内完成；前端 loading + 120s 超时（沿上传先例）；批量/异步化欠账登记，随检索轮评估 |
| 置换策略 | MySQL **单事务**（软删旧块 + 新块落库 + 文件行快照更新）提交后，Milvus **先写新后删旧**；删除清单 = 该文件**历代**（含已软删行）子块 id，对既往失败残留自愈；`Milvus.delete` 返回 False 必须检查——log + 抛业务码；跨会话防重不做（欠账登记） |
| 存量无原件 | 拒绝 + 错误码 5006（「文件无原件，请删除后重新上传再重嵌入」）；检测 = file_path 不以 `kb/` 开头（早期行是裸 uuid hex，MinIO 无对象） |
| 接口与前端 | POST body DTO（**去 kb_id**，只收 file_id + 可选 chunk_size/chunk_overlap，不传 = 沿用该文件快照值；chunk_size 1..499、overlap 0..chunk_size-1，违例 1001）；权限复用 `ai:kb:update`（kb_update_router，零新种子）；文件不存在 5005；前端 FilesDrawer 操作列 + 参数弹窗预填 + 成功刷新当前页；契约 §7 VO additive 补两列；父块大小固定 500 不开放（欠账登记） |

与拍板无出入，以下全部按此展开。

## 3. 总体设计

### 3.1 端点概览

`POST /rag/kb/reembedding`（网关 `/ai/rag/kb/reembedding`），挂 `kb_update_router`（权限 `ai:kb:update` 与编辑端点同 router），入参 `ReembeddingReq`，返回 `R<null>`。逐字段契约见契约文档 §8。

### 3.2 编排流程（service 层）

```
POST /ai/rag/kb/reembedding {file_id 必填, chunk_size/chunk_overlap 可选}
  │  router（kb_update_router）：pydantic 校验（越界/类型 → 1001）→ 两行式调 service
  ▼
service.reembedding(req)          ← 编排方法：不开事务、不调 current_session
  │
  ├─ ① 阶段一 _load_reembedding_context（@transactional 读事务）
  │     find_kb_file_by_id → None 抛 5005
  │     file_path 不以 "kb/" 开头 → 抛 5006（存量裸 uuid 行，MinIO 无原件）
  │     生效参数 = req 值 ?? 文件行快照值；生效 overlap >= 生效 chunk_size → 抛 1001
  │     list_child_ids_by_file（历代口径，含软删行）→ old_vector_ids
  │     返回纯值（file_path、生效参数、old_vector_ids）——不返回 ORM 实体，
  │     防事务关闭后惰性加载陷阱
  │
  ├─ ② 读原件：run_in_threadpool(get_storage().get_bytes, file_path)
  │     S3Error → logger.error + 抛 5007
  │
  ├─ ③ 解析分片（与上传链同构）：
  │     pdfmuse.to_text → chunking.parent_child_split_file(
  │         file_content, parent_chunk_size=500(固定), child_chunk_size=生效值,
  │         child_chunk_overlap=生效值)
  │
  ├─ ④ 阶段二 _replace_file_chunks（@transactional 写事务，三写原子）
  │     delete_chunks_by_file（软删当前代，复用既有动词）
  │     save_chunks（父子新代同批写入，id 回填）
  │     update_kb_file（快照三值：file_chunk_count=新子块数、size/overlap=生效值）
  │     返回新代子块行 id 清单 new_child_ids
  │     —— 任一步失败整体回滚，Milvus 未动，直接重试即可
  │
  ├─ ⑤ Milvus 写新代：milvus.add_documents(child_docs, ids=[str(id)…])
  │
  └─ ⑥ Milvus 删历代：old_vector_ids 非空才调 milvus.delete(ids=[str(id)…])
        返回 False → logger.error + 抛 5008（True 含「目标本就不存在」的幂等删除）
```

Milvus 实例经 `vectorstore.get_milvus("test123")` 取——collection 名硬编码是既有欠账，本轮不动（见 §8）。

### 3.3 事务边界与守护合规

编排方法 `reembedding` **不调用 `current_session()`**，因此不挂 `@transactional`（守护规则「用 current_session 的方法必带 @transactional」按字面满足）；两个阶段方法各挂 `@transactional`，是两段独立事务、各自提交。Milvus/MinIO 调用全部在事务外——MySQL 事务在阶段二 return 时已提交，向量置换（⑤⑥）面对的是已持久化的新代 id，失败不再牵连数据库。这与上传链「外部写在事务内、靠回滚兜底」不同：重嵌入的置换语义要求**先定库、后动向量**，失败面收敛到向量一侧（见 §5）。

## 4. 关键设计决策

### 4.1 历代自愈删除清单（⑥ 的口径）

- **来源是 MySQL 反查，不是 Milvus 表达式**：向量主键 = 子块行自增 id 字符串化；向量 metadata 只有 chunk uuid/type/parent_id，无 kb/file 维度字段，Milvus 侧按文件过滤无通道。新增 DAO 动词 `list_child_ids_by_file`（`WHERE file_id=? AND chunk_type=1`，**不含 deleted 过滤**）即得精确清单。
- **为何含软删行**：删除清单要覆盖「该文件历史上所有成功落过库的子块代」——含既往重嵌入被软删的旧代、以及上次运行到 ⑤⑥ 之间失败残留的代。这让「原样重试同一请求」具备完全收敛性。
- **为何在阶段一捕获**：新代 id 是阶段二才生成的自增值，先读后写保证清单与新增 id 零交集，删除不会误伤新向量。
- **覆盖边界（诚实声明）**：只覆盖「曾成功提交到 MySQL 的代」。上传链在「MySQL 提交前」失败的向量写入（行已回滚、id 从未落库）不属任何文件的历代，仍是全局孤儿向量，欠账口径不变（§8）。

### 4.2 先写新后删旧（拍板）的失败形态

新代向量 id 与旧代不同源（自增），无主键冲突，次序可自由选择。选「先写后删」：⑤⑥ 之间失败时向量面是「新旧并存」而非「检索空洞」——旧代块行已软删，未来检索命中旧向量后按 `deleted=0` 回捞会被过滤（表现为静默丢弃、不产生脏内容），新向量已可服务；而「先删后写」的中间态是该文件完全不可见。检索未上线使两者当下都无用户可见影响，取恢复面更优的一侧。

### 4.3 Milvus.delete 返回值判定（装机源码实证）

`langchain_milvus`（工程 .venv 装机版，vectorstores/milvus.py 的 delete 方法）：内部把 ids 拼成 `主键 in [...]` 表达式调 `client.delete`，成功**恒返回 True**（删除目标本就不存在也算成功——幂等重删安全）；仅捕获 `MilvusException` 时记日志并返回 **False**。因此「False 即真失败」成立，检查方式 `if not ok: logger.error + 抛 5008`。另注意：ids 为空列表且无表达式会触发断言错误——**空清单必须跳过调用**（新文件首次重嵌入的极端场景）。

### 4.4 参数与校验设计

- **快照默认语义**：chunk_size/chunk_overlap 不传 = 沿用文件行快照列（`file_chunk_size`/`file_chunk_overlap`）——覆盖「换 embedding 模型原参刷新」场景；传入则以新参分片并在阶段二回写快照。
- **chunk_size 上限 499**：父块固定 500，分片器要求父块大于子块；上限进 pydantic 约束（静态可判）。若放行 ≥500，会触发分片器内既有的裸 `code=-1` 异常（历史遗留写法），在入口挡掉即不会触达。
- **overlap < chunk_size 的关系校验放 service**：两者各自可缺省、生效值取决于快照，纯请求域的 pydantic 校验器覆盖不了「只传其一」的组合——统一在阶段一对**生效值**判定，违例抛 `BusinessException("分片重叠必须小于分片大小", 1001)`（错误码语义与参数校验一致，全局 handler 出 HTTP 200 + R{code:1001}）。
- **kb_id 移除**：文件行自带 kb_id，file_id 单值定位；库被删时文件行已级联软删 → 5005，无需库级校验。

## 5. 失败收敛矩阵

| 失败点 | MySQL 状态 | Milvus 状态 | 调用方所见 | 重试收敛路径 |
|---|---|---|---|---|
| 参数校验（1001：router 静态越界 / 阶段一关系校验） | 未动 | 未动 | 1001 | 修正参数即成功 |
| 文件不存在（5005） | 未动 | 未动 | 5005 | — |
| 存量无原件（5006） | 未动 | 未动 | 5006 | 删文件、重新上传后再重嵌入 |
| 原件读取失败（5007：S3Error） | 未动 | 未动 | 5007 | 修 MinIO 后重试，或删后重传 |
| 解析/分片异常（pdfmuse 等） | 未动 | 未动 | 500 兜底 | 环境修复后重试 |
| 阶段四事务失败（任一 SQL） | **整体回滚零残留** | 未动 | 500 兜底 | 直接重试（全新一次） |
| ⑤ 写新向量异常 | 已提交（新代在库、旧代已软删） | 旧代在、新代缺 | 500 兜底 | 重试：再置换一代 + 历代清理一次收敛 |
| ⑥ 删旧返回 False | 已提交 | 新旧并存 | **5008** | 重试：历代清单含残留代，一次清干净 |

**已知残余（拍板接受）**：跨会话并发重嵌入不设防。两次并发交错的理论最坏情形会留一代「中间代」向量（其 MySQL 行已被后一次运行软删、但两边的删除清单都没含它）——由**该文件下一次重嵌入**的历代清单收敛；在此之前它命中即被回捞过滤，不产生脏内容。此为「不做防重」的既定代价，随欠账登记。

## 6. 错误码分配（AI 段 5xxx 接续）

| 码 | 常量语义 | msg | 触发 |
|---|---|---|---|
| 5005 | 文件不存在 | 文件不存在 | find_kb_file_by_id 为 None（活记录视角，软删不可见；库级联删除后的文件行同此） |
| 5006 | 文件无原件 | 文件无原件，请删除后重新上传 | file_path 不以 `kb/` 开头（存量裸 uuid 行） |
| 5007 | 原件读取失败 | 文件原件读取失败，请删除后重新上传 | MinIO get_bytes 抛 S3Error（有 kb/ 前缀但对象不可得） |
| 5008 | 向量清理失败 | 向量清理失败，请重试 | milvus.delete 返回 False（MySQL 已生效，重试收敛） |

1001 为既有参数校验码复用（本轮新增关系校验分支）；下一可用 AI 段码 **5009**。常量在 `rag/service.py` 顶部成对声明（沿既有 `CODE_X`/`MSG_X` 模式）。

## 7. 封装设计（各层改动清单）

### 7.1 后端 cloud-base-ai

| 层 | 文件 | 改动 |
|---|---|---|
| schemas | `rag/schemas.py` | 新增 `ReembeddingReq`：file_id int ≥1 必填；chunk_size int|None（1..499）；chunk_overlap int|None（≥0）。`KbFileVo` additive 补 `file_chunk_size`/`file_chunk_overlap` 两列（契约 §7） |
| router | `rag/router.py` | 删 GET WIP 端点；`kb_update_router` 增 `POST /reembedding`（body DTO，两行式返回 `R.ok(None)`）。router 级权限依赖沿用 kb_update_router 既有挂法，无需新种子 |
| service | `rag/service.py` | 重写 `reembedding`（编排，无事务）+ 阶段一 `_load_reembedding_context` / 阶段二 `_replace_file_chunks`（各 @transactional）+ 四个错误码常量。上传链 `upload_file_by_kb` **不动**（其分片参数写死 128/20/500 维持现状） |
| dao | `rag/dao.py` | 新增 `list_child_ids_by_file`（历代口径：file_id 等值 + chunk_type=1，**不含 deleted 过滤**——注释写明此例外于「读路径显式 deleted=0」通则的原因：清单用途是外部向量清理，恰要含历代）。复用 find_kb_file_by_id / delete_chunks_by_file / save_chunks / update_kb_file |
| vectorstore / storage / chunking | 不改 | get_milvus("test123")、get_storage().get_bytes、parent_child_split_file 原样复用 |

### 7.2 前端 cloud-web

| 文件 | 改动 |
|---|---|
| `src/types/api.ts` | `KbFileVo` additive 两列（number）；新增 `ReembedPayload { file_id, chunk_size?, chunk_overlap? }` |
| `src/api/rag.ts` | 新增 `reembedKbFile(payload)`：POST `/ai/rag/kb/reembedding`，timeout 120000（沿 chatRag/uploadKbFile 长操作先例） |
| `src/views/rag/kb/components/ReembedDialog.vue`（新） | 参数弹窗：props `{ modelValue, file: KbFileVo }`；两个 `el-input-number`（分片大小 1..499 / 重叠大小），**预填 file 快照两列**；关系校验（重叠 < 分片）自定义 validator；表单提示注明父块固定 500；提交 loading 防重复；成功 `ElMessage.success('重新向量化完成')` + `emit('success')` + 关窗；catch 留空（拦截器统一 toast 5005/5006/5007/5008/1001） |
| `src/views/rag/kb/components/FilesDrawer.vue` | 表格增「操作」列：「重新向量化」文字按钮挂 `v-perms="'ai:kb:update'"`，点击传行数据开弹窗；弹窗 `@success` → `loadFiles(当前页)`（拍板：刷新当前页非回首页）；表格既有三列不动（分块数不展示的拍板维持，参数可见性走弹窗预填） |

弹窗组件形态沿 `KbFormDialog.vue` 先例（modelValue + watch 初始化 + FormRules + loading 模式），不引入新交互模式。

## 8. DDL / 索引审查与欠账关系

**零 DDL、零增量索引**。本轮唯一新查询路径 `list_child_ids_by_file`（file_id 等值 + chunk_type 过滤，不过滤 deleted）命中既有 `idx_file(file_id)` 单列索引——等值前缀命中，chunk_type 与 deleted 走回表过滤，单文件块行量级（几十至几百行）无需复合索引。软删 `delete_chunks_by_file` 同路径既有覆盖。

**既有欠账（`docs/handoff/ai.md` 三条）本轮不治理只登记关系**：三存储一致性补偿、collection "test123" 隔离、孤儿向量/孤儿对象清理——口径不变。本轮的历代删除是**该文件范围**的局部自愈，非全局治理；上传链「MySQL 回滚但向量已写」产生的无主向量不在任何文件的历代清单内，仍归全局欠账。

**新增登记三条**（实施收官时落 `docs/handoff/ai.md`）：
1. 父块分片大小未开放（固定 500；文件表无 parent 快照列，开放需 DDL 加列）；
2. 跨会话并发防重未做（残余形态与收敛路径见 §5）；
3. 批量/异步化未做（同步单文件拍板的后续评估项，随检索轮）。

## 9. 测试策略

- **单测（不连真实外部系统，沿 SQLite 替身 + monkeypatch 模式）**：dao 新动词 1 用例（含软删行/仅子块/空文件）；service 用例覆盖 5005/5006/5007/5008/1001 关系校验、快照默认语义（不传参走快照值且快照不回写为不同值）、置换编排（旧代软删 + 新代落库 + 快照三值 + milvus add 新 id / delete 历代清单、空清单跳过 delete）；router 用例对齐既有端点形态（无 perms 403 / 仅 ai:kb:add 403 / 参数越界 1001 / 业务码透传 / 成功 data null）。
- **守护测试**：重写后 `current_session` 全部在 @transactional 方法内——开工基线 182 passed / 1 failed（WIP 红）收官转全绿，是硬验收项。
- **真链路双路**（直连 8010 手工鉴权 header + 网关 18080 admin token）：happy path 换参、原参重嵌入、5005/5006/1001 分支；pymysql 回查块表世代与文件行快照；Milvus 按 pk 回查「新代全在、历代清零」。测试数据 `e2e_` 前缀，收官清理（删库端点 + storage.remove 原件 + kill 服务）。
- **e2e（cloud-e2e）**：扩展 `run-ai-kb-e2e.mjs`（route mock AI 层、登录走真实网关），新增操作列/弹窗预填/提交 body 断言/成功刷新/5006 错误分支场景；挂法沿 `npm run e2e:aikb` 单跑，不进 `e2e` 聚合（同 aichat/aikb 口径）。场景脚本与功能代码同一 commit（既有纪律）。
