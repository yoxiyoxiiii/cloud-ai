# AI 知识库管理（2 查询端点 + 前端管理界面）技术方案（2026-10-15）

> 全栈 A 级轮。设计输入 = 阶段一需求分析 + 用户拍板四项：① 新增 2 个查询端点（知识库列表 / 指定库文件列表，
> 对「其他接口不做新增」的唯一豁免）；② 权限拆分——查询与菜单用 `ai:kb:list`，创建+上传沿用 `ai:kb:add`；
> ③ 前端单页面两栏（左知识库列表 + 右文件表格）；④ 不显示文档数/分块数（库级 file_count/file_chunk_count
> 恒 0，不显示、不 JOIN、不统计）。拍板结论不再复议。
> 关联文档：契约 `docs/superpowers/contracts/2026-10-14-ai-kb-api.md`（本轮扩展）、
> 实施计划 `docs/superpowers/plans/2026-10-15-ai-kb-manage.md`。

## 1. 背景、范围与结论总览

**现状**：cloud-base-ai（Python/FastAPI，:8010，网关前缀 /ai）RAG 域已有 4 个端点且全为写操作——
POST /rag/chat（含 /stream）与 POST /rag/kb 创建、POST /rag/kb/upload 上传。查询零端点，前端零知识库界面
（cloud-web 仅 /rag/chat 对话页）。DAO 查询函数已备（`list_kbs` / `page_list_kb_files` / `find_kb_by_id`，
含软删过滤与分页钳制），新端点 service 层是薄壳，无新 DAO、无新表、零 DDL。

**本轮结论**（详细取舍见后文各节）：

| # | 结论 | 章节 |
|---|---|---|
| 1 | 读端点新开 `kb_read_router`（路由级 `ai:kb:list` 依赖），既有写 router 不动 | §2.2 |
| 2 | AI 域出参全局口径：snake_case 字段 + id/total 为 JSON number + 时间 `yyyy-MM-dd HH:mm:ss` | §2.3 |
| 3 | 知识库列表不分页（全量、左栏滚动区）；文件列表分页（右栏表格） | §2.1/§3.1 |
| 4 | 文件分页先校验知识库存在（5004），与上传端点同语义 | §2.5 |
| 5 | Milvus 连接参数从硬编码外置进 config（上传链路运行时验收前置 + 规范合规） | §2.7 |
| 6 | e2e 沿 AI 对话页先例走 route mock（8010/Milvus 零依赖）；真实链路由后端 curl 双路实证覆盖 | §4.3 |
| 7 | 种子修订：182 perms 改 `ai:kb:list` + 新增 183 F 型按钮 `ai:kb:add` | §5 |

**范围外（明确不做，防蔓延）**：删除/重命名知识库、删除文件、知识库编辑详情、文档数与分块数统计展示、
chunk 管理界面、检索测试界面、ingestion 轮全部内容。Milvus collection 名硬编码 `"test123"` 的技术债
记档移交（§6）。**本轮零新增错误码**（AI 段已用至 5004，下一可用 5005 保持空闲）。

## 2. 后端设计（cloud-base-ai）

### 2.1 端点总览

| 方法 | 服务侧路径 | 权限 | 入参 | 返回 | 错误码 |
|---|---|---|---|---|---|
| GET | /rag/kb/list | `ai:kb:list` | 无 | `R<KbVo[]>` | 200 / 403 |
| GET | /rag/kb/{kb_id}/files | `ai:kb:list` | kb_id 路径 + page/size query | `R<AiPageResult<KbFileVo>>` | 200 / 403 / 1001 / 5004 |

完整逐字段定义见契约 §4/§5。查询参数名 `page`/`size`（Python 域原生口径，与 Java 域 pageNum/pageSize 不同，
契约 §1 已显式标注）。越界值不报错由既有 `_clamp_page` 钳制（page≥1、1≤size≤200）；非整数走 FastAPI 校验
→ 1001。

### 2.2 取舍一：读/写两个 router（均路由级权限依赖）

- 背景：既有 `kb_router`（router.py）是**路由级**依赖 `require_perms("ai:kb:add")`，新读端点权限是
  `ai:kb:list`，两者不同，必须拆。
- 候选：A）新增 `kb_read_router`（prefix 同为 `/rag/kb`，路由级 `ai:kb:list`），写 router 原样不动，
  main.py 加一行 include；B）去掉 router 级依赖，全部端点各自挂 `Depends(require_perms(...))`。
- 结论：**选 A**。理由：`/ai-spec` 记载「漏写权限依赖不会有任何报错，请求直接放行」——路由级依赖下，
  今后挂进 router 的新端点**自动**受保护；端点级依赖每加一个端点就多一次人工记得的机会。B 还要改动两个
  已上线端点（create/upload），违背最小改动。FastAPI 同前缀多 router 无冲突（按方法+路径精确匹配）。

