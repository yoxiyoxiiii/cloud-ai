# AI 知识库列表管理页实施计划（2026-10-15）

> 全栈 A 级轮：后端 list 端点分页改造 + 编辑/删除两端点 + 种子 184/185；前端知识库页重写为标准表格
> 管理页（搜索+分页+编辑弹窗+删除确认+文件抽屉）。
> 设计：`docs/superpowers/specs/2026-10-15-ai-kb-manage-page-design.md`；契约：
> `docs/superpowers/contracts/2026-10-14-ai-kb-api.md`（2026-10-15 列表管理轮修订版——§4 分页重写、
> §5/§6 新增、原 §5/§6/§7 顺延 §7/§8/§9，实现与联调逐字段对它）。后端章与前端章**独立可并行**
> （前端先按契约 mock，集成阶段切真实）。

## 0. 环境前置与清欠账（开工先做）

环境现状（2026-10-15 实测口径，实现时复核）：

| 组件 | 状态 | 本轮角色 |
|---|---|---|
| MySQL 127.0.0.1:3306 | 已起（cloud_ai 有实测 KB/文件数据；cloud_system 有 182/183 无 184/185） | B1 落库、B5 实证、集成联调 |
| cloud-base-ai :8010 | 在跑（改码后由 backend-agent 重启） | B3/B5 |
| 网关 18080 / sso 9201 / system 9202 | 未起 | B5 网关全链、集成阶段由 agent 自起 |
| 前端 dev 5173 | 未起 | 前端章 F3 起 |
| Milvus / Ollama | **本轮零依赖**（无上传链路改动；e2e 走 route mock、双路实证不含上传） | 无 |

清欠账映射（SOP：同域开工先清账；`docs/handoff/ai.md` 现有 2 条）：本轮**只增不减**——两条既有欠账
（向量事务一致性、collection test123）不动；B1 追加「已删知识库孤儿向量」新条目（设计 §6 文案照抄）。

---

## 1. 后端章（backend-agent，cloud-base-ai + 种子脚本；可与前端章并行）

命令口径：uv 不在 Git Bash PATH，用绝对路径
`C:/Users/jy-root/.conda/envs/cloud-ai/Scripts/uv.exe run --project cloud-base-ai pytest`
（全量 pytest 在 cloud-base-ai 工程根跑）；对 MySQL 读写中文一律 pymysql（脚本源码 UTF-8 +
charset="utf8mb4"，见 CLAUDE.md）。

### B0 前置确认

- 做法：①全量 pytest 基线绿（已知 2 个 settings 测试失败系本地 .env 真实 key 污染，与本轮无关，
  不修不阻塞）；②pymysql 回查 sys_menu：182/183 在、184/185 空（B1 前置断言依据）。
- 验收：基线状态记进实施报告；184/185 空闲确认。

### B1 种子增量 + handoff 记账

- 文件：`cloud-base/scripts/sql/2026-10-15-ai-kb-perms.sql`（新，形态沿 2026-10-14-ai-kb-create.sql：
  头注前置断言「184/185 空闲 + 父 182 存在」+ 两行 INSERT + 绑定段 `WHERE id IN (184, 185)`）、
  `cloud-base/scripts/sql/cloud_system.sql`（基线 18 段 4→6 行，补 184 F「编辑」ai:kb:update sort 2 /
  185 F「删除」ai:kb:delete sort 3，注释同步）、`docs/handoff/ai.md`（追加「已删知识库孤儿向量」
  条目——设计 §6 文案原文照抄）。
- 落库执行：pymysql 执行增量脚本（前置断言先跑）→ 回查：184（perms/type= F/parent=182/sort=2）、
  185（同构 sort=3）、sys_role_menu 184/185 各 1 行（role_id=1）。
- 验收：回查三项全对；报告注明「admin 须重新登录刷新 perms 快照（B5 网关实证时自然验证）」；
  handoff 新条目落档。

### B2 schemas 与 DAO 增量（TDD——先写测试红再实现）

- 文件：`cloud-base-ai/src/cloud_base_ai/rag/schemas.py`（KbVo 继承 VoTimeMixin + `create_time:
  datetime | None`；新增 `UpdateKBReq`：name 1..50 必填、description ≤500 必填）、
  `cloud-base-ai/src/cloud_base_ai/rag/dao.py`（新增 `delete_kb_files_by_kb(session, kb_id) -> int`——
  软删某库全部活文件行，形态抄改 `delete_chunks_by_kb`，注释注明命中 idx_kb_id）、
  `cloud-base-ai/tests/test_rag_schemas.py`（KbVo create_time 序列化 `yyyy-MM-dd HH:mm:ss` 用例）、
  `cloud-base-ai/tests/test_rag_dao.py`（如该文件存在则补 delete_kb_files_by_kb 用例；不存在则并入
  service 测试覆盖，不强制新建）。
