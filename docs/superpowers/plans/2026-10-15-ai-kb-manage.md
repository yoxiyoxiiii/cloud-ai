# AI 知识库管理实施计划（2026-10-15）

> 全栈 A 级轮：后端 2 个查询端点 + 种子修订 + Milvus 配置外置；前端知识库管理页（两栏）。
> 设计：`docs/superpowers/specs/2026-10-15-ai-kb-manage-design.md`；契约：
> `docs/superpowers/contracts/2026-10-14-ai-kb-api.md`（前后端唯一对齐物，实现与联调逐字段对它）。
> 后端章与前端章**独立可并行**（前端先按契约 mock，集成阶段切真实）。

## 0. 环境前置与清欠账（开工先做）

环境现状（2026-10-15 实测，实现时复核）：

| 组件 | 状态 | 本轮角色 |
|---|---|---|
| MySQL 127.0.0.1:3306 | 已起（cloud_ai 有实测 2 KB/2 文件；cloud_system 无 182/183） | B1 落库、B6 实证、集成联调 |
| Milvus | 容器 Exited(80)，**用户将重起（宿主端口 9092）** | 仅 §3 上传真实链路实证需要（B6）；e2e 不依赖 |
| Ollama（127.0.0.1:11434，embedding） | 未确认 | 同上——B6 前探测，不可达上报主控（实现时验证） |
| cloud-base-ai :8010 | 在跑（改码后由 backend-agent 重启） | B4/B6 |
| 网关 18080 / sso 9201 / system 9202 | 未起 | B6 网关全链、集成阶段由 agent 自起 |
| 前端 dev 5173 | 未起 | 前端章 F4 起 |

清欠账映射（SOP：同域开工先清账；`docs/handoff/ai.md` 现有 4 条）：

| handoff 条目 | 处置 |
|---|---|
| 知识库菜单种子 182 未落库 | B1 修订版落库且回查后**删条目** |
| 知识库创建端点未做双路实证 | B6 curl 覆盖 create_kb 双路后**删条目** |
| 分块表 ai_kb_chunk 未在真库建表 | B0 前置验证：cloud_ai 已有 2 文件实测数据，表大概率已在（pymysql 查 information_schema）——在则**删条目**；不在则按 `cloud-base/scripts/sql/cloud_ai.sql` 该段建表后删 |
| 向量写入与 MySQL 写入不在同一事务 | **保留**（范围外设计项）；B1 顺手追加一条「Milvus collection 名硬编码 test123」欠账（设计文档移交备忘） |

---

## 1. 后端章（backend-agent，cloud-base-ai + 种子脚本；可与前端章并行）

命令口径：uv 不在 Git Bash PATH，用绝对路径
`C:/Users/jy-root/.conda/envs/cloud-ai/Scripts/uv.exe run --project cloud-base-ai pytest`；
对 MySQL 读写中文一律 pymysql（脚本源码 UTF-8 + charset="utf8mb4"，见 CLAUDE.md）。

### B0 前置清账与探测

- 文件：`docs/handoff/ai.md`（清账）；临时 python 脚本不落仓。
- 做法：①pymysql 查 information_schema 确认 `cloud_ai.ai_kb_chunk` 存在（含 idx_kb_parent/idx_file）；
  ②探测 Milvus（用户重起后 `curl http://127.0.0.1:9092/healthz` 或等价——实现时验证端口/端点形态）与
  Ollama 11434 可达性，结果记进实施报告（不可达不阻塞 B1-B5 编码）。
- 验收：ai_kb_chunk 在→删 handoff 对应条目；不在→建表后删；Milvus/Ollama 状态已记录。

### B1 种子脚本修订与落库

- 文件：`cloud-base/scripts/sql/2026-10-14-ai-kb-create.sql`（修订）、
  `cloud-base/scripts/sql/cloud_system.sql`（基线 18 段同步）。
- 做法：增量脚本改为两段 INSERT——182 perms `ai:kb:list`（其余列不变）+ 183 F「新增」perms `ai:kb:add`
  parent 182 sort 1（path/icon 空）；绑定段 `WHERE id IN (182, 183)`；头注前置断言更新为「182/183 空闲 +
  父 18 存在」，并写明应急分支：断言失败（旧版 182 已落库）→ 停下改跑 `UPDATE sys_menu SET
  perms='ai:kb:list' WHERE id=182` 后仅执行 183 段，回报主控。基线 cloud_system.sql 18 段 3 行改 4 行
  （182 perms 改 list、新增 183；admin 绑定走既有 SELECT 全量式天然覆盖，核对无需显式段）。
