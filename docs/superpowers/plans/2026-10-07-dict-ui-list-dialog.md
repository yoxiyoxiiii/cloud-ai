# 字典管理界面优化（类型全宽列表 + 字典项弹框）轻量计划

- 日期：2026-10-07
- 轨道：纯前端（B 级）——主控直写轻量 plan，无新契约（dict 8 端点零变化，契约 `2026-10-07-dict-api.md` / `2026-10-07-translation-api.md` 原样消费）
- 需求原文：「前端字典管理界面优化：字典类型可以是列表，字典项可以直接是弹框即可，字典项不会太多，现在的这样界面展示区域大拥挤」
- 现状痛点：左 380px 窄类型表 + 右侧 9 列项表常驻（label/value/sort/status/审计 4 列/操作）——展示区域拥挤
- superpowers：本 plan 即 writing-plans 产物（B 级轻量），实现走 executing-plans

## 设计决策（D 系列）

### D1 布局：类型单卡全宽列表

- `dict/index.vue` 移除主从双卡（`.type-pane` 380px + `.data-pane`），改为**单卡全宽类型表**（`shadow="never"`，卡 class 保留 `type-pane`——e2e 选择器兼容面）
- 类型表列不变（字典名称/字典键/状态/操作），操作列扩为**三按钮：字典项 / 编辑 / 删除**（width 相应放宽 ~160）
- 类型分页不变（pageSize 10，`total, prev, pager, next`）

### D2 字典项管理弹框（新组件 `components/DictDataDialog.vue`）

- 触发：类型行"字典项"按钮（`v-perms="'system:dict:list'"`——查看/管理入口挂 list 权限，弹框内增删改按钮各挂 add/edit/remove 不变）
- 结构：`el-dialog`（width ~860px，title `字典项：{dictName}（{dictKey}）`——沿用原右栏标题格式，e2e 断言可平移）：
  - 顶部"新增字典项"按钮（`v-perms system:dict:add`）
  - 项表格 **5 列精简**：标签/值/排序/状态/操作（编辑/删除）——**审计 4 列（创建人/创建时间/更新人/更新时间）不再展示**（弹框场景聚焦管理本身；契约 §3 VO 字段不变，仅 UI 不消费）
  - 分页（pageSize 10 + `total, prev, pager, next`；契约只有分页端点，弹框内保留分页控件最稳）
  - 空态 `el-empty` "暂无字典项"
- 增/编辑**复用既有 `DictDataFormDialog`**（`append-to-body` 二层弹框——EP 标准模式；该组件 props mode/dictData/typeId 已满足，零改动）；删除沿用 ElMessageBox 确认（文案含标签）
- 数据加载：弹框打开（watch visible + dictType 变化）→ `pageDictData({ typeId })` 第 1 页；保存/删除成功后原地刷新当前页
- 组件职责内聚：项分页状态/加载/增删改入口全在弹框组件内，父页只传 `dictType` 与 `v-model`

### D3 父页简化（删掉的代码）

- `selectedType` 联动、`handleTypeCurrentChange`、右栏 `loadDataPage`/`dataRows`/`dataTotal`/`dataQuery`、`openDataAdd`/`openDataEdit`/`handleDataDelete`、`highlight-current-row`/`row-key` 联动注释——全部移除（项逻辑迁入弹框）
- `DictDataFormDialog` 从父页挂载移到 `DictDataDialog` 内
- 类型增删改逻辑（loadTypePage/openTypeAdd/openTypeEdit/handleTypeSaved/handleTypeDelete）不变；类型删除后无需清右栏（3011 拦截逻辑不变，"删除的若是选中类型"注释语义消失）
- keep-alive：`defineOptions({ name: 'SystemDict' })` 不变，路由/菜单零改动

### D4 e2e 适配（run-dict-e2e.mjs，结构性改写但断言语义保留）