- 验收：①KbVo 序列化含 create_time 字符串（None→null）；②UpdateKBReq 校验边界（空 name / 51 字 /
  空 description 拒）；③全量 pytest 绿。

### B3 三端点改造与新增（service + router，TDD）

- 文件：`cloud-base-ai/src/cloud_base_ai/rag/service.py`（删 `list_kbs`；新增 `page_list_kbs(page, size,
  name)` / `update_kb(kb_id, req)` / `delete_kb(kb_id)`，编排示意见设计 §2.3/§2.4/§2.5；IntegrityError
  catch 先 log.error 再转 5003）、`cloud-base-ai/src/cloud_base_ai/rag/router.py`（kb_read_router 的
  GET /list 改分页：page 默认 1 / size 默认 10 / name 可选 max_length=50，出参 `R<AiPageResult<KbVo>>`；
  新增 `kb_update_router`（PUT /{kb_id}）与 `kb_delete_router`（DELETE /{kb_id}），路由级各
  `require_perms("ai:kb:update"/"ai:kb:delete")`，两行式返回；文件头注释同步）、
  `cloud-base-ai/src/cloud_base_ai/main.py`（include +2 行）、
  `cloud-base-ai/tests/test_rag_service.py`、`cloud-base-ai/tests/test_rag_router.py`（client fixture
  补挂两个新 router）。
- service 用例：page_list_kbs——空集 / 多库 id 倒序 / name 包含过滤与 None·空串不过滤 / 软删不可见 /
  钳制 page=0&size=0 不抛错 / create_time 格式化；update_kb——5004 / 改成他库名 5003 / 保持原名放行 /
  成功改两字段回查；delete_kb——5004 / happy 三表级联（KB·文件·chunk 行 deleted 全置 1，find 不可见）/
  二次删 5004。
- router 用例：list——无 header 403 / 错 perms 403 / 200（**断言 total 为 JSON number**）/ page=abc 1001 /
  name 51 字 1001；update——无 header 403 / ai:kb:add 亦 403（权限拆分）/ kb_id=abc 1001 / 5004 / 5003 /
  200 data=null；delete——403 / kb_id=abc 1001 / 5004 / 200 data=null。
- 验收：全量 pytest 绿（含 test_architecture_guard.py **零改即绿**——若红按 /ai-spec 修代码不改守护）；
  8010 重启后 `curl -H "X-User-Perms: ai:kb:list" "http://127.0.0.1:8010/rag/kb/list?page=1&size=10"`
  返回 200 `{"total":N,"rows":[...]}`（rows[0] 为最大 id）。

### B4 后端收口

- 验收：①全量 pytest 绿；②契约三处自检（字段/错误码/权限标识）与实现一致，发现缺陷回报主控不得
  单方改；③handoff 三条（2 旧 + 1 新）状态核对。

### B5 运行时双路实证与契约回填（网关链路前置：自起 sso/system/网关，收口自 kill）

- 文件：`docs/superpowers/contracts/2026-10-14-ai-kb-api.md`（§9 列表管理轮段回填）。
- 直连 8010（手工 header）：§4——默认参 200 / page=2&size=1 切片断言 / name=实测库名片段过滤 /
  page=abc 1001 / 无 header 403；§5——200 / 重名 5003 / kb_id=9999 5004 / 仅 ai:kb:add 403；§6——
  删测试库 200 → 再 GET files 5004（级联可见性）→ 二次删 5004 / kb_id=abc 1001。
- 网关全链（admin 登录换 token 经 18080 /ai）：§4/§5/§6 各抽 happy + 一个错误分支复核直连结论。
- 测试数据纪律：`e2e_kb_` 前缀+时间戳建库用于删除实证；**本轮有删除端点**，上两轮残留的 e2e_kb_*
  测试库顺手清理（删除后回查软删标记）；清理结果记报告。
- 验收：契约 §9 逐项回填；8010 与网关链服务按 SOP「服务进程管理」收口 kill。

---

## 2. 前端章（frontend-agent，cloud-web；可与后端章并行，先契约 mock）

### F1 类型与 api 函数（含契约节号引用同步）

- 文件：`cloud-web/src/types/api.ts`（AI 段：KbVo + `create_time: string`（注释改「分页 id 倒序」）；
  新增 `KbPageQuery` { page: number; size: number; name?: string } 与 `UpdateKbPayload`
  { name: string; description: string }；**既有节号注释同步**：KbFileVo 等引用旧 §5 的注释改 §7——
  本轮契约节号顺延）、`cloud-web/src/api/rag.ts`（删 `listKbs`；新增 `pageKbs(query: KbPageQuery):
  Promise<AiPageResult<KbVo>>`（GET /ai/rag/kb/list，params 只携带非空 name）、`updateKb(kbId, payload):
  Promise<null>`（PUT）、`deleteKb(kbId): Promise<null>`（DELETE）；文件头注释更新；createKb/
  uploadKbFile/pageKbFiles 注释节号同步）。
