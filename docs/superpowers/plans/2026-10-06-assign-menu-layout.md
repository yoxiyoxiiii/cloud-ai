# 权限分配弹窗布局优化（轻量计划 · B 级）

- 日期：2026-10-06
- 需求原文：「把权限分配界面进行重新优化，比如：用户管理和用户修改 直接用水平排列即可，弹框可以调大」
- 轨道：纯前端 **B 级**（单组件 UI 重构，无契约变更/无新接口/不涉其他页面）——主控直带 frontend-agent
- 涉及文件：`cloud-web/src/views/system/role/components/AssignMenuDialog.vue`（重构）、`cloud-e2e/run-role-e2e.mjs`（R5/R5a 选择器适配）
- 契约基线：§4.6/§4.7 语义**不变**（全量覆盖提交 / 回显 id 数组）

## 布局设计

替换 el-tree 为自定义分组布局（数据源不变，仍是 `menuTree()` 的 MenuTreeNode 树）：

- **M 目录 = 组头**：三态 checkbox（el-checkbox 的 `indeterminate`）+ 目录名，加视觉分组（底色/分隔线）
- **C 菜单 = 行**：行首 C 三态 checkbox + 菜单名，同行右侧**横排其 F 按钮 checkbox**（flex wrap，可换行）
- 弹窗 `width` 560 → **800px**；主体 max-height 360 滚动保留；F checkbox 间 gap 16px
- CSS class 约定（e2e 选择器锚点）：组 `.perm-group`、组头 `.perm-group-header`、C 行 `.perm-menu-row`、F 项 `.perm-func`；C 行的 checkbox 与 F 的 checkbox 均原生 input，三态用 `input.checked` 与 `input.indeterminate` DOM 属性断言

## 勾选语义等价规则（⚠️ 本任务核心约束——逐字实现，破坏即契约回归）

原 el-tree 方案的设计 D2 语义（见 `specs/2026-10-06-role-page-design.md`）必须逐位等价保留：

### 回显（bound = listRoleMenuIds 结果；非叶节点勾选态纯由子孙推导，bound 中的父 id 不直接设置勾选）

- F：`checked = bound 含其 id`
- C 无 F 子（如"在线用户"若有此类）：`checked = bound 含其 id`
- C 有 F 子：`checked = 其全部 F checked`；`indeterminate = 部分 F checked`
- M：`checked = 其全部 C 均 checked`；`indeterminate = 部分 C checked 或 indeterminate`

### 交互联动

- 点 M：全选/全清其下所有 C 与 F（checked 翻转时子级全跟随；取消时全清）
- 点 C：全选/全清其 F（无 F 子则仅自身）
- 点 F：仅自身翻转；C/M 三态即时重算

### 提交（与 el-tree `getCheckedKeys ∪ getHalfCheckedKeys` 逐位等价）

- `include(F) = F.checked`
- `include(C 无 F 子) = C.checked`
- `include(C 有 F 子) = 任一其 F included`
- `include(M) = 任一其 C included`
- 等价性验证锚点：勾"用户新增(111)+用户删除(113)"→ 提交体 menuIds 必含 `["111","113","11","10"]`（2 叶子 + C11 + M10）——与旧 R5 断言一致

实现建议：checked 状态用 `Set<string>`（F 与叶子 C 的选中集）作唯一数据源，M/C 的三态与提交集合全部由它推导（computed/纯函数），避免多份状态漂移。

## 任务分解

### T1 组件重构（AssignMenuDialog.vue）

- 数据流不变：打开时 `Promise.all([menuTree(), listRoleMenuIds(role.id)])`；loading 包裹；保存 `assignRoleMenus({roleId, menuIds})` → toast"分配成功" → emit success + 关闭；catch 留空；saving 防重
- 按布局设计重写 template 与 style；`nodeOf` 断言模式延续（插槽不再有，普通遍历可直接用 MenuTreeNode 类型——模板内 v-for 变量本身有类型）
- 空树 el-empty 保留

### T2 e2e 适配（run-role-e2e.mjs 的 R5/R5a）

- 选择器从 el-tree DOM（`.el-tree-node`、aria-checked）改为 class 约定（`.perm-group`/`.perm-menu-row`/`.perm-func`）
- **R5 的提交体断言不变**（menuIds 含 4 id 的等价性验证——语义等价的自动化证明）
- R5a（admin 回显）改断言新 DOM 三态：相关 M/C 的 input.indeterminate / checked
- 其余场景（R0-R4、R6、CLEANUP、S 全量）不动

### T3 验收

- 连续两次 `npm run build` 全绿
- `npm run e2e` 有头全量（S 用户回归 + R 角色场景）全 PASS
- 视觉核对 ≥4 张：新布局整体 / 半选态（勾部分 F 时 C 与 M 三态）/ admin 回显态 / F 换行情况；结论文字记录，截图验收后删
- 测试数据纪律照旧（e2e 前缀、admin 只读、清理核验）

## 红线

- 不动 api 层与契约语义；不动其他组件/页面
- 不执行 git 操作（主控在用户确认后统一提交，功能与 e2e 脚本同 commit）
- 契约与实测不符 → 停下回报主控

## 移交备忘