- 落库执行：pymysql 执行修订版脚本（前置断言先跑）→ 回查：182（perms/path/type/parent）、183（perms/
  parent/type）、sys_role_menu 182/183 各 1 行（role_id=1）→ 删 handoff「种子 182」条目。
- 验收：回查四项全对；报告注明「admin 须重新登录刷新权限快照」（B6 网关实证时自然验证）。

### B2 Milvus 连接配置外置

- 文件：`cloud-base-ai/src/cloud_base_ai/config.py`（+MilvusSettings：uri 默认
  `http://127.0.0.1:19530`，env 覆盖键 `CLOUD_BASE_AI_MILVUS__URI`）、
  `cloud-base-ai/src/cloud_base_ai/rag/vectorstore.py`（读 `get_settings().milvus.uri`，
  lru_cache 单例与 collection 使用方式不动）、`cloud-base-ai/tests/test_settings.py`（+milvus 段断言）。
- 做法：见设计 §2.7；本机 9092 写 `.env`（环境级不入库不入档）。已知 2 个 settings 测试失败为本地 .env
  真实 key 污染，与本项目无关，不修不阻塞。
- 验收：pytest settings 段新用例绿（默认 19530 + env 覆盖生效）；`.env` 键不进 git。

### B3 出参 schema（VO / 分页容器 / 时间序列化）

- 文件：`cloud-base-ai/src/cloud_base_ai/rag/schemas.py`（+KbVo、KbFileVo、AiPageResult[T]、
  VoTimeMixin——field_serializer 见设计 §2.4，mixin 异常则降级各 VO 显式声明）、
  `cloud-base-ai/tests/test_rag_schemas.py`（+用例）。
- 验收：①AiPageResult 序列化 `{total, rows}`；②KbFileVo.create_time 输出 `yyyy-MM-dd HH:mm:ss` 字符串
  （None→null）；③KbVo.description 可 None；④全量 pytest 绿。

### B4 两个读端点（service + router，TDD——先写测试红再实现）

- 文件：`cloud-base-ai/src/cloud_base_ai/rag/router.py`（+kb_read_router：prefix `/rag/kb`、路由级
  `require_perms("ai:kb:list")`、GET /list 与 GET /{kb_id}/files，两行式返回；main.py +1 行 include）、
  `cloud-base-ai/src/cloud_base_ai/rag/service.py`（+list_kbs / page_list_kb_files，@transactional，
  5004 复用既有常量——见设计 §2.5 示意）、`cloud-base-ai/tests/test_rag_service.py`（+用例）、
  `cloud-base-ai/tests/test_rag_router.py`（+用例；client fixture 补挂 kb_read_router）。
- service 用例：list 空集 / 多库 id 升序且字段逐项映射（含 description=None）/ 软删库不可见；files
  库不存在 5004 / happy path（total、rows 字段与时间格式）/ page=0&size=0 钳制不抛错。
- router 用例：两端点各——无 header 403 / 错 perms（ai:kb:add）403 / 200（**断言 id 与 total 为 JSON
  number**）；files 另有 kb_id=abc 1001、page=abc 1001、5004。
- 验收：全量 `pytest` 全绿（含 test_architecture_guard.py，95+新增）；8010 重启后
  `curl -H "X-User-Perms: ai:kb:list" http://127.0.0.1:8010/rag/kb/list` 返回 200 数组。

### B5 后端收口

- 验收：①全量 pytest 绿；②守护测试零改即绿（新代码天然合规，若红按 /ai-spec 修代码不改守护）；
  ③Milvus collection 硬编码欠账已追加进 `docs/handoff/ai.md`（B1 一并做则此处核对）。

### B6 运行时双路实证与契约回填（网关链路前置：自起 sso/system/网关，收口自 kill）

- 文件：`docs/superpowers/contracts/2026-10-14-ai-kb-api.md`（§7 回填）、`docs/handoff/ai.md`
  （create_kb 双路条目删除）。