### 2.3 取舍二：出参字段保持 snake_case（AI 域全局口径，一次定版）

- 背景：Python 侧多词字段（file_name/create_time/file_split_type）序列化用哪种命名风格，本轮首例定调。
- 候选：A）pydantic 默认 snake_case 直出；B）逐字段 alias 转 camelCase 对齐 Java 域习惯。
- 结论：**选 A，AI 域全局一致**。理由：B 需要每字段维护 serialization_alias，新增字段漏写即出现同域
  两种风格混排，长期更乱；cloud-base-ai 是 Python 工程，工程精神延续而非机械套用 Java 规范。配套口径
  （一并定版，前端按此消费）：
  - `id`/`total` 为 JSON **number**（Python 侧无 Long→String 转换；自增 id 值域内安全）；
  - 时间字段统一字符串 `yyyy-MM-dd HH:mm:ss`（见 §2.4 序列化 mixin——对齐 Java 侧 Jackson 全局格式，
    前端日期展示口径跨域一致）；
  - 分页容器 `AiPageResult<T> = {total: number, rows: T[]}`——前端**不复用** Java 域 `PageResult<T>`
    （其 total 为 string）。

### 2.4 VO 与数据结构（pydantic，落 schemas.py）

| VO | 字段（类型） | 说明 |
|---|---|---|
| KbVo | id: int、name: str、description: str \| None | 左栏列表项。**不含** create_time 与两个库级计数（最小出参面，需要时 additive 补） |
| KbFileVo | id: int、file_name: str、file_split_type: str、create_time: str | 右栏表格行。create_time 序列化后为 `yyyy-MM-dd HH:mm:ss` 字符串 |
| AiPageResult[T] | total: int、rows: list[T] | AI 域分页容器（Generic），与 Java 域 PageResult 并存 |

字段裁剪口径：库级 file_count/file_chunk_count 恒 0 不入出参（拍板④）；文件级 file_chunk_count 虽为上传
链路写入的真实数据，按拍板「不显示分块数」**从严解读**本轮亦不展示（后续轮 additive 可补，不动既有字段）。

时间序列化 mixin（关键示意，pydantic v2 `field_serializer`；若 `check_fields` 在 mixin 场景表现异常，
降级为各 VO 显式声明——实现时验证）：

```python
class VoTimeMixin(BaseModel):
    """出参时间统一 'yyyy-MM-dd HH:mm:ss'（对齐 Java Jackson 全局格式，前端展示口径一致）。"""

    @field_serializer("create_time", check_fields=False)
    @classmethod
    def _ser_create_time(cls, v: datetime | None) -> str | None:
        return v.strftime("%Y-%m-%d %H:%M:%S") if v else None
```

### 2.5 service 薄壳设计

无新 DAO。两个读编排方法均 `@transactional`（`current_session()` 的硬性要求，读路径正常返回即提交、
无写语义副作用）；VO 转换在 service 层原生逐字段构造（对齐 Java「Service 层统一转换、禁三方拷贝工具」）：

```python
@transactional
async def list_kbs() -> list[KbVo]:
    """全量活知识库（id 升序，管理面左栏——MVP 库量小不分页）。"""
    rows = await dao.list_kbs(current_session())
    return [KbVo(id=r.id, name=r.name, description=r.description) for r in rows]


@transactional
async def page_list_kb_files(kb_id: int, page: int, size: int) -> AiPageResult[KbFileVo]:
    """分页列某知识库文件：先校验库存在（5004，与上传端点同语义——前端可据此刷新左栏）。"""
    session = current_session()
    if await dao.find_kb_by_id(session, kb_id) is None:
        raise BusinessException(MSG_KB_NOT_EXISTS, CODE_KB_NOT_EXISTS)  # 既有常量，零新增码
    rows, total = await dao.page_list_kb_files(session, kb_id, page, size)
    vos = [KbFileVo(id=r.id, file_name=r.file_name,
                    file_split_type=r.file_split_type, create_time=r.create_time) for r in rows]
    return AiPageResult(total=total, rows=vos)
```

router 端两行式返回（对齐既有端点形态）；kb_read_router 定义见 §2.2 结论。

### 2.6 索引审查（零增量 DDL）

- GET /rag/kb/list → `dao.list_kbs`：全表活记录过滤 + 主键升序。知识库是管理面小表（个位~十位数），
  主键序天然有序，无需索引（沿 2026-10-13 ORM/DAO 轮的索引设计，不重开）。
