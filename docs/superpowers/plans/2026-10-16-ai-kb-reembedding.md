# 知识库文件重新向量化实施计划（2026-10-16，全栈轮）

> **For agentic workers:** 本计划按任务顺序执行，步骤用 checkbox 跟踪。
> 需求原文：「我现在需要实现知识库文件的重新 embedding 操作，目前有后端接口，可以在现在的接口基础上继续完成」。
> 设计依据：`docs/superpowers/specs/2026-10-16-ai-kb-reembedding-design.md`；API 行为以契约
> `docs/superpowers/contracts/2026-10-14-ai-kb-api.md`（2026-10-16 修订版，§8）为准。
> 四项用户拍板（同步单文件 / 先写新后删旧+历代自愈 / 存量无原件拒绝 5006 / POST DTO 去kb_id+复用
> ai:kb:update）已折进下列任务，不再议。

**Goal**: 交付单文件重新向量化端点（POST /rag/kb/reembedding）与前端 FilesDrawer 操作入口；重写工作区既有 WIP 半成品；守护测试由「182 passed / 1 failed」回全绿。

**Architecture**: service 编排方法不开事务，两个 `@transactional` 阶段方法（读校验 / 写置换）+ 事务外 Milvus 先写新后删旧（删除清单 = 该文件历代子块 id，含软删行）；MySQL 单事务三写（软删旧块 + 新块落库 + 文件行快照更新）。详见 spec §3-§5。

**Tech Stack**: 既有 `pdfmuse` / `chunking.parent_child_split_file` / `vectorstore.get_milvus("test123")` / `storage.get_storage()` / dao 动词集；前端沿 FilesDrawer + KbFormDialog 弹窗模式。

---

## 设计定案（不重议，摘自 spec）

1. **重写基线**：工作区 WIP（`rag/router.py` GET 端点、`rag/service.py` 尾部 `reembedding`）整段替换——上一轮「一行不碰」红线在本轮解除，git 工作区两文件的未提交改动由本轮任务消化。
2. **编排分层**：`reembedding(req)`（无事务、不调 `current_session`）→ ① `_load_reembedding_context`（@transactional：5005/5006/1001 关系校验/历代清单）→ ② MinIO 读原件（S3Error→5007）→ ③ 解析分片（父块固定 500）→ ④ `_replace_file_chunks`（@transactional：软删旧块+写新块+快照三值，返回新代子块 id）→ ⑤ `milvus.add_documents`（新 id 字符串化）→ ⑥ `milvus.delete`（历代清单非空才调；返回 False→log+5008）。
3. **参数**：`ReembeddingReq{file_id≥1 必填, chunk_size 1..499 可选, chunk_overlap ≥0 可选}`；不传=沿用快照；「重叠 ≥ 生效分片大小」在 service 判定抛 `BusinessException(msg, 1001)`。
4. **错误码**：5005 文件不存在 / 5006 文件无原件 / 5007 原件读取失败 / 5008 向量清理失败（msg 见契约 §1）；常量成对声明于 service 顶部。
5. **零 DDL、零种子、零新索引**；契约 §7 KbFileVo additive 两列（前端预填数据源）。
6. **不动**：上传链 `upload_file_by_kb`（写死 128/20/500 维持）、collection 名 "test123"、三条既有欠账（本轮只追加登记）。

## 前置条件（开工自检）

- 基线：`uv run --project cloud-base-ai pytest` 为 182 passed / 1 failed（唯一失败 = WIP 的守护规则红「current_session 无 @transactional」）——不符先停下核对再动工。
- 真链路验证需 MySQL / Nacos / Redis / Ollama(11434) / Milvus(19530) / MinIO 在线；种子 184 已落库且 admin 已含 `ai:kb:update`（2026-10-15 轮已实证，无需重落）。
- uv 不在 Git Bash PATH，用绝对路径（CLAUDE.md「AI 基座」条）；本地直连 8010 须手工带 `X-User-Perms`/`X-User-Account` header。

---

# 后端章（cloud-base-ai；章内任务顺序执行）

### Task B1: DTO 与路由（重写 WIP 端点）

文件：`src/cloud_base_ai/rag/schemas.py`、`src/cloud_base_ai/rag/router.py`。