- 直连 8010（手工 header）：GET /rag/kb/list（ai:kb:list）200 数组；无 header 403；GET /rag/kb/1/files
  ?page=1&size=10 200（total/rows/时间格式）；kb_id=9999 → 5004；page=abc → 1001。
- 网关全链（admin 登录换 token，经 18080 /ai 前缀）：GET /ai/rag/kb/list 200；POST /ai/rag/kb 创建
  `e2e_kb_<时间戳>` 200 新 id；同名再建 5003；**上传**（`curl -F "file=@<pdf路径>"`，**前置：B0 探测
  Milvus 9092 与 Ollama 可达**，不可达则本项挂起走 SOP 欠账流程并明示用户）200 data=kb_id → 再 GET
  files 见新行（file_name/split_type/create_time）→ pymysql 回查该文件分块行与 create_by=admin（中文
  回查走 pymysql）。
- 测试数据纪律：`e2e_` 前缀+时间戳；无删除端点（范围外），残留记录报告注明（可选 pymysql 软删清理）。
- 验收：契约 §7 逐项回填（含 2026-10-14 挂起段按实际结果改写）；handoff 对应条目删除；8010 与网关链
  服务按 SOP「服务进程管理」收口 kill。

---

## 2. 前端章（frontend-agent，cloud-web；可与后端章并行，先契约 mock）

### F1 类型与 api 函数

- 文件：`cloud-web/src/types/api.ts`（AI 段追加，每类型注契约节号）：`AiPageResult<T>`（total: **number**，
  注明不复用 PageResult 的原因）、`KbVo`、`KbFileVo`、`CreateKbPayload`；`cloud-web/src/api/rag.ts`
  （追加四函数+文件头注释更新）：listKbs / createKb / uploadKbFile（FormData + **timeout 120000**）/
  pageKbFiles（query 名 `page`/`size`，非 pageNum/pageSize）。
- 验收：函数签名与契约 §2/§3/§4/§5 逐字段一致；TS strict 零 any 零裸 as。

### F2 页面与组件

- 文件：`cloud-web/src/views/rag/kb/index.vue`（两栏：左 KB 列表+新建按钮 v-perms
  `ai:kb:add`；右 el-table 三列[文件名/分片类型 tag/上传时间]+el-pagination+el-upload 内联；空态两处
  [无库/未选中]；结构见设计 §3.1）、`cloud-web/src/views/rag/kb/components/CreateKbDialog.vue`
  （name 1..50 必填 + description ≤500 必填；props modelValue / emit update:modelValue+success）。
- 要点：`defineOptions({ name: 'RagKb' })`（= pathToRouteName('/rag/kb')，keep-alive 契约）；表格列插槽
  row 收窄用集中 `rowOf` 一处断言；分片类型常量映射（`parent-child`→「父子分片」，未知原样）；上传
  `accept=".pdf,.docx"`、`:show-file-list="false"`、自定义 http-request、按钮 v-perms `ai:kb:add`、
  未选库 disabled、成功 toast+回第一页刷新；新建成功 emit success(新库 id)→父刷新左栏并按 id 选中新库；错误态全靠拦截器统一 toast，
  弹窗 catch 留空；两栏 CSS 沿 /rag/chat 页 aside+main 先例。
- 验收：交互六项（选库切换/新建全流/重名 5003 toast 弹窗不关/上传成功刷新/两空态/翻页）自测通过。

### F3 路由注册

- 文件：`cloud-web/src/router/viewRegistry.ts`（import + `'/rag/kb': KbManageView`）。
- 验收：菜单种子 182 落库后（依赖 B1，未落库前用 mock 菜单数据或直接 URL 验证），admin 登录侧边
  「AI 助手→知识库」可达 /rag/kb 且面包屑正确。

### F4 构建验证

- 验收：`npm run build` **连续两次**全绿（首次生成 components.d.ts、二次类型更严——按需引入已知坑）。

### F5 dev 联调（mock → 真实切换）

- 做法：后端未就绪先按契约 mock（Vite proxy 临时本地 json 或页面临时数据，报告中注明）；B4/B6 就绪后
  切真实：dev 5173 → proxy /api → 网关 18080 → /ai → 8010。admin 重新登录（新菜单+新 perms 快照）。