- 验收：函数签名与契约 §4/§5/§6 逐字段一致；TS strict 零 any 零裸 as；工程内 grep「契约 §5」等旧
  节号引用无漏改（范围仅 kb 相关文件）。

### F2 页面重写与组件（KbFormDialog / FilesDrawer）

- 文件：`cloud-web/src/views/rag/kb/index.vue`（重写：搜索栏[name 输入框 maxlength 50 + 查询回第一页 +
  重置清空回第一页] + 操作条[新增 v-perms ai:kb:add] + el-table 四列[名称/描述 null→「暂无描述」/
  创建时间直显/操作列] + 操作列三按钮[编辑 v-perms ai:kb:update · 删除 v-perms ai:kb:delete ·
  文件管理不挂 perms] + el-pagination[total number 直取 / [10,20,50] / total, prev, pager, next,
  sizes]；删除走 ElMessageBox.confirm，成功后刷新当前页、当前页删空且 page>1 回退上一页；新建/编辑
  成功刷新当前页；结构见设计 §3.1）、`cloud-web/src/views/rag/kb/components/KbFormDialog.vue`（新，
  **替代并删除 CreateKbDialog.vue**：add/edit 双模式——props modelValue/mode('add'|'edit')/kb?，编辑态
  回显 name/description 与标题「编辑知识库」，校验沿旧弹窗 name 1..50 + description ≤500 双必填；
  沿 UserFormDialog 双模式先例）、`cloud-web/src/views/rag/kb/components/FilesDrawer.vue`（新：
  el-drawer size 60%，props modelValue/kbId/kbName；上传[accept=".pdf,.docx"、:show-file-list="false"、
  自定义 http-request、按钮 v-perms ai:kb:add、timeout 120000] + 文件表三列 + 分页——代码自旧 index.vue
  右栏平移，打开时 loadFiles(1)）。
- 要点：`defineOptions({ name: 'RagKb' })` 不变（keep-alive 契约）；表格列插槽 row 收窄集中 `rowOf`
  一处断言；分片类型常量映射随文件表迁入 FilesDrawer；错误态全靠拦截器统一 toast，弹窗/抽屉 catch
  留空；el-drawer 工程首用——按需引入自动解析，F3 build 后核对 components.d.ts 含 ElDrawer。
- 验收：交互自测——搜索/重置、新建全流、编辑回显与提交、删除确认与删空回退、文件抽屉打开/
  上传/翻页、两空态（无库/搜索无结果）、权限按钮可见性（admin 全见）。

### F3 构建验证

- 验收：`npm run build` **连续两次**全绿（首次生成 components.d.ts、二次类型更严——按需引入已知坑）；
  components.d.ts 含 ElDrawer / ElMessageBox（confirm 命令式调用按需引入是否需手动 import——沿
  user 页 `import { ElMessageBox } from 'element-plus'` 显式导入先例，不依赖自动解析）。

### F4 dev 联调（mock → 真实切换）

- 做法：后端未就绪先按契约 mock（B4 就绪后切真实）：dev 5173 → proxy /api → 网关 18080 → /ai →
  8010。**admin 重新登录**（B1 落库 184/185 后 perms 快照需刷新，否则编辑/删除 403）。
- 验收：真实链路「列表分页 → 搜索 → 新建 → 编辑 → 删除 → 文件抽屉上传/翻页」全流通；
  `curl http://localhost:5173/api/system/demo/ping` 经代理返回 R（联通冒烟）。

---

## 3. 集成章（frontend-agent 主导，后端就绪后）

### I1 e2e 场景重写 + 版图断言同步 + 回归 + 视觉核对 + 服务收口

- 文件：`cloud-e2e/run-ai-kb-e2e.mjs`（重写）、`cloud-e2e/run-menu-e2e.mjs` 与
  `cloud-e2e/run-role-e2e.mjs`（**版图断言同步，确定性任务**：各自 SEED_MENU_IDS 全量清单补
  '184','185'（48→50）及关联注释文案——与上轮 daf9ea8 同型机械维护）、`cloud-e2e/package.json`
  （`e2e:aikb` script 已有，不动）——**与功能代码同一 commit**（场景脚本纪律）。
