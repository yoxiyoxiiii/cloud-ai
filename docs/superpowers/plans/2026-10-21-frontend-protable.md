# 通用表格组件 ProTable 封装实施计划（2026-10-21，纯前端 B 级）

> 需求：封装 table 组件，含 ①可勾选 ②双击查看详情（传事件即可）③分页（10/20/50）。
> 轨道：纯前端，零后端契约变更。消费页选 **dict 字典类型列表**（有专属 e2e，回归可证不破）。
> 设计取向：**透传式包装**（列仍由页面写 `el-table-column`，组件只收编表格壳+选择列+分页条），
> 不做配置驱动列——保留页面既有列写法与 DefaultRow 收窄惯例，学习成本为零（已知取舍
> 「CRUD 弹窗不做通用抽象，第二个出现再提」同源：先薄后厚）。

## 组件 API（定形，实现不得偏离）

```vue
<ProTable
  :rows="typeRows"
  :loading="typeLoading"
  :total="typeTotal"
  v-model:page-num="typeQuery.pageNum"
  v-model:page-size="typeQuery.pageSize"
  selectable
  row-key="id"
  @selection-change="onSel"
  @row-dblclick="row => openDataDialog(typeRowOf(row))"
>
  <el-table-column ... />（原样透传，默认插槽）
</ProTable>
```

- **Props**：`rows: unknown[]`（el-table data；页面 Vo 数组直接可传）、`loading?: boolean`
  （v-loading，dict 页已有该指令用法先例）、`total: number | string`（**内部 `Number()` 归一**
  ——后端 total 是 Long→String，收进组件消灭每个页面的手工转换）、`pageNum/pageSize`（v-model
  双向）、`pageSizes?: number[]` 默认 `[10, 20, 50]`、`selectable?: boolean`、`rowKey?: string`。
- **Emits**：`update:pageNum` / `update:pageSize` / `page-change`（翻页或改 size 后统一触发，
  页面据此 load；**size 变更时先重置 pageNum=1** 再触发）、`selection-change(rows: unknown[])`、
  `row-dblclick(row: unknown)`（只传 row，需求原文「传递事件即可」）。
- **Expose**：`clearSelection()`（删除/刷新后清勾选；透传 el-table 同名方法）。
- **透传**：`defineOptions({ inheritAttrs: false })` + el-table 上 `v-bind="$attrs"`——border/
  stripe 等未列举的 el-table props 消费方随时可用，不僵化 API。
- **分页条**：`layout="total, sizes, prev, pager, next"`，右对齐（沿 dict 现样式 margin-top 16）。
- 选择列：`selectable` 时在默认插槽列**之前**注入 `<el-table-column type="selection" />`，
  `@selection-change` 上抛。
- 类型纪律：事件 payload 一律 `unknown`，页面沿用集中收窄函数（`typeRowOf` 模式）断言——
  组件内禁 any/裸 as（TS strict）。

## Task 1：ProTable 组件实现

**文件**：新建 `cloud-web/src/components/ProTable.vue`（`src/components` 是 unplugin-vue-components
默认扫描目录——**免 import 自动注册**，`components.d.ts` 构建时生成；这是通用组件目录的
第一个成员，文件头注释写明该目录约定）。

**验收**：`cd cloud-web && npm run build` **连续两次**全绿（首次生成 components.d.ts，二次起
类型更严）；模板中 `<ProTable>` 无需 import 即通过类型检查。

## Task 2：dict 页接入（活体消费证明）

**文件**：改 `cloud-web/src/views/system/dict/index.vue`。

**要点**：
- 类型表 `el-table + el-pagination` 整段替换为 `<ProTable>`：列定义原样搬进默认插槽；
  `row-key="id"` 保留（E3 渲染竞态注释随迁）；`v-loading` 改走 `loading` prop；
- 分页改 v-model 双向 + `@page-change="() => loadTypePage()"`；`loadTypePage` 里
  `Number(page.total)` 可删（组件已归一）；
