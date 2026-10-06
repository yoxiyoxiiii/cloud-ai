# 角色管理页技术方案（纯前端 A 级 + 契约补录）

- 日期：2026-10-06
- 需求：完成角色管理基本功能（分页列表 / 新增 / 编辑（名称·标识·状态）/ 删除（确认+解绑提示）/ 分配菜单权限（树形勾选+保存+重开回显））
- 轨道：**纯前端 A 级**（原需求以 /dev-fullstack 触发；主控侦察确认后端零改动后降轨，降轨结论由主控裁定）。菜单管理页**不在**本需求内
- API 契约：`docs/superpowers/contracts/2026-10-05-pilot-auth-user-api.md` §4.1-4.7（本次补录）+ §5.1 菜单树（本次细化）
- 实施计划：`docs/superpowers/plans/2026-10-06-role-page.md`
- 前端规范唯一来源：`/frontend-page` 技能（本方案不与技能冲突，样板 = 用户管理页三件套）

## 1. 现状盘点（后端零改动，前端纯消费）

### 1.1 后端既有端点（已逐字核对）

| 端点 | 契约节 | 用途 | 权限 |
|---|---|---|---|
| GET /system/role/page | §4.2 | 列表页数据源（含停用，id 倒序） | system:role:list |
| GET /system/role/list | §4.1 | 仅启用角色（本页**不用**，用户分配角色弹窗在用） | system:role:list |
| POST /system/role | §4.3 | 新增，返回新 id | system:role:add |
| PUT /system/role | §4.4 | 修改（roleKey **可改**，部分更新语义） | system:role:edit |
| DELETE /system/role/{id} | §4.5 | 删除（事务内解绑用户/菜单） | system:role:remove |
| PUT /system/role/menu | §4.6 | 全量分配菜单（先清后插） | system:role:assignMenu |
| GET /system/role/{id}/menus | §4.7 | 已绑菜单 id（回显） | system:role:list |
| GET /system/menu/tree | §5.1 | 菜单树（M/C/F 三级，叶子 children=[]） | system:menu:list |

权限种子已齐（sql id 12/121-124 已映射 admin），无任何 SQL/后端改动。

### 1.2 前端现状

- `src/api/role.ts` 仅 `listRoles()`（用户分配角色弹窗在用，**保留不动**）
- `src/types/api.ts` 的 `SysRoleVo` 是 4 字段子集、`MenuTreeNode` 未消费
- 路由静态表只有 system/user 与 dashboard；无角色页
- 样板齐备：用户页 `index.vue`（列表模板）+ `UserFormDialog`（表单弹窗模板）+ `AssignRoleDialog`（勾选分配弹窗模板）

## 2. 关键决策与方案权衡

### D1 列表数据源：分页接口 vs 全量接口

| 方案 | 说明 | 取舍 |
|---|---|---|
| A（推荐） | `GET /role/page` 分页 | 与用户页同构（total/翻页/Number() 模式直接复用，e2e 分页断言可复制）；**含停用角色**——列表页必须展示停用项才能编辑回启 |
| B | `GET /role/list` 全量前端不分页 | 仅返回启用角色，停用角色在列表页隐身，功能不完整；否决 |

### D2 分配菜单：el-tree 勾选树（核心决策）

| 方案 | 说明 | 取舍 |
|---|---|---|
| A（推荐） | `el-tree` + `show-checkbox` + `node-key="id"` | 原生表达 M/C/F 三级父子结构；勾选联动（父半选/全选）免费获得；EP 组件按需引入自动覆盖 |
| B | 平铺 `el-checkbox-group`（照抄 AssignRoleDialog） | 菜单 20+ 节点三层嵌套，平铺丢失层次且不可读；仅一层实体（角色）才适用；否决 |
| C | `el-cascader` / `el-tree-select` | 逐级选择/下拉语义，与"勾选任意子集"的需求不符；否决 |

**勾选语义的技术要点（回显与提交的不对称，实现时最容易踩的坑）**：

- **提交**：`getCheckedKeys()`（全选节点，含因子全选而全选的父）+ `getHalfCheckedKeys()`（半选父）合并成 menuIds 全量提交（§4.6 先清后插）。半选父也入库，保证 admin 等存量"角色绑定了目录节点"的数据自洽。
- **回显**：**只把叶子节点 id 传给 `setCheckedKeys`**。原因：EP 的 setCheckedKeys 传入父节点 id 会连带勾选其全部子孙——若存量数据绑定了父目录（admin 就是），直接回显会把整棵子树误勾。叶子过滤后，父节点勾选态（全选/半选）由 EP 依据子节点联动计算，天然正确。