- 场景重写（沿 route mock 分层骨架：`page.route('**/api/ai/**')` fulfill 假 R JSON，登录走真实网关，
  cloud-base-ai 8010 与 Milvus 零依赖；mock 层 list 分支改分页切片 + name 过滤内存实现，新增
  update/delete 分支维护内存态）：
  - AC1 菜单可达：admin 登录 → AI 助手 → 知识库 → /rag/kb 表格渲染 mock 分页 2 行 + 分页 total；
  - AC2 搜索：填 name → 请求带 name 参数断言 + 结果过滤；重置 → 回全量（name 参数不携带断言）；
  - AC3 新建流：空提交内联必填（0 请求）→ 填写提交 mock 200 → toast + 表格刷新；
  - AC4 重名错误：mock {code:5003} → toast 文案断言 + 弹窗不关；
  - AC5 编辑流：编辑按钮 → 弹窗回显原值 → 改名提交 mock 200 → toast + 表格刷新；重名分支 mock 5003；
  - AC6 删除流：删除 → 确认框出现 → 确认 → mock 200 → toast + 行消失（含删空回退页场景可选）；
  - AC7 文件抽屉：点「文件管理」→ drawer 打开 → files 请求 URL 带 kb_id + page=1&size=10 断言 +
    表格渲染 + 分页翻页 page=2 断言；
  - AC8 上传：抽屉内 mock upload 200 → 表格刷新 toast（multipart content-type route 层断言）；
  - AC9 KB 表分页：mock total=15 → 翻第 2 页 → 请求 page=2 断言 + 余 5 行。
- 回归：`cd cloud-e2e && npm run e2e`（全量聚合）+ `npm run e2e:aichat` + `npm run e2e:aikb` + 
  **`npm run e2e:menu` + `npm run e2e:role`**（版图断言改动后两脚本必须复跑）——结果只认
  `grep -c '[FAIL]'`（harness 不设退出码的已知坑）。nav/dict/bpmn/dataperm 四脚本零影响不强制
  单跑（全量聚合已覆盖）。
- 视觉核对：≥5 张关键截图（页面全貌表格+搜索栏/编辑弹窗/删除确认框/文件抽屉/空态）→ 上传平台取
  远程 URL → analyze_image 比对（表格四列与操作列、按钮权限可见性、抽屉内表格+分页要素）。
- 收口：联调+冒烟+回归全过 → kill 本轮 agent 起的全部服务（netstat 找 PID + taskkill）。
- 验收：AC1-AC9 通过、全量回归零 FAIL、视觉核对结论入报告、服务零残留。

---

## 4. 给 backend-agent 与 frontend-agent 的任务清单

**backend-agent**（顺序 B0→B5；与前端并行）：B0 前置确认 → B1 种子增量+落库+handoff 记账 →
B2 schemas/DAO TDD → B3 三端点 TDD → B4 全量 pytest 收口 → B5 curl 双路实证+契约 §9 回填+残留测试库
清理+服务收口。纪律：零 git 写操作；契约是唯一对齐物，发现缺陷回报主控不得单方改；守护测试红了修
代码不改守护。

**frontend-agent**（顺序 F1→F4，I1 待后端 B4/B5 后）：F1 类型与 api（含节号引用同步）→ F2 页面重写+
KbFormDialog/FilesDrawer → F3 build×2 → F4 dev 联调（mock→真实）→ I1 e2e 场景重写+menu/role 版图
断言同步+全量回归+视觉核对+服务收口。纪律：按需引入红线（禁全量 ElementPlus；ElMessageBox 显式
import 沿 user 页先例）；types/api.ts 为类型唯一来源；组件名 'RagKb' 不变；编辑/删除按钮 v-perms
`ai:kb:update`/`ai:kb:delete`；契约缺陷回报主控不得单方改。

**并行衔接点**：前端 F4 真实联调依赖 B1 落库（184/185 权限，admin 须重登）+ B3/B4 端点就绪；此前
F1-F3 + mock 联调不受阻塞。I1 依赖 B1（menu/role 版图断言断的是种子落库后的真实菜单树——若 B1
未落库，menu/role 回归必红，顺序上 I1 排在 B1 之后）。aikb/aichat e2e 只依赖网关/sso/system +
前端 dev，不依赖 8010。

## 5. 移交备忘（本轮产生的下一轮起点）

- 错误码 AI 段下一可用仍 **5005**（本轮零新增，5003/5004 复用）。
- 「已删知识库孤儿向量」handoff 条目（B1 落档）——检索轮随 collection 库级隔离命名策略统一治理；
  与既有两条（向量事务一致性 / collection test123）同域合并推进。
- dao.list_kbs 零消费方保留（域动词集完整性）；检索轮评估去留。
- name 搜索 LIKE 前置通配不索引（ai_kb 小表）；量级增长后若搜索成瓶颈再议。
- 文件级删除（档位三）已排除；若后续提出，「文件不存在」错误码从 5005 起。
