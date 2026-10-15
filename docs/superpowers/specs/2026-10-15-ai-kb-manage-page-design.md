# AI 知识库列表管理页（分页 + 编辑 + 删除 + 文件抽屉）技术方案（2026-10-15）

> 全栈 A 级轮。设计输入 = 阶段一需求分析 + 用户拍板三项：① 范围档位二——知识库页改标准
> 表格管理页（分页 + 名称搜索 + 创建时间列）+ 编辑弹窗（重命名/改描述）+ 删除知识库（确认框、
> KB/文件/chunk 三表级联软删）+ 文件管理抽屉平移（上传+文件分页，能力面不增不减；不做删除单个文件）；
> ② 删除向量口径 c2——MySQL 软删级联、物理向量不动、孤儿向量记账移交检索轮；③ 权限分立新种子
> 184/185（`ai:kb:update` / `ai:kb:delete` 挂 182 下）。拍板结论不再复议。
> 关联文档：契约 `docs/superpowers/contracts/2026-10-14-ai-kb-api.md`（本轮修订）、
> 实施计划 `docs/superpowers/plans/2026-10-15-ai-kb-manage-page.md`、
> 上轮设计 `docs/superpowers/specs/2026-10-15-ai-kb-manage-design.md`（其 §6 移交备忘「KB 列表不分页」
> 条目由本轮兑现）。

## 1. 背景、范围与结论总览

**现状**：知识库管理页是「卡片左栏（全量不分页）+ 右栏文件表格」两栏形态；知识库只有「建」没有
「改/删」。后端 `GET /rag/kb/list` 返回 `R<KbVo[]>` 全量 id 升序（契约 §4 现状）。DAO 层在 2026-10-13
ORM/DAO 轮已备齐 `page_list_kbs`（含 name 模糊过滤）、`update_kb`、`delete_kb_by_id`、
`delete_chunks_by_kb` 等动词——本轮后端增量集中在 service 编排 + router + 契约 + 种子，DAO 仅补一个
文件行批量软删动词。

**本轮结论**（详细取舍见后文）：

| # | 结论 | 章节 |
|---|---|---|
| 1 | `GET /rag/kb/list` **原端点改造**为分页（入参加 page/size/name，出参 `R<AiPageResult<KbVo>>`）——breaking change，消费方唯一（前端 kb 页），同轮两端收敛 | §2.1/§2.3 |
| 2 | 新增编辑 `PUT /rag/kb/{kb_id}`、删除 `DELETE /rag/kb/{kb_id}` 两端点；各挂独立 router（路由级权限依赖防线延续） | §2.2 |
| 3 | 排序由 id 升序改 **id 倒序**（新建库居首，与文件表一致）；KbVo additive 补 `create_time` 列 | §2.3 |
| 4 | 删除 = KB/文件/chunk **三表级联软删**同一事务；物理向量不动（c2 口径），孤儿向量记账 `docs/handoff/ai.md` | §2.5/§6 |
| 5 | 错误码**零新增**：编辑重名复用 5003、编辑/删除目标不存在复用 5004（msg 语义兼容） | §2.4/§2.5 |
| 6 | 前端改标准表格管理页（搜索栏 + 操作条 + 表格 + 分页），文件管理平移进 **el-drawer 抽屉**；CreateKbDialog 改造为 KbFormDialog（add/edit 双模式） | §3.1/§3.2 |
| 7 | 种子新增 184 F「编辑」`ai:kb:update` / 185 F「删除」`ai:kb:delete` 挂 182 下；menu/role 两 e2e 版图断言同步 48→50 | §5 |

**范围外（明确不做）**：删除单个文件（档位三排除）、向量物理清理与 collection 隔离（handoff 检索轮
统一治理）、库级文档数/分块数展示（2026-10-15 用户拍板维持）、搜索之外的筛选（创建时间范围等）、
检索测试界面 / ingestion 域全部内容。**本轮零 DDL、零索引变更、零新增错误码。**