- GET /rag/kb/{kb_id}/files → `dao.page_list_kb_files`：WHERE kb_id + deleted → 命中既有
  `idx_kb_id(kb_id, deleted)`；排序 id DESC 走索引回表主键反向，行数受 kb_id 过滤约束。建索引时的取舍
  记档在 `cloud-base/scripts/sql/cloud_ai.sql` 表注释。
- 结论：**本轮零 DDL、零索引变更**（无新查询路径，两查询均落在既有 DAO 函数与既有索引上）。

### 2.7 Milvus 连接配置外置（上传链路运行时验收的前置）

- 背景：`rag/vectorstore.py` 的 `get_milvus()` 硬编码 `uri="http://localhost:19530"`；用户将把 Milvus
  容器重起到宿主端口 **9092**——不外置则上传真实链路验收必然连不上。且 `/ai-spec`「接入外部系统」明确
  要求「连接信息走 config.py 配置对象，不在代码里写死地址」，本就是合规缺口。
- 做法：config.py 增 `MilvusSettings`（字段 uri，默认 `http://127.0.0.1:19530` 保持现行为兼容），
  `vectorstore.get_milvus` 改读 `get_settings().milvus.uri`；本机 9092 经 `.env` 键
  `CLOUD_BASE_AI_MILVUS__URI` 覆盖（环境级不入库——同 LLM key 先例）。改动 2 文件，不动 collection 名
  硬编码（范围外，§6 移交）。
- 连带前置：上传链路 embedding 走本机 Ollama（127.0.0.1:11434，既有 `EmbeddingModelSettings` 默认），
  运行时验收需其可达——实现时验证，不可达上报主控。

## 3. 前端设计（cloud-web）

### 3.1 页面结构与交互（单页两栏，path /rag/kb）

文字版布局（两栏 CSS 沿 /rag/chat 页 aside+main 先例）：

```
┌──────────────┬─────────────────────────────────┐
│ 知识库        │  <所选知识库名>        [上传文件] │
│ [新建知识库]  │ ┌─────────────────────────────┐ │
│ ● 产品手册库  │ │ 文件名 │ 分片类型 │ 上传时间  │ │
│ ○ 规章制度库  │ │ …（el-table，loading 态）     │ │
│ …（滚动区）   │ └─────────────────────────────┘ │
│              │  el-pagination（total 为 number）│
└──────────────┴─────────────────────────────────┘
```

- 左栏：头部「新建知识库」按钮 `v-perms="'ai:kb:add'"`（无权限自动隐藏）；列表项 name 为主、description
  为次行；点击选中（active 样式）触发右栏加载；空态文案「暂无知识库」。
- 右栏：未选中知识库时整栏空态「请选择左侧知识库」；表格三列——文件名 / 分片类型（tag：
  `"parent-child"` → 「父子分片」，字面量用常量映射，未知值原样展示）/ 上传时间（字符串直显）。
- 上传：`el-upload` 单文件、`accept=".pdf,.docx"`、`:show-file-list="false"`、自定义 `http-request`
  调 api（multipart 先例 `api/bpmn.ts` deployDefinition——FormData 交 axios 自动设 boundary）；
  按钮与新建同权限门控 `v-perms="'ai:kb:add'"`（无权限隐藏）；未选中知识库时按钮 disabled；
  上传中 loading；成功 `ElMessage.success` + 文件表回第一页刷新。
- 新建：CreateKbDialog 弹窗（name 1..50 必填、description ≤500 **必填**——契约拍板口径），成功后刷新
  左栏并按返回 id 自动选中新库。
- 错误态：403/5003/5004/1001 的后端 msg 由 request.ts 拦截器统一 toast，页面不重复处理；弹窗内 catch
  留空（沿 UserFormDialog 先例）。查询参数无搜索栏（契约现状，后续 additive）。

### 3.2 组件拆分与文件清单

| 文件 | 职责 |
|---|---|
| `views/rag/kb/index.vue` | 页面骨架 + 两栏布局 + 左栏列表 + 右栏表格/分页/上传交互（上传内联，不拆组件） |
| `views/rag/kb/components/CreateKbDialog.vue` | 新建知识库弹窗（表单校验 + 提交 + emit success） |
| `router/viewRegistry.ts` | 注册 `'/rag/kb': KbManageView`（动态路由由菜单种子 182 驱动，仅缺注册表项） |

页面组件 `defineOptions({ name: 'RagKb' })`——必须等于 `pathToRouteName('/rag/kb')` 派生名（keep-alive
include 契约，沿 /rag/chat 先例）。

### 3.3 api 层与类型

`types/api.ts` AI 段追加：`AiPageResult<T>`（total: **number**）、`KbVo`、`KbFileVo`、`CreateKbPayload`
（每类型注契约节号）。`api/rag.ts` 追加四函数：