- 验收：真实链路下「列表→新建→上传→文件表刷新」全流通（上传项若 Milvus 未起则按 B6 同口径挂起并明示）；
  `curl http://localhost:5173/api/system/demo/ping` 经代理返回 R（联通冒烟）。

---

## 3. 集成章（frontend-agent 主导，后端就绪后）

### I1 e2e 场景 + 回归 + 视觉核对 + 服务收口

- 文件：`cloud-e2e/run-ai-kb-e2e.mjs`（新）、`cloud-e2e/package.json`（+`"e2e:aikb"` script）——
  **与功能代码同一 commit**（场景脚本纪律）。
- 做法：沿 `run-ai-kb-e2e` 同目录 aichat 先例 route mock 分层（page.route fulfill 假 R JSON，
  **cloud-base-ai 8010 与 Milvus 均不需起**；admin 登录仍需网关/sso/system）：
  - AC1 菜单可达：admin 登录 → AI 助手 → 知识库 → /rag/kb，左栏渲染 mock 2 库；
  - AC2 选中切换：点击第 2 库 → files 请求 URL 带 kb_id 断言 + 表格渲染 mock 行；
  - AC3 新建流：空提交内联必填（0 请求）→ 填写提交 mock 200 → toast + 左栏刷新含新库且选中；
  - AC4 重名错误：mock {code:5003} → toast 文案断言 + 弹窗不关；
  - AC5 分页：mock total=15 → 翻第 2 页 → 请求 page=2 断言；
  - AC6 上传：mock upload 200 → 表格刷新 toast（断言 multipart 请求已发出）。
- 回归：`cd cloud-e2e && npm run e2e`（全量聚合防破既有页）+ `npm run e2e:aichat` + `npm run e2e:aikb`
  ——结果只认 grep -c '[FAIL]'（harness 不设退出码的已知坑）。
- 视觉核对：≥5 张关键截图（页面全貌两栏/新建弹窗/文件表格/空态/错误 toast）→ 上传平台取远程 URL →
  analyze_image 比对（布局两栏、按钮权限可见性、表格列与分页要素）。
- 收口：联调+冒烟+回归全过 → kill 本轮 agent 起的全部服务（netstat 找 PID + taskkill，SOP「服务进程
  管理」）。
- 验收：全部 AC 通过、全量回归零 FAIL、视觉核对结论入报告、服务零残留。

---

## 4. 给 backend-agent 与 frontend-agent 的任务清单

**backend-agent**（顺序 B0→B6；与前端并行）：B0 清账探测 → B1 种子修订+落库（含 handoff 追加
test123 欠账）→ B2 Milvus 配置外置 → B3 schemas → B4 两读端点 TDD → B5 全量 pytest 收口 → B6 curl
双路实证+契约 §7 回填+handoff 清账+服务收口。纪律：零 git 写操作；.env 真值不入库；契约是唯一对齐物，
发现缺陷回报主控不得单方改。

**frontend-agent**（顺序 F1→F5，I1 待后端 B4/B6 后）：F1 类型与 api → F2 页面与弹窗 → F3 路由注册 →
F4 build×2 → F5 dev 联调（mock→真实）→ I1 e2e 脚本+全量回归+视觉核对+服务收口。纪律：按需引入红线
（禁全量 ElementPlus）；types/api.ts 为类型唯一来源；组件名 'RagKb' 不可省；上传/新建按钮 v-perms
`ai:kb:add`；契约缺陷回报主控不得单方改。

**并行衔接点**：前端 F5 真实联调依赖 B1 落库（菜单/权限）+ B4 端点就绪；在此之前 F1-F4 + mock 联调
不受阻塞。I1 的 aichat/aikb e2e 只依赖网关/sso/system + 前端 dev，不依赖 8010。

## 5. 移交备忘（本轮产生的下一轮起点）

- Milvus collection 名硬编码 `"test123"`（B1 记入 handoff/ai.md）——collection 命名策略与库级隔离待
  ingestion 轮定夺。
- 向量与 MySQL 写入事务一致性补偿（handoff 既有，未动）。
- KB 列表不分页 / 库级计数恒 0 / 文件级分块数不展示——增长后按契约 additive 扩展（设计文档移交备忘表）。
- 错误码 AI 段下一可用 **5005**（本轮零新增）。