## 2. 后端设计（cloud-base-ai）

### 2.1 端点总览（改造后 rag 域 kb 共 6 端点）

| 方法 | 服务侧路径 | 权限 | 入参 | 返回 | 错误码 |
|---|---|---|---|---|---|
| GET | /rag/kb/list | `ai:kb:list` | query：page/size/name | `R<AiPageResult<KbVo>>` | 200 / 403 / 1001 |
| PUT | /rag/kb/{kb_id} | `ai:kb:update` | 路径 kb_id + body UpdateKbReq | `R<null>` | 200 / 403 / 1001 / 5003 / 5004 |
| DELETE | /rag/kb/{kb_id} | `ai:kb:delete` | 路径 kb_id | `R<null>` | 200 / 403 / 1001 / 5004 |
| POST | /rag/kb（创建）、POST /rag/kb/upload（上传）、GET /rag/kb/{kb_id}/files（文件分页） | | 不变 | | |

编辑/删除的 HTTP 方法与路径形态对齐 Java 域用户域先例（修改 PUT、删除 DELETE /{id}、返回 data=null）；
kb_id 走路径参数沿 files 端点先例。完整逐字段定义见契约 §4/§5/§6。

### 2.2 取舍一：编辑/删除各挂独立小 router（路由级权限依赖）

- 背景：权限拆分为 `ai:kb:update` / `ai:kb:delete` 后，既有 `kb_router`（路由级 `ai:kb:add`）与
  `kb_read_router`（路由级 `ai:kb:list`）均不适配。
- 候选：A）新开 `kb_update_router` / `kb_delete_router` 两个小 router（prefix 同 `/rag/kb`，各带路由级
  `require_perms`）；B）挂进既有 router 用端点级 `Depends(require_perms(...))`。
- 结论：**选 A**。理由沿上轮设计「读/写两个 router」的取舍（上轮 §2.2）：`/ai-spec` 记载漏写权限依赖
  不会有任何报错、请求直接放行——路由级依赖下今后挂进 router 的新端点自动受保护；FastAPI 同前缀多
  router 按方法+路径精确匹配无冲突（上轮 kb_router/kb_read_router 已实证）。router 数量 +2，main.py
  include +2 行，是防漏放行防线的固定代价。

### 2.3 list 端点改造（分页 + 搜索 + 排序变更 + 补列）

- **入参**：`page` int 默认 1、`size` int 默认 10（越界钳制不报错：page<1 取 1、size 取 1..200——既有
  `_clamp_page`）；`name` str 可选默认 None，`max_length=50`（超长 1001），空串视为不过滤；非整数
  参数 1001（FastAPI 校验）。
- **name 匹配语义**：包含匹配（SQL `LIKE '%name%'`，dao.page_list_kbs 现状），不分词、不区分大小写
  依 MySQL collation 默认（utf8mb4_general_ci 不区分）——契约写明「包含匹配」即可，不展开 collation。
- **排序变更**：id 升序 → **id 倒序**（dao.page_list_kbs 现状 ORDER BY id DESC，新建库居首，与文件表
  一致）——契约 §4 重写时显式标注该行为变更。
- **出参**：`R<AiPageResult<KbVo>>`；KbVo additive 补 `create_time: str`（继承 VoTimeMixin 序列化为
  `yyyy-MM-dd HH:mm:ss`，DDL 列 NOT NULL 无默认，实际恒有值；类型保守给 `| None` 与域内其他 VO 一致）。
- **service**：`list_kbs()`（全量）退役删除（router 不再调用），新 `page_list_kbs(page, size, name)`；
  dao.list_kbs 保留（域动词集完整性，未来下拉/ingestion 读路径预留，零维护成本）。

### 2.4 编辑 service 编排（示意，@transactional 事务边界）