- **双击行 = 打开该类型的字典项弹框**（`@row-dblclick` → `openDataDialog(typeRowOf(row))`——
  复用既有 DictDataDialog，行为自然且 e2e 可断言）；
- 勾选：`selection-change` 落本地 `selectedRows`；删除成功/翻页后调 `tableRef.value?.clearSelection()`
  （ref 拿 expose）；工具栏暂不消费选中集（YAGNI，仅保证链路通）；
- 分页注释更新：「pageSize 恒 10、省 sizes（设计 §5）」被组件标准取代——改写为
  「分页收编进 ProTable，10/20/50 可切（2026-10-21 组件化取代 dict 轮恒 10 口径）」。

**验收**：build 两次全绿；页面手测（dev）勾选/双击/切 size 均生效。

## Task 3：e2e 场景扩展（run-dict-e2e.mjs）

**文件**：改 `cloud-e2e/run-dict-e2e.mjs`（黑盒纪律：只经 URL/选择器，禁 import 前端内部代码；
EP 弹窗关闭是 display:none——等待用 `waitFor({ state: 'hidden' })`）。

**新增断言**（挂在既有 dict 场景尾部，复用其 login/造数 harness）：
1. 选择列渲染：首行 checkbox 点击后 checked；表头全选 → 当页行全 checked；
2. 双击首行 → DictDataDialog 弹框可见（标题含该类型名）→ 关闭后 hidden；
3. sizes 下拉切换 20 → 分页条「X / page」文案变化且发出 pageSize=20 请求（数据不足时先经
   浏览器 fetch 真实 `/api` 链路造 `e2e_` 前缀类型——Git Bash curl 中文 GBK 坑规避）；
4. 既有断言全保持（含翻页、新增/编辑/删除流）。

**测试数据纪律**：`e2e` 前缀+时间戳，绝不改 admin/内置字典类型，结束清理，报告残留。

## Task 4：联通 + 全量回归

**前置栈**：system(9202)→sso(9201)→bpmn(9203)→gateway(18080)（java -jar 绝对路径后台起，
agent 自管）；**5173 先探活**——在跑（用户 dev server）则复用**且绝不 kill**，没跑则自起自停。

1. 联通：`curl http://localhost:5173/api/system/demo/ping` 返回后端 R；
2. 全量：`cd cloud-e2e && npm run e2e`（有头）——聚合成员 user/role/menu/scaffold/nav/dict/bpmn，
   **不含 AI 栈**（ai-kb 是独立脚本，不在本轮回归面）；
3. 判定**只认** `grep -c '[FAIL]'` == 0（harness exit code 不可信）。

## Task 5：视觉核对 + 收口

- 截图 ≥5 张：dict 列表全貌（含选择列+sizes 分页）/勾选态/全选态/双击弹框/翻页态——
  `cloud-e2e/artifacts/` → Read 得上传 URL → analyze_image 核对（布局/组件/数据预期）；
  **核对完截图即删**（artifacts 不进 git）；
- 验收完立即 kill 本轮所起服务（netstat 找 PID + taskkill；**5173 若是用户的绝不动**）；
- 汇报：文件清单/两次 build 结果/e2e 各场景 PASS 计数与 grep 判定/视觉核对结论/起停服务记录。

## 红线（实现 agent 逐条遵守）

- 按需引入红线：禁 `app.use(ElementPlus)`、禁全量样式 import——组件内 el-* 全靠模板自动解析；
- TS strict：禁 any/裸 as（组件内）；事件 payload unknown + 页面集中收窄；
- 不动 cloud-base、不动其他页面、不动 viewRegistry/路由；
- 选择器改版与页面改造**同一 commit 纪律**（本轮由主控统一提交，agent 零 git 操作）。

## 移交备忘

- 组件 v1 刻意不含：列配置化、工具栏插槽、排序/筛选、多选批量操作栏、jumper——第一个
  消费页之外的形态等第二个消费页出现再议（沿「第二个出现再抽象」取舍）；
- 其余列表页（user/role/menu/mqDlq…）迁移到 ProTable 属后续独立轮，一次一页配 e2e。