- 若后续菜单层级超过 M→C→F 三层（种子现状），分组布局需按 type 而非层级泛化——届时重新设计
- el-tree 移除后 components.d.ts 可能不再含 ElTree（build 产物变小属预期）

---

## 修订 R1（同日）：F 区与 C 菜单左对齐缺陷修复

- 需求原文：「功能菜单展示区前面做一个 tab 空格，现在用户管理-用户新增完全左对齐，不好看」
- **实测根因**（Playwright 盒子坐标取证）：`.perm-func-list` 为 `flex: 1 1 auto`，basis=auto 取内容宽（5 个 F ≈1000px > 行内剩余 721px），`.perm-menu-row` 的 flex-wrap 将**整块 F 区**换行到 C 的下一行，且从行容器左边缘起排——F 按钮与 C checkbox 完全左对齐（x 同为 151）。原布局设计"同行右侧横排"未成立（上轮视觉核对只验了三态，漏了几何排列）。
- **修复**：`flex: 1 1 auto` → `flex: 1 1 0`（或等价 `flex-basis: 0`）。F 区从 C 列（112px）右侧起排并在区内自行换行；换行行与首行统一对齐在 C 列之后，形成"菜单名列 + 按钮区"的水平层次——即需求要的"tab 空格"效果。
- **一致性要求**：所有 C 行统一此形态（含 F 少的"在线用户"行）；C 列定宽对齐保留。
- **e2e 影响**：R5/R5a 无几何断言、选择器不变，预期全绿原样通过；跑全量回归确认。
- **验收补充**：截图需覆盖"F 区起排位置在 C 列右侧、换行行对齐一致"（DOM 断言：F 首 checkbox 的 x 坐标 > C checkbox 的 x 坐标 + C 列宽）。
- 视觉核对教训入账：布局类改动除三态外必须核对**几何排列**（盒子坐标断言），防止本轮漏检重现。

## 修订 R2（同日）：M→C 层级视觉引导

- 需求原文：「最后一级平铺，其他还是需要有层级的视觉体现，可以用虚线体现或者其他比较美观的」
- 现状问题：R1 后 M 组头与 C 菜单行同在组块左缘（x=151），M→C 层级仅靠组头加粗/底色区分，缩进层次缺失。
- **方案**：C 行区整体缩进于 M 组头之下，左缘加竖向虚线引导线（树形引导语义）：
  - 模板：`.perm-group` 内 C 行的 v-for 外包一层 `.perm-group-body`
  - CSS：`.perm-group-body { margin-left: 9px; padding-left: 15px; border-left: 1px dashed var(--el-border-color); }`（具体像素可在实现时微调，保持视觉平衡）
  - 不动：M 组头样式（底色/加粗/下边线）、C 行间横向虚线分隔、F 区相对 C 列偏移（R1 的 flex: 1 1 0 与 112px 列宽）、勾选语义与数据流
- **几何验收**：C 行 checkbox x > M 组头 checkbox x（缩进生效，差约 24px）；F 首 checkbox x = C x + 128（R1 不回归）。
- **e2e**：`.perm-menu-row` 等选择器不变（仅嵌套层级+1），R5/R5a 预期原样全绿；全量回归确认。
- 视觉核对：竖虚线贯穿整组 C 行、缩进层次清晰、与横向虚线分隔不显杂乱。

## 修订 R3（同日）：M→C 虚线肘形连接线

- 需求原文：「菜单（1级到二级，三级...）需要添加虚线链接到一起，操作按钮保持平铺」
- 现状问题：R2 的竖虚线是 C 区左侧一条通线，C 行与竖线之间没有横向衔接——"父子连接"的树形语义不够直观。
- **方案**（仅 CSS，template 与 script 零改动）：每个 C 行 checkbox 左侧加一段横向虚线，从竖线接到 checkbox 左缘，形成树形 `├──` 视觉：
  - `.perm-menu-check` 增加 `position: relative`
  - 新增 `.perm-menu-check::before { content: ''; position: absolute; right: 100%; top: 50%; width: 15px; border-top: 1px dashed var(--el-border-color); }`
  - width 与 `.perm-group-body` 的 `padding-left`（15px）一致，保证连接线左端恰落在竖线上；`top: 50%` 挂在 checkbox 元素上，垂直居中自动对齐（行内 F 换行不影响的稳健做法）
- **不动**：F 按钮区不加连接线（保持平铺）；M 组头样式、R1 的 `flex: 1 1 0`、R2 的竖虚线与缩进、勾选语义与数据流
- **层级泛化**：肘形连接线模式天然可嵌套（更深菜单层级复用同样样式即可）；但更深层级（C 下还有 C）当前模板本就不渲染——按"移交备忘"留待真实数据出现时重新设计渲染结构，本轮不引入泛化组件
- **几何验收**：连接线存在（`getComputedStyle(el, '::before')` 证实已应用）且 y=该行 checkbox 垂直中心 ±2px、横向跨距=竖线到 checkbox 左缘；R1/R2 不回归（F 首 x=C+128、C 缩进 25px）
- **e2e**：伪元素不进 DOM，既有选择器与断言全部不受影响，预期全绿原样通过；全量回归确认（不改 e2e 脚本）