```python
@transactional
async def update_kb(kb_id: int, req: UpdateKbReq) -> None:
    """编辑知识库：先校验存在（5004），再查重排除自身（5003），最后改字段落库。"""
    session = current_session()
    kb = await dao.find_kb_by_id(session, kb_id)
    if kb is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    same_name = await dao.find_kb_by_name(session, req.name)
    if same_name is not None and same_name.id != kb_id:
        raise BusinessException(MSG_KB_NAME_EXISTS, CODE_KB_NAME_EXISTS)
    try:
        kb.name = req.name
        kb.description = req.description
        await dao.update_kb(session, kb)
    except IntegrityError as e:
        logger.error("update_kb duplicate name (uk_name): %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_KB_NAME_EXISTS, CODE_KB_NAME_EXISTS) from e
```

要点：改名为自己的当前名字放行（same_name.id == kb_id 不算重名）；并发窗口/墓碑占键由 uk_name 的
IntegrityError 兜底（先 log.error 记根因再转 5003，规范第 4 条）；正常返回提交、异常回滚（db/tx.py
声明式事务）。

### 2.5 删除 service 编排（三表级联软删 + c2 向量口径，示意）

```python
@transactional
async def delete_kb(kb_id: int) -> None:
    """删除知识库：KB/文件/chunk 三表软删级联，同一事务；物理向量不动（c2 口径，检索轮治理）。"""
    session = current_session()
    if await dao.find_kb_by_id(session, kb_id) is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
    await dao.delete_chunks_by_kb(session, kb_id)    # 既有动词，命中 idx_kb_parent 的 kb_id 前缀
    await dao.delete_kb_files_by_kb(session, kb_id)  # 本轮新增动词，命中 idx_kb_id(kb_id, deleted)
    rowcount = await dao.delete_kb_by_id(session, kb_id)
    if rowcount == 0:  # 并发窗口兜底：校验后被并发删除；raise 回滚本事务级联（对方事务已删，无害）
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)
```

要点：
- **级联顺序**先子后主（chunk → 文件行 → KB 行），全部软删（UPDATE SET deleted=1 + 审计两值，dao 既有
  口径）；无「库下有文件阻止删除」前置约束——直接级联，契约行为要点写明。
- **向量口径 c2（用户拍板）**：Milvus 物理向量不动。当前检索链路未上线（chat 是无检索纯生成），孤儿
  向量零用户可见影响；检索轮做 collection 库级隔离命名策略时按软删 chunk id 统一清理——`docs/handoff/ai.md`
  记账文案见 §6，实现轮由 backend-agent 兑现落档。
- 删除幂等：二次删除同一 id 走 5004（软删后 find_kb_by_id 不可见）。

### 2.6 schemas 与 DAO 增量

| 位置 | 变更 |
|---|---|
| schemas.py | KbVo 继承 VoTimeMixin + `create_time: datetime \| None`；新增 `UpdateKbReq`（name 1..50 必填、description ≤500 必填——与 CreateKBReq 同口径，独立定义演进自由） |
| dao.py | 新增 `delete_kb_files_by_kb(session, kb_id) -> int`：软删某库全部活文件行（WHERE kb_id + deleted=0，UPDATE SET deleted=1 + 审计两值，返回 rowcount）——形态抄改 `delete_chunks_by_kb` |
| service.py | `list_kbs` 删除；新增 `page_list_kbs` / `update_kb` / `delete_kb`（§2.3/§2.4/§2.5） |
| router.py | `kb_read_router` 的 GET /list 改分页入参出参；新增 `kb_update_router` / `kb_delete_router`（PUT /{kb_id}、DELETE /{kb_id}，两行式返回）；文件头注释同步；main.py include +2 行 |

### 2.7 索引审查（零增量 DDL）