- [ ] **B1.1** schemas 增 `ReembeddingReq(BaseModel)`：`file_id: int = Field(ge=1)`；`chunk_size: int | None = Field(default=None, ge=1, le=499)`；`chunk_overlap: int | None = Field(default=None, ge=0)`——docstring 注明「不传=沿用文件行快照值；上限 499 因父块固定 500」。
- [ ] **B1.2** `KbFileVo` additive 两列：`file_chunk_size: int`、`file_chunk_overlap: int`（契约 §7）。
- [ ] **B1.3** router：删除 `kb_router` 上的 `@kb_router.get("/reembedding")` WIP 端点；在 `kb_update_router` 增 `@kb_update_router.post("/reembedding")`，body 收 `ReembeddingReq`，两行式 `await service.reembedding(req)` → `return R.ok(None)`；javadoc 风格 docstring 注契约 §8。
- [ ] **B1.4** 确认路由无冲突：POST `/rag/kb/reembedding` 与 POST `/rag/kb`（创建）、PUT `/rag/kb/{kb_id}` 方法路径互斥。

**验收**：`GET /rag/kb/reembedding` 不复存在；POST 端点挂 ai:kb:update 门控（router 级依赖继承）；schemas import 无未用项。

### Task B2: DAO 历代清单动词 + 用例

文件：`src/cloud_base_ai/rag/dao.py`、`tests/test_rag_dao.py`。

- [ ] **B2.1** dao 增 `list_child_ids_by_file(session, file_id) -> Sequence[int]`：`WHERE file_id=? AND chunk_type=CHILD`（**不含 deleted 过滤**），`order_by id asc`，docstring 写明历代口径与「读路径显式 deleted=0 通则的例外原因：清单用于外部向量清理，恰要包含已软删的历代」。命中既有 `idx_file(file_id)`。
- [ ] **B2.2** test_rag_dao 增用例：软删行包含在清单内 / 仅子块（父块排除）/ 无块文件返空列表。

**验收**：新动词三断言绿；既有 dao 用例不红。

### Task B3: service 重写（编排 + 两阶段）

文件：`src/cloud_base_ai/rag/service.py`（替换尾部 WIP 函数区域；错误码常量区增四对 CODE/MSG）。

- [ ] **B3.1** 常量：`CODE_FILE_NOT_EXISTS=5005` / `CODE_FILE_NO_ORIGINAL=5006` / `CODE_FILE_ORIGINAL_READ_FAILED=5007` / `CODE_VECTOR_PURGE_FAILED=5008`（msg 见契约 §1），另 `CODE_CHUNK_PARAM_INVALID=1001`。
- [ ] **B3.2** `async def reembedding(req: ReembeddingReq) -> None`：编排六步（设计定案 2），**不挂 @transactional、不调 current_session**；MinIO 读走 `run_in_threadpool`；Milvus 实例 `vectorstore.get_milvus("test123")`。
- [ ] **B3.3** `async def _load_reembedding_context(...) -> tuple 纯值`（@transactional）：file 行校验（5005/5006）→ 生效参数（req ?? 快照）→ 关系校验（生效 overlap >= 生效 size → 1001）→ `list_child_ids_by_file` 历代清单；返回纯值不返回 ORM 实体。
- [ ] **B3.4** `async def _replace_file_chunks(...) -> list[int]`（@transactional）：`delete_chunks_by_file`（软删当前代）→ 构造父子块实体 `save_chunks` → 文件行快照三值更新 `update_kb_file`（file_chunk_count=新子块数、size/overlap=生效值）；返回新代子块行 id。
- [ ] **B3.5** Milvus 段：`add_documents(child_docs, ids=[str(id)…])`；`old_ids` 非空才 `delete(ids=[str(id)…])`，返回值 False → `logger.error` + 抛 5008（空清单跳过——delete 空列表会触发 langchain-milvus 断言错误，源码实证见 spec §4.3）。
- [ ] **B3.6** S3Error catch：`logger.error` 记根因后抛 5007；except 块全部满足守护规则（记日志或首行纯 raise）。

**验收**：`git diff` 显示 WIP 函数区域整体替换、`upload_file_by_kb` 零改动；方法体长度符合规范（编排方法 ≤50 行目标，超限拆私有助手）。

### Task B4: service / router 单测

文件：`tests/test_rag_service.py`、`tests/test_rag_router.py`（沿既有 monkeypatch 替身模式——upload 用例已示范 pdfmuse/chunking/dao/vectorstore/storage 全替身）。

- [ ] **B4.1** service 用例（≥7）：
  1. 文件不存在 → 5005；
  2. file_path 裸 uuid（不以 `kb/` 开头）→ 5006 且零外部调用；
  3. get_bytes 抛 S3Error → 5007；
  4. 不传参数 → `parent_child_split_file` 收到快照值（128/20）、文件行快照更新为同值；
  5. 传新参（如 200/30）→ 分片收到新参、快照三值回写（count=新子块数）；
  6. 生效 overlap >= 生效 size（含只传其一组合）→ 1001；
  7. 置换编排：`delete_chunks_by_file` 被调、`milvus.add_documents` 收新代 id 字符串、`milvus.delete` 收历代清单（含软删代）且空清单不调；`delete` 返回 False → 5008。
