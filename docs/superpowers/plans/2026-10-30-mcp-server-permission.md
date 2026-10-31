# 实施计划：MCP Server 用户级权限（2026-10-30 MCP server 权限轮）

> 依据：设计 `docs/superpowers/specs/2026-10-30-mcp-server-permission-design.md`；契约 `docs/superpowers/contracts/2026-10-30-mcp-server-permission-api.md`（两端点）+ `2026-10-29-agent-platform-api.md` §3/§9（运行面修订，已由架构侧定稿）。
> 拍板口径一句话：server 级新增「角色+部门+用户」三主体授权（默认拒绝 + 持 ai:mcp:list 豁免），仅运行面强制、静默缩窄；工具级 required_perms 门与 Java 侧零改动。
> 后端/前端两章独立可并行。**pytest 必须先 `cd cloud-base-ai` 再跑**（仓库根跑收集报错且管道吞码假绿）。

## 一、后端章（cloud-base-ai，Python）

### B1 DDL：增量脚本 + 基线同步

**文件清单**：
- 新增 `cloud-base/scripts/sql/2026-10-30-mcp-server-permission.sql`（表 DDL + 初始授权行 + 前置断言注释，内容照抄设计文档 §1.1；幂等口径沿既有脚本：表存在则跳过、种子重放撞 uk 不视为错误）
- 修改 `cloud-base/scripts/sql/cloud_ai.sql`（ai_mcp_server 段之后同步同表定义与种子行——环境重建走基线）

**验收标准**：脚本对既有库执行成功且可重复执行不报错；基线与增量表定义一致；每列有 COMMENT；索引 uk_server_subject / idx_subject 落地（`SHOW INDEX FROM ai_mcp_server_grant`）。

### B2 实体 + DAO（TDD：先写 dao 测试见红）

**文件清单**：
- 修改 `cloud-base-ai/src/cloud_base_ai/mcp_server/entities.py`（新增 `AiMcpServerGrant`：镜像 AiKbGrant 形态——纯关系表、SubjectTypeEnum{ROLE=0,DEPT=1,USER=2} 本域自定义、BigInteger 主键 sqlite 变体 Integer、create_by/create_time）
- 修改 `cloud-base-ai/src/cloud_base_ai/mcp_server/dao.py`（新增三函数：`list_server_grants(session, server_id)` 回显按 subject_type/subject_id 升序；`save_server_grants(session, server_id, grants)` 全删全插+create_by 取 current_account，镜像 rag/dao.py 的 save_kb_grants；`list_authorized_server_ids(session, user_id, role_ids, dept_ids) -> set[int]` 三分支 OR+distinct，镜像 list_visible_kb_ids，空列表分支不拼接、user_id None 跳过用户分支）
- 修改 `cloud-base-ai/tests/test_mcp_server_dao.py`（扩用例）

**TDD 步骤**：先写测试（三分支命中/多主体同 server distinct/空身份空集/全删全插/create_by 落值/uk 撞重上抛）→ 跑红 → 实现实体与 DAO → 跑绿。

**验收标准**：`cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run --project . pytest tests/test_mcp_server_dao.py -q` 全绿；新实体通过架构守护测试（列 comment 等）。

### B3 管理面：schemas + service + router（TDD：先写 service/router 测试见红）

**文件清单**：
- 修改 `cloud-base-ai/src/cloud_base_ai/mcp_server/schemas.py`（`ServerPermsVo` / `ServerPermsSaveReq`——字段与约束逐项同构 rag/schemas.py 的 KbPermsVo/KbPermsSaveReq：strict int ge=1、每数组 max_length=100、default_factory=list）
- 修改 `cloud-base-ai/src/cloud_base_ai/mcp_server/manage_service.py`（`get_server_perms(server_id)`：find_server_by_id None → 5021；`save_server_perms(server_id, req)`：5021 前置 + 保序去重组装 + dao 保存，@transactional）
- 修改 `cloud-base-ai/src/cloud_base_ai/mcp_server/router.py`（mcp_server_update_router 挂两端点：GET/PUT `/{server_id}/perms`，路径参数非整数交既有 1001 处理；两行式返回）
- 修改 `cloud-base-ai/tests/test_mcp_server_manage_service.py`、`tests/test_mcp_server_router.py`（扩用例）

**TDD 步骤**：service 测试（5021/去重/全空清空/事务回滚）+ router 测试（403 无 ai:mcp:update / 1001 数字串与越界 / 5021 / 正常往返）先红后绿。

**验收标准**：两测试文件全绿；两端点本地直连冒烟（手工带 X-User-Perms: ai:mcp:update 头）GET/PUT 往返一致；与契约 §2/§3 逐字段对齐（字段名、错误码、语义）。

### B4 运行面：豁免常量 + server 门 + 缓存键修复（TDD：先写 run_service/runtime 测试见红）