- `page_list_kbs`：WHERE deleted=0（+ name LIKE '%..%' 前置通配**不走索引**）+ ORDER BY id DESC +
  LIMIT/OFFSET。ai_kb 是管理面小表（个位~十位行），主键反向扫无压力——不为 name 前置通配 LIKE 加
  索引（加了也不命中）；量级增长后若搜索成为瓶颈再议方案（前缀匹配/全文索引），当前不设计。
- `delete_kb_files_by_kb`：WHERE kb_id + deleted → 命中既有 `idx_kb_id(kb_id, deleted)`。
- `delete_chunks_by_kb`：命中既有 `idx_kb_parent` 的 kb_id 前缀（dao 注释在案）。
- 结论：**零 DDL、零索引变更**（全部落在既有索引与主键序上）。

## 3. 前端设计（cloud-web）

### 3.1 页面结构与交互（标准表格管理页，path /rag/kb 不变）

文字版布局（表格范式对齐用户/角色管理页；筛选栏形态沿 dataPerm/mqDlq 页先例）：

```
┌─────────────────────────────────────────────────────────────┐
│ [名称搜索框______] [查询] [重置]           [新增知识库(add)]  │
│ ┌─────────────────────────────────────────────────────────┐ │
│ │ 名称 │ 描述 │ 创建时间 │ 操作                             │ │
│ │      │      │          │ [编辑(update)] [删除(delete)]    │ │
│ │      │      │          │ [文件管理]                        │ │
│ │ …（el-table，loading 态；行 = KbVo）                      │ │
│ └─────────────────────────────────────────────────────────┘ │
│  el-pagination：total（number 直取）· [10,20,50] ·           │
│  layout="total, prev, pager, next, sizes"                    │
└─────────────────────────────────────────────────────────────┘
点「文件管理」→ 右侧滑出 el-drawer（size 60%，工程内首用，按需引入自动解析）：
┌────────────────────────────────┐
│ <库名>（drawer title） [上传文件(add)] │
│ 文件名 │ 分片类型(tag) │ 上传时间    │
│ el-pagination（自现 index.vue 平移）   │
└────────────────────────────────┘
```

- **搜索栏**：name 输入框（maxlength 50）+「查询」（回第一页加载）+「重置」（清空 name 回第一页）；
  入参 `name` 仅在非空时携带。
- **操作条**：「新增知识库」按钮 `v-perms="'ai:kb:add'"`（无权限自动隐藏，沿现状）。
- **表格**：四列——名称 / 描述（null 显示「暂无描述」，沿现状）/ 创建时间（字符串直显）/ 操作；操作列
  三按钮：编辑 `v-perms="'ai:kb:update'"`、删除 `v-perms="'ai:kb:delete'"`、文件管理（查询类动作，
  页面可达即有 `ai:kb:list`，不挂 v-perms）。
- **删除确认**：`ElMessageBox.confirm`（沿 user/mqDlq 先例），确认后调删除 api → toast → 刷新当前页；
  当前页删空且 page>1 时回退上一页再查（防空页滞留）。
- **编辑弹窗**：KbFormDialog add/edit 双模式（编辑态回显 name/description，标题「编辑知识库」；
  沿 UserFormDialog 先例）；成功后 toast + 刷新当前页。
- **文件抽屉**：FilesDrawer 承载现右栏全部能力（上传 accept=".pdf,.docx" 自定义 http-request、
  文件表三列、分页 [10,20,50]）——代码从现 index.vue 平移，能力面不增不减；打开时回第一页加载；
  上传成功回第一页刷新（沿现状）。
- **错误态**：403/1001/5003/5004 后端 msg 由 request.ts 拦截器统一 toast，页面 catch 留空（沿现状）。

### 3.2 组件拆分与文件清单