- [ ] **B4.2** router 用例（对齐 PUT 编辑端点形态）：无 header 403 / 仅 `ai:kb:add` 403（权限分立）/ chunk_size=600 → 1001 / file_id 缺失 → 1001 / monkeypatch service 抛 5006 透传 / 成功 `data=null`。

**验收**：新增用例全绿且不连真实外部系统（SQLite/替身）。

### Task B5: 全量 pytest + 守护回绿

- [ ] **B5.1** 工程根全量 `pytest`：**182+新增 passed / 0 failed**（WIP 守护红消除）。
- [ ] **B5.2** 复核 `test_rag_schemas.py` / `test_rag_entities.py` 无需联动（零实体零 DDL；KbFileVo additive 需补 schema 断言则同任务更新）。

**验收**：全绿是硬门槛，任何红不进入真链路验证。

### Task B6: 真链路双路验证 + 记账回填

- [ ] **B6.1** 起服 uvicorn 8010；直连带手工 header（perms 含 `ai:kb:update`，account=admin）：
  1. 造数：建 KB `e2e_reembed_<ts>` + 上传最小合法 PDF（构造器沿上一轮 plan 的 minimal_pdf 模板，`pdfmuse.to_text` 非空自检门）；
  2. **换参重嵌入** `{file_id, chunk_size:200, chunk_overlap:30}` → 200 data null；
  3. pymysql 回查：旧代块行 `deleted=1`、新代父子行数合理、文件行快照 = 200/30/新子块数、update_by=admin；
  4. Milvus 按 pk 回查（沿上一轮 pk 精确回查脚本形态，pymilvus query `pk in [...]`）：**新代子块 pk 全在、历代 pk 全无**；
  5. **原参重嵌入**（不传两参）→ 200，分片沿用快照（快照值不变可从块行数/内容印证）；
  6. 错误分支：file_id=99999 → 5005；pymysql 造一条 file_path=32 位 hex 裸 uuid 行 → 5006 → 清理该行；chunk_size=600 / overlap≥size → 1001。
- [ ] **B6.2** 网关全链（admin 登录 token 经 18080 `/ai`）：happy path 200 + 记录耗时（与上传 11s 同量级观察，超 120s 上报）；仅 `ai:kb:list` 直连 → 403。
- [ ] **B6.3** 清理：删除端点删测试 KB（软删）+ `storage.remove(对象键)` 物理删原件 + netstat 找 PID kill uvicorn；`e2e_` 数据与对象零残留。
- [ ] **B6.4** 契约 §10 追加本轮验证记录；`docs/handoff/ai.md` 追加三条登记（父块参数未开放 / 跨会话防重未做 / 批量异步化未做——措辞见 spec §8）。

**验收**：双路各分支行为与契约 §8 逐条一致；Milvus 新旧 pk 核对记录在案；记账两条文档已更新。

---

# 前端章（cloud-web；只依赖契约不依赖后端实现，与后端章并行）

### Task F1: 类型与 api 层增量

文件：`src/types/api.ts`、`src/api/rag.ts`。

- [ ] **F1.1** `KbFileVo` additive：`file_chunk_size: number`、`file_chunk_overlap: number`（JSDoc 注契约 §7 additive 口径）。
- [ ] **F1.2** 新增 `ReembedPayload { file_id: number; chunk_size?: number; chunk_overlap?: number }`（契约 §8；不传两参=沿用快照）。
- [ ] **F1.3** `reembedKbFile(payload): Promise<null>`：POST `/ai/rag/kb/reembedding`，`timeout: 120000`（长操作沿 chatRag/uploadKbFile 先例），JSDoc 注契约 §8 与错误码（5005/5006/5007/5008 拦截器统一 toast）。

**验收**：types/api.ts 与契约 §7/§8 逐字段一致；无 any。

### Task F2: 参数弹窗 + 操作列接线

文件：`src/views/rag/kb/components/ReembedDialog.vue`（新建）、`src/views/rag/kb/components/FilesDrawer.vue`。

- [ ] **F2.1** `ReembedDialog.vue`：沿 KbFormDialog 模式——props `{ modelValue: boolean; file: KbFileVo | null }`；`watch(modelValue)` 打开时预填 `file.file_chunk_size`/`file.file_chunk_overlap`；`el-input-number` 两字段（分片大小 min1 max499 / 重叠大小 min0），关系校验（重叠 < 分片）自定义 validator；表单提示注明「父块固定 500，子块须更小」；提交 loading 防重复 + `reembedKbFile({file_id, chunk_size, chunk_overlap})`；成功 `ElMessage.success('重新向量化完成')` + `emit('success')` + 关窗；catch 留空（拦截器 toast，弹窗不关可改参重试）。
- [ ] **F2.2** `FilesDrawer.vue`：表格增「操作」列（width≈120）——「重新向量化」文字按钮挂 `v-perms="'ai:kb:update'"`，点击存当前行开弹窗；`<ReembedDialog @success="loadFiles(query.page)" />`（拍板：刷新**当前页**非回首页）；既有三列与上传区不动。
- [ ] **F2.3** 行断言纪律：列插槽 row 收窄复用既有 `rowOf` 函数，禁新增裸 `as`。