```ts
/** 收集叶子节点 id 集合：回显只传叶子，父节点勾选态由 EP 联动计算 */
function collectLeafIds(nodes: MenuTreeNode[], acc: Set<string> = new Set()): Set<string> {
  for (const node of nodes) {
    if (node.children.length === 0) acc.add(node.id)
    else collectLeafIds(node.children, acc)
  }
  return acc
}

// 回显：leafIds 过滤已绑 id
const leafIds = collectLeafIds(treeData.value)
await nextTick()
treeRef.value?.setCheckedKeys(checkedIds.filter((id) => leafIds.has(id)))

// 提交：全选 + 半选父合并（契约 §4.6 全量覆盖语义）
const checked = treeRef.value?.getCheckedKeys() ?? []
const half = treeRef.value?.getHalfCheckedKeys() ?? []
const menuIds = [...checked, ...half].map(String)
```

（children 叶子为空数组 `[]` 非 null——契约 §5.1 已核实，`length === 0` 判断安全；`getCheckedKeys` 返回 TreeKey 联合类型，`map(String)` 收敛为 `string[]`。）

### D3 编辑弹窗中 roleKey 可编辑（与用户页 account 的关键差异）

| 方案 | 说明 | 取舍 |
|---|---|---|
| A（推荐） | roleKey 编辑时可输入可保存 | 契约 §4.4 后端明确支持（改 roleKey 触发唯一性校验排除自身）；需求原文"编辑（名称·标识·状态）"明确含标识；roleKey 只是业务标识，权限语义锚点是 sys_menu.perms 而非 roleKey，改动无联动风险 |
| B | 编辑锁定 roleKey 只读 | 比后端能力窄，且与需求文字不符；否决 |

对照：用户页 account 锁定是**契约**使然（PUT /user 的 DTO 不收 account）；角色页 PUT /role 收 roleKey——两页差异是后端契约差异的自然映射，设计文档显式标注防实现时误抄样板。

### D4 删除保护：前端不做特殊拦截

后端删除无内置角色保护（admin 亦可删，契约 §7.7 已如实记录并上报主控）。前端选择：

- 确认框文案强化提示（含解绑警示）：`确定删除角色 "xxx" 吗？删除后将解除该角色与用户、菜单的绑定。`
- 不发明"内置角色禁止删除"的前端拦截（超出契约的隐形规则会让 e2e 与契约漂移）；风险处置权在主控（是否后端补保护）
- e2e 纪律：绝不删 admin，测试数据 e2e 前缀

### D5 e2e 组织：抽公共 harness（技能既定动作）

/frontend-page 技能明示"共用 harness（login/findRow/waitToast 等）在第二个功能接入时抽 lib/"——角色页正是第二个功能：

| 方案 | 取舍 |
|---|---|
| A（推荐） | 抽 `cloud-e2e/lib/harness.mjs`（step/assert/shot/waitToast/waitDialogGone/waitTableIdle/login/logoutViaUi/rowCells/findRow 参数化目标页与定位列）；`run-e2e.mjs` 改引 lib（行为不变）；新增 `run-role-e2e.mjs` 独立场景；`npm run e2e` 串行跑两个脚本 | 回归范围清晰，黑盒纪律不变 |
| B | 角色场景追加进 run-e2e.mjs 单文件 | 文件超 800 行，场景边界模糊；否决 |

## 3. 页面结构设计

`/system/role`，与用户页同构（el-card 容器）：

```
┌ el-card ──────────────────────────────────────────────┐
│ header: [角色管理]                      [新增角色(prim)] │
│ ┌ el-table (v-loading) ──────────────────────────────┐ │
│ │ 角色名称 | 权限标识 | 状态(tag) | 创建人 | 创建时间   │ │
│ │          | 更新人 | 更新时间 | 操作(编辑/分配权限/删除)│ │
│ └────────────────────────────────────────────────────┘ │
│ el-pagination: total(Number()), [10,20,50], 同用户页    │
└───────────────────────────────────────────────────────┘
弹窗（页面私有，就近 components/）：
  RoleFormDialog   —— 新增/编辑（名称·标识·状态）
  AssignMenuDialog —— 分配菜单权限（树勾选）
```

- 工具栏仅"新增角色"（分页无搜索参数——契约现状，同用户页取舍，不私自加搜索框）
- 操作列 3 个 link 按钮：编辑（primary）/ 分配权限（primary）/ 删除（danger），width 200 fixed right
- 状态列 el-tag 复用用户页 STATUS_MAP 模式（0 正常 success / 1 停用 danger，未知值 fallback `info` + 原值）

## 4. 组件设计（封装契约）

### 4.1 RoleFormDialog.vue（对照 UserFormDialog）