| 文件 | 职责 |
|---|---|
| `views/rag/kb/index.vue` | 重写：搜索栏 + 操作条 + KB 表格 + 分页 + 删除确认 + 弹窗/抽屉编排 |
| `views/rag/kb/components/KbFormDialog.vue` | 新建（**替代并删除** CreateKbDialog.vue）：add/edit 双模式表单弹窗，props modelValue/mode/kb，emit success |
| `views/rag/kb/components/FilesDrawer.vue` | 新建：文件管理抽屉（上传 + 文件表 + 分页），props modelValue/kbId/kbName |

页面组件名 `RagKb` 不变（= pathToRouteName('/rag/kb')，keep-alive 契约）；路由注册
`router/viewRegistry.ts` 不动。

### 3.3 api 层与类型

`types/api.ts` AI 段：KbVo + `create_time: string`（注释同步「分页 id 倒序」）；新增 `KbPageQuery`
（{ page: number; size: number; name?: string }）与 `UpdateKbPayload`（{ name; description }）；
每类型注契约节号。`api/rag.ts`：`listKbs` 删除，改 `pageKbs(query: KbPageQuery): Promise<AiPageResult<KbVo>>`；
新增 `updateKb(kbId, payload): Promise<null>`（PUT /ai/rag/kb/{kbId}）、`deleteKb(kbId): Promise<null>`
（DELETE /ai/rag/kb/{kbId}）。createKb / uploadKbFile / pageKbFiles 不变。

### 3.4 e2e 场景（cloud-e2e，route mock 分层，重写 run-ai-kb-e2e.mjs）

沿既有 route mock 骨架（`page.route('**/api/ai/**')` fulfill 假 R JSON，登录走真实网关，8010/Milvus
零依赖）；mock 层 list 分支改分页切片 + name 过滤内存实现，新增 update/delete 分支内存态维护。场景
AC1-AC9：菜单可达+表格渲染 → 搜索（name 参数断言 + 重置回全量）→ 新建流（空提交内联必填 + 表格刷新）
→ 重名 5003（弹窗不关）→ 编辑流（回显 + 提交刷新 + 重名 5003 分支）→ 删除流（确认框 + 删空回退页）
→ 文件抽屉（打开 + files 请求 kb_id/page/size 断言 + 分页翻页）→ 上传（multipart 断言 + 抽屉内刷新）
→ KB 表分页翻页（page=2 断言）。完整清单见实施计划 I1。

## 4. 测试策略

### 4.1 后端单测（SQLite 内存替身，不连真实外部系统；工程根跑全量 pytest）

- service 层（`make_service_session_factory` + monkeypatch 既有模式）：
  - `page_list_kbs`：空集（total=0 rows=[]）/ 多库 id 倒序 / name 包含过滤与不过滤（None/空串）/ 软删库
    不可见 / 钳制（page=0&size=0 不抛错）/ create_time 输出 `yyyy-MM-dd HH:mm:ss`；
  - `update_kb`：库不存在 5004 / 改成他库名 5003 / 保持原名放行 / 成功改 name+description（回查断言）；
  - `delete_kb`：库不存在 5004 / happy path 三表级联断言（KB、文件行、chunk 行 deleted 全置 1，
    find 不可见）/ 二次删 5004。
- router 层（TestClient + mock service 函数；client fixture 补挂两个新 router）：
  - list：无 header 403 / 错 perms（ai:kb:add）403 / 200（**断言 total 为 JSON number**）/ page=abc 1001 /
    name 超 51 字 1001；
  - update：无 header 403 / ai:kb:add 也不行（权限拆分）403 / kb_id=abc 1001 / 5004 / 5003 / 200 data=null；
  - delete：403 / kb_id=abc 1001 / 5004 / 200 data=null。
- 收口：全量 pytest 全绿（含 test_architecture_guard.py 零改即绿——service 的 except 记日志、
  @transactional、router 不碰 dao 等新代码天然合规）；已知 2 个 settings 测试失败系本地 .env 污染
  与本轮无关。

### 4.2 前端验证