| 函数 | 方法/URL | 要点 |
|---|---|---|
| listKbs() | GET /ai/rag/kb/list | 返回 KbVo[] |
| createKb(payload) | POST /ai/rag/kb | 返回 number（新库 id） |
| uploadKbFile(kbId, file) | POST /ai/rag/kb/upload?kb_id= | FormData；**timeout 120000**（分片+向量写入长操作，沿 chatRag 先例） |
| pageKbFiles(kbId, query) | GET /ai/rag/kb/{kbId}/files | query 为 `{page, size}`（**非** pageNum/pageSize） |

### 3.4 e2e 场景（cloud-e2e，route mock 分层）

沿 AI 对话页 e2e 先例（`run-ai-chat-e2e.mjs` 的 page.route fulfill 假响应，cloud-base-ai 无需启动）：
AC1 菜单可达（admin → AI 助手 → 知识库）→ AC2 左栏渲染与选中切换（files 请求带 kb_id 断言）→ AC3 新建流
（空提交内联校验 → 成功 toast + 左栏刷新选中）→ AC4 重名 5003 toast 且弹窗不关 → AC5 分页翻页（page
参数断言）→ AC6 上传（mock 200 → 表格刷新）。脚本 `run-ai-kb-e2e.mjs` + npm script `e2e:aikb`，不进
`e2e` 聚合（同 aichat 口径）。真实上传链路（含 Milvus/Ollama）由后端 curl 双路实证覆盖，见 §4.3。

## 4. 测试策略

### 4.1 后端单测（SQLite 内存替身，不连真实外部系统）

- service 层（`make_service_session_factory` + monkeypatch `tx.get_session_factory` 既有模式）：
  list 空集/字段映射/软删不可见/id 升序；files 5004（库不存在）、happy path（total/rows/时间格式化）、
  分页钳制（page=0&size=0 不抛错）。
- router 层（TestClient + mock service 函数）：两端点各自 403（无 header/错 perms）→ 1001（kb_id 或 page
  非整数）→ 5004 → 200（**断言 id/total 为 JSON number**，防转 string）；client fixture 补挂
  kb_read_router。
- 收口：全量 `pytest` 全绿（含 `test_architecture_guard.py`；已知 2 个 settings 测试失败系本地 .env 真实
  key 污染，与本轮无关）。

### 4.2 前端验证

`npm run build` 连续两次全绿（按需引入 dts 首次不存在的已知坑）→ dev 起 5173 经 proxy/网关联调真实接口。

### 4.3 运行时双路实证（后端 agent，curl）

8010 直连（手工带 X-User-Perms/X-User-Account header）+ 网关全链（admin 登录换 token）各跑一遍：
list 200 / files 200·5004·403·1001 / create 200·5003（并入清上一轮 create_kb 双路实证欠账）/
upload 200 真实链路（**前置：Milvus 9092 已起 + Ollama 可达**；再查 files 见新行、DB create_by=admin）。
结果回填契约「验证记录」节。与任务书预设的差异明示：e2e 上传场景因走 route mock 而**不依赖** Milvus，
Milvus 前置只落在后端 curl 实证——验收能力面不缩。

## 5. 种子修订（cloud_system 库，脚本修订 + 落库执行）

- 182 C「知识库」perms **改 `ai:kb:list`**（path /rag/kb、icon Collection、sort 2 挂 18 不变）；
  阶段一实测 182 未落库，零成本改（修订的是脚本与基线，非 UPDATE）。
- **新增 183 F 型按钮**「新增」perms `ai:kb:add` 挂 182 下（sort 1，path/icon 空——F 型形态沿 172/173
  先例）。
- admin 绑定同步（sys_role_menu role_id=1 覆盖 182+183）；`cloud_system.sql` 基线 18 段 3→4 行同步。
- 落库走 pymysql（中文入库规则，见 CLAUDE.md）；前置断言 182/183 空闲、父 18 存在；落库后 admin 须
  重新登录刷新权限快照（perms 快照在登录时定格）。执行步骤与回查清单见实施计划后端任务 B1。

## 6. 移交备忘（技术债记档，本轮不治）

| 事项 | 说明 |
|---|---|
| Milvus collection 名硬编码 `"test123"` | `service.upload_file_by_kb` 内写死；本轮仅外置连接 uri（§2.7），collection 命名策略移交后续轮——建议随本轮在后端任务中登记进 `docs/handoff/ai.md` |
| 向量与 MySQL 写入不在同一事务 | 已在 `docs/handoff/ai.md` 登记（2026-10-15），本轮不动（范围外） |
| KB 列表不分页 | 左栏全量（MVP 库量小）；库数量增长后需加 page 端点（additive，不动既有契约） |
| 库级计数 file_count/file_chunk_count 恒 0 | ingestion 轮维护（用户拍板：本轮不显示、不 JOIN、不统计） |