- props：`modelValue: boolean` + `mode: 'add' | 'edit'` + `role?: SysRoleVo`（编辑回显行数据）
- emits：`update:modelValue` + `success`
- 表单字段与校验：

| 字段 | 组件 | 校验（前端约定，契约 §7.4/§7.6 模式：后端无强约束前端兜底） |
|---|---|---|
| name 角色名称 | el-input | 必填；1-30 位（DDL VARCHAR(30)） |
| roleKey 角色标识 | el-input | 必填；字母开头，字母/数字/下划线/中横线，≤30 位（pattern 为前端约定——roleKey 是系统引用锚点，脏值后患大；后端仅校验非空白） |
| status 状态 | el-radio-group | 必填；绑定常量 STATUS_NORMAL=0 / STATUS_DISABLED=1 |

- `watch(modelValue)` 打开时初始化：edit 回显 role 三字段（**全可编辑**，见 D3）；add 给默认值（status=0）；先 `clearValidate()`
- 提交：validate 通过 → `createRole`（add，提示"新增成功"）/ `updateRole`（edit，提示"保存成功"）→ `emit('success')` + 关闭；catch 留空（3003 唯一冲突由拦截器统一 toast "角色标识已存在: xxx"，弹窗不关可改后重提）；loading 防重复提交

### 4.2 AssignMenuDialog.vue（对照 AssignRoleDialog，树形版）

- props：`modelValue: boolean` + `role?: SysRoleVo`；emits：`update:modelValue` + `success`
- 标题：`分配权限（{role.name}）`
- `watch(modelValue)` 打开：`Promise.all([menuTree(), listRoleMenuIds(role.id)])` 并行拉取（契约 §5.1 / §4.7）；loading 态包住树区域
- 树：`el-tree` `node-key="id"` `show-checkbox` `default-expand-all`，`:props="{ label: 'name', children: 'children' }"`；容器 `max-height: 360px; overflow: auto`
- 节点渲染（自定义 slot）：name；type 为 'F' 的按钮节点追加 perms 灰色小字（权限标识是分配对象的核心信息）
- 回显：D2 的叶子过滤 + `setCheckedKeys`（`await nextTick()` 后设，确保树已渲染）
- 提交：`assignRoleMenus({ roleId, menuIds: checked ∪ halfChecked })`（D2）；toast"分配成功"→ `emit('success')` + 关闭；catch 留空；saving 防重
- 候选为空（树加载失败/空）：`el-empty` 占位，弹窗可关闭（同 AssignRoleDialog 模式）

## 5. api 层与类型扩展

### 5.1 types/api.ts

- `SysRoleVo` 扩为全字段（对齐契约 §6：+createBy/createTime/updateBy/updateTime，可空）
- `MenuTreeNode.type` 收敛为 `'M' | 'C' | 'F'`，注释标注"叶子 children 恒为 []"

### 5.2 api/role.ts（既有 listRoles 保留不动，追加 6 函数）

```ts
pageRole(query: RolePageQuery): Promise<PageResult<SysRoleVo>>      // §4.2
createRole(payload: CreateRolePayload): Promise<string>             // §4.3
updateRole(payload: UpdateRolePayload): Promise<null>               // §4.4
deleteRole(id: string): Promise<null>                               // §4.5
assignRoleMenus(payload: AssignMenuPayload): Promise<null>          // §4.6
listRoleMenuIds(id: string): Promise<string[]>                      // §4.7
```

interface：`RolePageQuery{pageNum,pageSize}` / `CreateRolePayload{name,roleKey,status}` / `UpdateRolePayload{id,name,roleKey,status}` / `AssignMenuPayload{roleId,menuIds:string[]}`；菜单树函数放 `api/menu.ts`（`menuTree(): Promise<MenuTreeNode[]>`，一服务一文件，role.ts 不跨实体）。

## 6. 数据流

```
列表：onMounted / 弹窗 success ─→ loadRolePage() ─→ pageRole ─→ rows + total(Number())
新增/编辑：RoleFormDialog 提交 ─→ createRole/updateRole ─→ emit success ─→ loadRolePage()
删除：ElMessageBox.confirm（文案含角色名+解绑提示）─→ deleteRole ─→ toast ─→ loadRolePage()
分配：打开 AssignMenuDialog ─→ menuTree ∥ listRoleMenuIds ─→ 叶子过滤回显
      ─→ 保存 assignRoleMenus(checked∪halfChecked) ─→ emit success ─→ loadRolePage()
      （分配不改角色行数据，success 触发刷新沿用样板模式，保持页内交互一致性）
```

## 7. 错误处理矩阵（前端策略：仅分流 200/401，其余统一 toast msg）