`npm run build` 连续两次全绿（按需引入 dts 首次不存在的已知坑；el-drawer 工程首用，build 后核对
components.d.ts 含 ElDrawer 确认按需解析）→ dev 起 5173 经 proxy/网关联调真实接口（admin 落库
184/185 后须重新登录刷新 perms 快照）。

### 4.3 运行时双路实证（后端 agent，curl）

8010 直连（手工带 X-User-Perms/X-User-Account）+ 网关全链（admin 登录换 token）各跑一遍：list 分页
（默认参 200 / page=2&size=1 切片断言 / name=xx 过滤 / page=abc 1001 / 无 header 403）；编辑（200 /
重名 5003 / kb 不存在 5004 / 错 perms 403）；删除（建测试库 + 上传可选 → 删 200 → 再查 files 5004
（级联可见性实证）→ 二次删 5004）。测试数据 `e2e_kb_` 前缀 + 时间戳；本轮有删除端点，残留数据顺手
清理。结果回填契约 §9 验证记录。

## 5. 种子（cloud_system 库）

- **新增** `cloud-base/scripts/sql/2026-10-15-ai-kb-perms.sql`（增量脚本，沿 2026-10-14-ai-kb-create.sql
  形态）：184 F「编辑」perms `ai:kb:update` sort 2、185 F「删除」perms `ai:kb:delete` sort 3，均挂
  182 下（path/icon 空，形态沿 183 先例）；绑定段 `WHERE id IN (184, 185)`；头注前置断言（184/185 空闲 +
  父 182 存在）。
- **基线同步** `cloud-base/scripts/sql/cloud_system.sql` 18 段 4 行 → 6 行（补 184/185）。
- 落库走 pymysql（中文入库规则，见 CLAUDE.md）；落库后 **admin 须重新登录**（perms 快照登录时定格，
  否则编辑/删除按钮 403）。
- **e2e 版图断言同步（主控已核实影响面，确定性任务）**：`cloud-e2e/run-menu-e2e.mjs` 与
  `cloud-e2e/run-role-e2e.mjs` 各自 SEED_MENU_IDS 全量清单补 '184','185'（48→50）及关联注释文案；
  run-nav-e2e.mjs（F 型不入导航，183 先例实证）与 dict/bpmn/dataperm 三脚本零影响。

## 6. handoff 记账（`docs/handoff/ai.md` 追加条目，实现轮由 backend-agent 落档）

```markdown
## 已删知识库/文件的孤儿向量未清理

- **待办**：删除知识库走 MySQL 三表（ai_kb/ai_kb_file/ai_kb_chunk）软删级联，Milvus 物理向量不动
  （collection "test123" 全库共用、向量按 chunk pk 存）。检索轮做 collection 库级隔离命名策略时，
  按 deleted=1 的 chunk id 统一清理物理向量并迁移存量。
- **命令**：无（设计项，随检索轮 collection 命名策略一并定夺）。
- **预期**：孤儿向量不进入检索结果（检索上线前治理完成）；已删数据可按软删标记追溯。
- **登记日期**：2026-10-15（列表管理轮，c2 软删记档口径拍板）
```

## 7. 移交备忘（本轮产生/延续的下一轮起点）

| 事项 | 说明 |
|---|---|
| 错误码 AI 段下一可用 | 仍为 **5005**（本轮零新增：5003/5004 复用） |
| 孤儿向量清理 | §6 新记条目，检索轮统一治理（与 collection 命名策略、上传链路事务一致性两条既有欠账同域） |
| dao.list_kbs 零消费方 | 保留（域动词集完整性，下拉/ingestion 预留）；后续长期无消费方可在检索轮评估删除 |
| name 搜索 LIKE 前置通配不索引 | ai_kb 小表无压力；量级增长后若搜索成瓶颈再议（前缀匹配/全文索引） |
| 文件级删除（档位三） | 本轮排除；若后续提出，错误码从 5005 起（「文件不存在」语义，5004 msg 不兼容） |