**验收**：弹窗预填值来自 §7 两 additive 列；loading 期间按钮/输入禁用；5006 等错误 toast 后弹窗保留。

### Task F3: 构建与联调冒烟

- [ ] **F3.1** `npm run build` **连续两次全绿**（components.d.ts 首建生成、二建更严——frontend-page 规范）。
- [ ] **F3.2** dev 5173 冒烟：后端就绪走真实链路（admin 登录 → 知识库 → 文件管理 → 重嵌入换参 → toast → 列表当前页刷新）；后端未就绪先按契约 mock（proxy 临时指向本地 json 或 route 拦截），联调切回并注明。

**验收**：build ×2 绿；冒烟覆盖弹窗预填/提交/错误三分支。

---

# 测试章（cloud-e2e）

### Task T1: 重新向量化场景并入 aikb 脚本

文件：`cloud-e2e/run-ai-kb-e2e.mjs`（扩展；**与功能代码同一 commit**——场景脚本与页面改动原子提交的既有纪律）。

- [ ] **T1.1** mock 层扩展：`filesByKb` 行补 `file_chunk_size: 128, file_chunk_overlap: 20`；新增 `POST /ai/rag/kb/reembedding` 分支——记录请求 body，可开关返回 `R(null)` 或 `ERR(5006, '文件无原件，请删除后重新上传')`。
- [ ] **T1.2** 新增场景（沿既有 AC 编号顺延）：
  - AC11 操作列与弹窗：抽屉行「重新向量化」按钮可见（admin 含 ai:kb:update）→ 弹窗打开 → 两输入框**预填 mock 行的 128/20** 断言；
  - AC12 提交流：改参（如 200/30）提交 → route 层断言 body `{file_id, chunk_size:200, chunk_overlap:30}`（JSON body）→ mock 200 → toast「重新向量化完成」→ 弹窗关 → files 请求重发且 **page 保持当前页**断言；表单关系校验（重叠≥分片）内联错误 0 请求；
  - AC13 错误分支：mock 5006 → toast 文案断言 + 弹窗不关。
- [ ] **T1.3** 挂法说明（零新 npm script）：并入既有 `npm run e2e:aikb` 单跑，不进 `e2e` 聚合（同 aichat/aikb 口径——AI 域 mock 场景与全量真实回归分层）；脚本头部注释与文件头场景清单同步更新。

**验收**：`cd cloud-e2e && npm run e2e:aikb` 全 AC 绿（含既有 AC1-AC10 回归不红）；AC-VERIFY 证据核验段零 console error / 零 >=400。

---

## 验收标准（整轮）

1. 后端：全量 pytest 全绿（守护回绿）；真链路双路各分支与契约 §8 一致；Milvus 新代全在/历代清零实证；`e2e_` 数据与 MinIO 对象清理净。
2. 前端：build ×2 绿；FilesDrawer 操作列 + 弹窗预填/提交/错误三分支冒烟通过。
3. e2e：`npm run e2e:aikb` 全绿（新 AC11-13 + 既有回归）。
4. 文档：契约 §10 验证记录回填、handoff 三条新登记落档。
5. WIP 消化：工作区两文件的未提交半成品被本轮替换，无残留死代码（GET 端点、无参分片调用、未 await 协程不复存在）。

## 移交备忘（下一轮前必读）

- 父块分片大小（parent_chunk_size）未开放：固定 500、文件表无快照列——开放需 DDL 加列 + 契约/弹窗同步（handoff 已登记）。
- 跨会话并发防重未做：并发交错的理论残余（中间代向量）由该文件下一次重嵌入收敛；若未来要防，候选是文件行状态列条件写（需 DDL）或进程内 per-file 锁（多实例失效）。
- 批量/异步化未做：KB 级批量重嵌入与后台任务模型随检索轮评估（同步 120s 超时是单文件前提）。
- 上传链分片参数仍写死 128/20/500：本轮只开放重嵌入侧换参；上传侧参数化若要做，属新需求（复用本轮 ReembeddingReq 校验口径）。
- 检索轮做 collection 隔离时：重嵌入与上传的 "test123" 字面量共两处，随命名策略一并收敛；孤儿向量全局治理口径见 `docs/handoff/ai.md`。
