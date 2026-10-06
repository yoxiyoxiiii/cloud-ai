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