**文件清单**：
- 修改 `cloud-base-ai/src/cloud_base_ai/security.py`（新增 `MCP_SERVER_EXEMPT_PERMS = frozenset({"ai:mcp:list"})` + `is_mcp_server_exempt(identity)`——与既有 MGMT_PERMS/is_mgmt_exemption 同文件同形态，注释写明「豁免只免 server 授权门」）
- 修改 `cloud-base-ai/src/cloud_base_ai/agent/run_service.py`（`_load_visible_tools` 内、`visible_tools` 调用前插入 `_filter_by_server_grant(pairs, identity)`——代码照设计文档 §2.1；import mcp_server 域 dao）
- 修改 `cloud-base-ai/src/cloud_base_ai/agent/runtime.py`（`_agent_cache` 键改 `(agent_id, config_version, tuple(工具id升序))`；get_agent 组键/查键/写键三处同步；docstring 更新说明按用户工具面隔离；`_AGENT_CACHE_LIMIT` 维持 32；reset_cache 保留不动）
- 修改 `cloud-base-ai/tests/test_agent_run_service.py`、`tests/test_agent_runtime.py`（扩用例）

**TDD 步骤**：先写测试见红——run_service 四态（授权命中注入/默认拒绝剔除/豁免短路不触 DAO——用 mock/替身断言未调用/授权了 server 但工具 required_perms 不满足仍剔除），全部静默不抛错；runtime 缓存隔离（同 agent 同版本两个不同工具面互不串面、同面命中复用、授权变化后新键重组装）→ 实现至绿。

**验收标准**：两测试文件全绿；`_load_visible_tools` 的管理面共用路径不受影响（test_agent_manage_service 既有用例不红——授权门在 run_service 调用侧，非 DAO 内）。

### B5 后端收口

**验收标准**：`cd cloud-base-ai && uv run --project . pytest -q` 全量绿（含架构守护）；增量 SQL 落库后服务启动正常；无新增 TODO。

## 二、前端章（cloud-web，Vue3+TS）

### F1 类型 + API 封装

**文件清单**：
- 修改 `cloud-web/src/types/api.ts`（`McpServerPermsVo` / `McpServerPermsSavePayload`——镜像既有 KbPermsVo/KbPermsSavePayload（同文件 ~1167/1181 行），字段 role_ids/dept_ids/user_ids: number[]）
- 修改 `cloud-web/src/api/mcp.ts`（`getMcpServerPerms(id)` GET `/ai/mcp/server/${id}/perms`；`saveMcpServerPerms(id, payload)` PUT——注释引契约 §2/§3，错误分支说明 403/1001/5021 拦截器统一 toast）

**验收标准**：`npm run build`（cloud-web）类型检查通过；两函数路径/方法/入参出参与契约逐字段一致。

### F2 授权弹窗组件（KbPermsDialog 全套同构移植）

**文件清单**：
- 新增 `cloud-web/src/views/mcp/manage/components/ServerPermsDialog.vue`

**实现要点**（全部沿 `cloud-web/src/views/rag/kb/components/KbPermsDialog.vue` 既有模式，改域不改形）：
- Props 由 kb 行换 `McpServerSummaryVo`；标题「授权（服务名）」；三块选择器 el-divider 分段平铺：角色/用户 = el-select multiple filterable（数据源 `listSubjectOptions`，api/dataPerm）；部门 = el-tree show-checkbox **check-strictly**（数据源 `treeDept`，api/dept——勾选即授权整棵子树的语义在服务端求值侧，前端只存被勾选节点）
- **数据源 403 容忍**（契约 §2 已知依赖边界）：只持 ai:mcp:* 的账号拉选项会 403——拦截器 toast 后弹窗保持可开可关（沿 KbPermsDialog catch 形态，不额外特判）
- id 换轨：数据源 string ↔ 授权端点 number（回显 String(id) 进勾选态、提交 Number(id)，Set 去重）；已失效主体「id:N（已失效）」占位（select 禁选占位项 / 部门 closable tag），默认随集保留
- 每数组上限 100 前端友好提示（后端 1001 权威）；提示文案改域：「被授权者（任一途径命中）可在其运行的智能体中使用该服务名下已发布工具（仍需逐工具通过工具权限门）；未授权默认不可用，持 MCP 管理查询权限者豁免。每类主体上限 100 个。」
- 失败（403/1001/5021）拦截器统一 toast、弹窗不关可重试；组件按需引入（ElementPlusResolver），无全量注册

**验收标准**：组件与 KbPermsDialog 同构性走查通过；浏览器自动化验证（frontend-page 规范）——打开回显三块、勾选/搜索、失效占位呈现与移除、保存 payload 断言（number[]、去重）、100 上限提示、保存成功 toast+关闭。

### F3 管理页接线

**文件清单**：
- 修改 `cloud-web/src/views/mcp/manage/index.vue`（操作列新增「授权」按钮 `v-perms="'ai:mcp:update'"`（列内既有「编辑」按钮同款位置风格）+ ServerPermsDialog 挂载与状态（permsVisible/permsServer），成功后无需刷新列表——授权不改变列表行数据）