| 场景 | 后端 | 前端表现 |
|---|---|---|
| roleKey 重复（新增/编辑） | 200 + code 3003，msg "角色标识已存在: xxx" | 拦截器 toast；弹窗保持打开，改值可重提 |
| 编辑/删除目标已被他人删除 | 200 + code 3004 "角色不存在" | toast；刷新后行消失 |
| 无操作权限 | 200 + code 403 | toast（MVP 按钮全显的既有兜底，契约 §7.1） |
| 未认证/过期/被踢 | HTTP 401 | request.ts 清态跳登录（页面不处理） |
| name 脏数据（绕过前端校验直发） | SQL 异常兜底 | 拦截器 toast；前端 rules 必填+长度已兜住正常路径（契约 §7.6） |
| 树/回显接口失败 | - | 拦截器 toast；弹窗空态可关闭，不阻塞 |

## 8. 状态与常量映射

- `STATUS_MAP: Record<number, {label, tagType}>`（0 正常 success / 1 停用 danger）——页面内常量，同用户页
- `STATUS_NORMAL = 0` / `STATUS_DISABLED = 1`——RoleFormDialog radio 绑定常量，禁魔法数
- `rowOf(row): SysRoleVo` 收窄函数——EP 列插槽 row 固定 DefaultRow，全页唯一断言点（模板/处理器禁散落裸 as）

## 9. 与用户管理页一致性对照

| 维度 | 用户管理页 | 角色管理页（本方案） | 一致性 |
|---|---|---|---|
| 页面骨架 | el-card + table + pagination | 同 | 复制模式 |
| 数据源 | /system/user/page | /system/role/page | 同（含 Number(total)） |
| 表单弹窗 | UserFormDialog（account 编辑锁定） | RoleFormDialog（**roleKey 可编辑**，D3） | 结构同、字段规则随契约 |
| 勾选分配 | AssignRoleDialog（checkbox 平铺一层） | AssignMenuDialog（**el-tree 三级勾选**，D2） | 交互模式同（打开并行拉取/回显/全量覆盖提交），载体随实体层次升级 |
| 删除确认 | 文案含账号名 | 文案含角色名 + **解绑提示**（D4） | 模式同、文案加强 |
| e2e | run-e2e.mjs S8-S14 | run-role-e2e.mjs（harness 抽 lib 复用，D5） | 断言模式复用 |

## 10. 测试策略（黑盒 e2e，cloud-e2e/）

前置：后端三服务 + 网关 + 前端 dev 5173 已起；admin 种子账号登录；测试角色 roleKey 用 `e2e` 前缀+时间戳，**绝不删 admin 角色**。

| 场景 | 断言要点 |
|---|---|
| R1 列表加载 | 路由/菜单项出现"角色管理"；表头 8 列齐全；状态 tag；分页 total ≥1；时间格式 |
| R2 新增 | 空提交必填错误（0 请求）→ 填表 → toast"新增成功" → 行出现（按 roleKey 定位，page 按 id 倒序应在首页顶部） |
| R3 唯一冲突 | 用相同 roleKey 再新增 → 错误 toast 含"角色标识已存在" → **弹窗仍在**（EP display:none 坑反向利用：断言 visible） |
| R4 编辑 | 改名称 + 停用 → toast"保存成功" → 行内更新（tag danger）；roleKey 输入框可编辑（非 disabled） |
| R5 分配权限 | 树渲染（根"系统管理"可展开，F 节点显示 perms）→ 勾选某菜单及其部分按钮 → 保存 → 重开回显勾选一致（父呈半选/全选）→ 全不勾保存 → 重开为空 |
| R6 删除 | 确认框含角色名与"解除"提示 → 确定 → toast"删除成功" → 全表行消失 |
| 回归 | run-e2e.mjs 用户场景整段重跑全绿（harness 抽 lib 后行为不变） |

视觉核对：角色列表页 / 新增弹窗 / 唯一冲突 toast / 分配权限树（勾选+半选态）/ 删除确认框，截图 ≥5 张走 analyze_image 比对（通路见技能）。

## 11. 已知缺口与上报（不阻塞本需求）

1. **删除角色无内置保护**（admin 可删，契约 §7.7）——已上报主控裁定是否后端补防护；前端仅加强确认文案
2. 分页无搜索参数（契约现状，与用户页同缺口）
3. name/status 后端无校验（契约 §7.6）——前端表单兜底
4. 菜单树含停用菜单且出参无 status 字段（契约 §5.1）——将来菜单管理页需求时再议后端扩展
5. 按钮级权限前端全显 + 403 兜底（契约 §7.1 既有缺口，非本需求范围）