- 选择器：`.type-pane` 保留可平移；`.data-pane` 全部消失 → 项操作改到弹框作用域（`.el-dialog` 定位）；`selectTypeRow(row)` → `openDataDialog(row)`（点"字典项"按钮 + 等 `/api/system/dict/data/page` 响应）
- **D1 改造**：右栏空态三件套断言（纯标题/请选择文案/disabled）**删除**（无右栏语义）；改为——打开种子行"字典项"弹框：断言标题 `字典项：用户状态（user_status）`、表头 5 列精确序 `标签,值,排序,状态,操作`、2 行种子项（正常/停用）+ 分页 `共 2 条`，关闭弹框回列表
- **D2 简化**：选中高亮 current-row / 右标题联动 / row-key 重对齐断言组**删除**（选中态语义消失）；改为——编辑类型改名后，重开"字典项"弹框断言标题跟随新名（联动价值的平移）
- **D3 改写**：项闭环全在弹框内（新增按钮/表格行/编辑删除按钮均 `.el-dialog` 作用域）；**审计 UI 断言（创建人 admin/时间格式/更新人）改为页内 fetch**（`/api/system/dict/data/page` body 断言 createBy/updateBy/createTime——契约黑盒锁定价值保留，UI 不再展示但 VO 仍返回）
- **D4 改写**：3011 流程不变；`deleteAllDataItems` 改弹框内版本（行定位 `.el-dialog .el-table__row`）；"类型删后右栏回空态"断言组删除，改为断言行消失即可
- **CLEANUP 改写**：`openDataDialog(target)` + 弹框内删净项 + 关弹框 + 删类型；后置断言（无 e2e 残留/共 1 条/种子行原样）不变
- D0/D5 零改动（D5 全程页内 fetch 不经 UI）

## 任务分解

### F1 父页重构 + 弹框组件（cloud-web）

- 文件：
  - `cloud-web/src/views/system/dict/components/DictDataDialog.vue`（新建，D2 结构）
  - `cloud-web/src/views/system/dict/index.vue`（重构，D1/D3）
- 验收：连续两次 `npm run build` 零错误；Element Plus 按需引入现状不变（新组件 EP 组件经 unplugin 自动解析，禁全量）

### E1 e2e 适配（cloud-e2e/run-dict-e2e.mjs，D4 全项）

- 验收：`node --check` 过；单跑 `npm run e2e:dict` 全 PASS；D-VERIFY 证据区零 console error/pageerror/>=400

### E2 全量回归 + 视觉核对

- `cd cloud-e2e && npm run e2e` 有头六脚本全跑全 PASS（后端栈 sso/system/网关在跑；dev 5173 agent 自管）
- 截图 ≥3 张（类型列表全宽/字典项弹框/弹框内表单二层）经 analyze_image 视觉核对——重点核对"不再拥挤"的需求达成度；结论文字记录，截图验收后删

## 红线

- dict 契约零触碰（无新端点/无字段变更；UI 减列≠契约变更）；发现契约缺口上报主控
- user_status 种子与 admin 零触碰；e2e 前缀+时间戳数据删净
- 黑盒纪律（cloud-e2e 禁 import 前端内部代码）；场景脚本与功能代码同一 commit

## 给 frontend-agent 的任务清单（可直接粘发）

按 F1 → E1 → E2 顺序执行；对齐基线 = 本 plan + 契约 `2026-10-07-dict-api.md`（§2/§3 端点原样）+ `/frontend-page` 技能。要点：

- F1：新组件 `DictDataDialog.vue`（列表弹框：标题 `字典项：{名}（{键）}`、5 列表格 标签/值/排序/状态/操作、新增按钮、分页、空态；增编辑复用 `DictDataFormDialog` append-to-body 二层，删除 ElMessageBox 确认）；父页改单卡全宽类型表（`.type-pane` 类名保留、操作列三按钮 字典项/编辑/删除、"字典项"按钮挂 `v-perms system:dict:list`）；删 selectedType 联动与右栏全部逻辑；组件名 SystemDict 不变
- E1：run-dict-e2e.mjs 按 plan D4 适配——空态断言删（改弹框断言组：标题/5 列/种子 2 行/共 2 条）、联动断言删（改弹框标题跟随新名）、项操作弹框作用域、审计断言改页内 fetch、CLEANUP 弹框内删净
- E2：两次 build 绿 → e2e:dict 单跑绿 → npm run e2e 六脚本全量回归 → 截图 ≥3 analyze_image 核对（含"不再拥挤"达成度）
- 验收后回报：文件清单/build 结果/单跑+全量结果/截图核对结论