**验收标准**：浏览器自动化——列表行点授权开弹窗、取消关闭、保存成功弹窗关闭且列表不刷新；无 ai:mcp:update 权限的账号看不到按钮（v-perms 防御）。

### F4 e2e 场景（route mock，独立入口不进聚合）

**文件清单**：
- 新增 `cloud-e2e/run-mcp-perm-e2e.mjs`（沿 `run-ai-kb-perm-e2e.mjs` / `run-mcp-e2e.mjs` 既有形态：route mock + 断言宽松语义）
- 修改 `cloud-e2e/package.json`（scripts 增 `"e2e:mcpperm": "node run-mcp-perm-e2e.mjs"`——不进 e2e 聚合链，沿 AI 域场景独立入口先例）

**场景设计**（对应契约 §6）：
- MP1 授权面板生命周期：登录进 MCP 管理页 → 行操作开授权弹窗 → mock GET perms 回显三块勾选态正确（含 number→string 换轨）
- MP2 保存断言：改选角色+部门+用户 → 保存 → 断言 PUT 请求体为三组 number 数组且去重
- MP3 失效主体占位：mock 回显含选项外 id → 占位呈现、直接保存保留该 id
- MP4 错误分支：mock PUT 返回 5021 → toast 呈现且弹窗不关
- 运行面三态（授权命中注入/默认拒绝剔除/管理豁免短路）**不进 e2e**（route mock 无真实求值、无 UI 面呈现按用户工具面）——由后端 B4 测试矩阵覆盖，此分工已在契约 §6 写明，此处不重复造假验证。

**验收标准**：`cd cloud-e2e && npm run e2e:mcpperm` 有头跑通全场景；**退出码假绿陷阱**——只认输出无 `[FAIL]`，不认 exit 0；聚合 `npm run e2e` 行为不变（零同步零回归）。

## 三、移交后续阶段的备忘

1. **运营欠账（上线必做）**：业务用户对 SYSTEM 及外部 server 的首次手工授权——默认拒绝生效后未授权用户 agent 工具面缩窄，管理员需在 MCP 管理页一次性补授（用户拍板已接受）。
2. AI 段错误码下一可用 **5030**（本轮零新增，5021/1001/403 复用）。
3. compiled agent 缓存上限维持 32（键含工具面指纹后组合数增多，重组装为零网络毫秒级操作；若后续 profiling 显示高频重组装可上调常量，无正确性风险）。
4. 工具级「按用户/角色授权具体工具」仍只能通过平台权限标识（required_perms）间接表达——本轮明确不做；若未来需求出现，授权表可扩资源类型列复用三主体模型，勿另起炉灶。
5. 主体选项端点（subject-options / dept/tree）的 additive 放宽沿 2026-10-18 含 ai:kb:update 的先例复用，未为 ai:mcp:update 单独扩——**只持 ai:mcp:* 的角色打开授权面板时选项加载 403**（过渡口径：发放 system:dataPerm:list 或 ai:kb:update 任一）；建议后续轮把 `ai:mcp:update` 加入该端点 additive 放宽（一行注解改动，先例齐备），随下一 Java 侧需求捎带。

## 四、给两端 agent 的任务清单

**backend-agent**（cloud-base-ai + scripts/sql，按序执行）：
1. B1 落增量脚本与 cloud_ai.sql 基线（表+初始授权行，设计文档 §1.1 原文照抄）
2. B2 实体 AiMcpServerGrant + DAO 三函数（TDD：tests/test_mcp_server_dao.py 先红后绿）
3. B3 ServerPermsVo/SaveReq + get/save_server_perms + 两端点挂 mcp_server_update_router（TDD：manage_service/router 测试先红后绿；逐字段对照契约 §2/§3）
4. B4 security.py 豁免常量 + run_service `_filter_by_server_grant` 插门 + runtime 缓存键修复（TDD：run_service 四态/缓存隔离测试先红后绿；代码照设计文档 §2.1/§2.2）
5. B5 全量 pytest 绿（cd cloud-base-ai 再跑）+ 落库冒烟
红线：dao 不提交事务；service except 必记日志；实体列必带 comment；错误码零新增；Java 侧零改动。

**frontend-agent**（cloud-web + cloud-e2e，可与后端并行；接口未就绪前按契约 mock 联调）：
1. F1 types/api.ts 两类型 + api/mcp.ts 两函数
2. F2 ServerPermsDialog.vue（KbPermsDialog.vue 全套同构移植，改域不改形；提示文案按计划 F2）
3. F3 mcp/manage/index.vue 授权按钮 + 弹窗接线（v-perms ai:mcp:update）
4. F4 浏览器自动化 UI 验证（frontend-page 规范）+ run-mcp-perm-e2e.mjs 四场景 + package.json e2e:mcpperm（不进聚合）
红线：组件按需引入（禁全量 ElementPlus）；请求一律走 utils/request 封装；e2e 只认无 [FAIL] 不认 exit 0；聚合 e2e 零回归。
