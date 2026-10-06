# 菜单管理页技术方案（全栈 A 级：菜单接口缺口补齐 + cloud-web 菜单管理页）

- 日期：2026-10-06
- 需求：实现菜单管理——cloud-web 菜单管理页（树表展示 / 新增 / 编辑 / 删除）+ cloud-system 菜单接口缺口补齐（tree 出参缺 status 与审计四列、update 缺 name 空校验）
- 轨道：**全栈 A 级**（契约变更：/menu/tree 出参 additive 扩展 + PUT /menu 收紧一条校验）
- API 契约：`docs/superpowers/contracts/2026-10-06-menu-management-api.md`（菜单域现行版，取代 pilot 契约 §5）
- 实施计划：`docs/superpowers/plans/2026-10-06-menu-management.md`（后端章/前端章独立可并行）
- 规范来源：CLAUDE.md「编码规范」+ `/backend-crud` 技能（后端）；`/frontend-page` 技能（前端，样板 = 用户/角色页三件套）

## 1. 现状盘点（已逐字核对）

### 1.1 后端（阶段 2 落地，本需求改动最小化）

| 端点 | 现状 | 缺口 |
|---|---|---|
| GET /system/menu/tree | `R<List<MenuTreeNode>>`，权限 system:menu:list；listAll 全量（含停用）→ MenuTreeBuilder 组树 | **MenuTreeNode 仅 7 字段**（id/parentId/name/perms/type/sort/children）——管理页需展示状态 tag 与创建/更新信息 |
| POST /system/menu | 实体接参，返回新 id；save() 已有 name 空校验 + validateParent + 审计四值 | 无（契约如实记录宽松语义） |
| PUT /system/menu | requireMenu(3006) + validateParent 环校验(3007) + 动态更新 | **缺 name 空白校验**——`name:""` 非空串会经动态 SET 落库为空白名 |
| DELETE /system/menu/{id} | 3005 存在子级 / 3006 不存在；逻辑删 + sys_role_menu 物理清理（单事务） | 无 |

- `MenuTreeBuilder.toNode` 未映射 status/审计列（`listAll` SQL 已查出全部列，仅 DTO 丢弃）——扩展只需改 DTO + toNode，**无 SQL/mapper/Controller 改动**
- 权限种子已齐：sys_menu 13（菜单管理 C，system:menu:list）+ 131/132/133（F：add/edit/remove），admin 绑定全部菜单
- ArchitectureGuardTest 已豁免 MenuTreeNode（"树节点 MenuTreeNode 不受 VO 直出限制"），DTO 扩展不触发守护红线

### 1.2 前端

- `views/system/menu/` 不存在；router 与 Sidebar 均静态表（用户管理 → 角色管理 → 工作台）
- `api/menu.ts` 仅 `menuTree()`（AssignMenuDialog 在消费，**契约扩展必须 additive 不破坏它**）
- `types/api.ts` 的 `MenuTreeNode` 为 7 字段子集
- 样板齐备：用户/角色两页（el-card + table + FormDialog 弹窗模式）；e2e harness 已抽 `cloud-e2e/lib/harness.mjs`

## 2. 关键决策与方案权衡

### D1 管理页数据源：additive 扩展 tree vs 新增扁平端点

| 方案 | 说明 | 取舍 |
|---|---|---|
| A（推荐） | `GET /menu/tree` 出参 additive 加 status + 审计四列 | 菜单量级小（RBAC 常态 <100 行）不分页；树形是菜单的天然形态，管理页与 AssignMenuDialog 共用同一数据源与同一排序语义；additive 对既有消费零破坏（JSON 多字段 TS 运行时无感、接口扩展向后兼容）；后端改动仅 DTO+toNode |
| B | 新增 `GET /menu/page` 扁平分页端点 | 菜单是树不是平表——扁平列表丢失层级还得前端重组；多一个端点两份语义要同步维护；否决 |

**结论 A**。契约以 v2 字段表取代 pilot §5.1（既有 7 字段名/类型/语义不变，新增 5 字段）。

### D2 树表呈现：el-table 树形 vs 手写缩进

| 方案 | 取舍 |
|---|---|
| A（推荐）`el-table` 原生树形：`row-key="id"` + `:tree-props="{ children: 'children' }"` + `default-expand-all` | 展开/缩进/层级引导免费获得；契约已核实叶子 children 恒为 `[]`（EP 判空即不渲染展开箭头）；`.el-table__row` 选择器与既有 e2e harness 完全兼容 |
| B 手写缩进表格 | 重复造 EP 已有能力，展开交互还得自己做；否决 |

- **类型列 tag 颜色语义**：`M 目录=primary / C 菜单=success / F 按钮=warning`，未知值 fallback `info` + 原值（同用户/角色页 STATUS_MAP 模式）
- **perms 展示**：独立"权限标识"列（M 行显示 `-`）——不塞进名称列，e2e 可按列断言
- **无分页**：全量树一屏展示（D1 结论），页脚不渲染 el-pagination

### D3 新增/编辑弹窗：type 单选联动 + 类型化父级候选（核心决策）

**字段联动规则**：

| type | 上级选择器 | perms 字段 | 说明 |
|---|---|---|---|
| M 目录 | 不显示选择器，固定展示只读"根目录"（提交 parentId="0"） | 隐藏（提交 ""） | **目录不嵌套**：AssignMenuDialog（M/C/F 三层分组布局）是 menu/tree 既有消费端，嵌套 M 会使其渲染失真（M 被当 C 行展示、其子树丢失）——红线"additive 不破坏既有消费"约束下，MVP 禁止 M 挂 M |
| C 菜单 | `el-tree-select`，候选 = 全部 M 节点（仅保留 M 分支的 M 子孙），**必选** | 选填（提示填 `system:xxx:list` 类访问权限——种子 C 节点即带 :list 标识） | C 必须挂在 M 下（前端约定；后端不校验父级类型，契约如实记录） |
| F 按钮 | `el-tree-select`，候选 = 全部 C 节点（剥掉其 F 子级），**必选** | **必填**（按钮无权限标识即无语义） | F 必须挂在 C 下（同上，前端约定） |

- **编辑时 type 锁定**（radio disabled）：改 type 会造成层级语义漂移（C 改 F 后其 F 子级变成孤儿层级）；后端不拦（动态列可改 type，契约宽松语义记录），前端锁定是最小且够用的约束
- **环防御的结构性满足**：主控要求的"编辑候选排除自身及后代"在本方案下**天然成立**——C 的候选是 M 型节点（自身是 C 不在集内）、F 的候选是 C 型节点（自身是 F 不在集内）、M 固定根级。无需额外过滤代码；后端 validateParent 的 3007 环校验继续兜底直连 API 路径
- **上级选择器形态**：`el-tree-select`（EP 按需引入自动覆盖）+ `check-strictly` + `default-expand-all`，node-key="id"
- 其余字段：名称必填 1-30（DDL VARCHAR(30)）；排序 el-input-number 0-999 默认 0；状态 radio 绑定常量 0/1
- perms 校验（前端约定兜底，后端无格式校验）：`^[a-zA-Z][a-zA-Z0-9:_-]{0,49}$`（DDL VARCHAR(50)）

### D4 后端 update() 补 name 空白校验（本需求唯一后端行为变更）

- 现状缺陷：`name:""`（非 null 空串）会经动态 SET 把菜单名落库为空白；`name:null` 是合法的部分更新语义（不更新该列）
- 修法（镜像 SysRoleManageService.update 对 roleKey 的模式）：`name != null && isBlank()` → `BusinessException("菜单名称不能为空")`（默认码 1002）
- **null 不拦**（保持部分更新语义，与 role 契约 §4.4 "roleKey 空串非 null 也拦"同一口径）；前端始终全量提交写字段，正常路径不受影响
- 对应单测：空白名拒绝（never update）/ null 名放行（verify update）两例

### D5 删除确认文案与 3005/3006 对齐

- 后端语义：3005 存在子菜单先删子级；3006 菜单不存在；删除单事务内逻辑删 + **物理删除 sys_role_menu 绑定**
- 确认框文案（对齐 role 页风格，含"解除"提示）：`确定删除菜单 "{name}" 吗？删除后将解除该菜单与角色的绑定。`
- 3005 由拦截器统一 toast（"存在子菜单，先删除子级"），行保留——用户按树自底向上删，**前端不做递归级联删除**（后端无此契约，不发明）

### D6 侧边栏/路由：本需求保持静态表

- router 静态表与 Sidebar 各追加一项 `/system/menu`（title 菜单管理，icon `Menu`——已验证 @element-plus/icons-vue 导出），顺序：用户管理 → 角色管理 → 菜单管理 → 工作台（与 sys_menu 种子 sort 一致）
- **动态菜单（消费 menu/tree 驱动侧边栏/路由）是另一个需求**——本需求不动此架构取舍（pilot 设计 §7/§8 既定）；记入移交备忘

### D7 权限快照时效（契约语义，两端与 e2e 共同遵守）

- sys_menu 的增/删/改（含新增按钮 perms、停用菜单）**不实时生效于在线会话**——权限在登录时快照进 OnlineSession（CLAUDE.md 认证链路既定取舍）
- 推论：新增 F 按钮后需**重新登录或 refresh** 才对会话生效；停用菜单不踢会话
- **e2e 不做"改完立即可用"断言**；前端不做任何"权限已生效"提示文案；契约单列语义节

### D8 e2e 设计：M 场景 + 删净纪律（保护既有回归）

- 新增 `cloud-e2e/run-menu-e2e.mjs`（场景号 M1-M6，沿用 harness lib）；`npm run e2e` 串行追加为第三段（user → role → menu）
- 测试数据：菜单名 `E2E` 前缀+时间戳；**绝不编辑/删除种子菜单**（id 10/11/12/13/111… 与认证管理 20/21/211）
- **删净纪律（本场景特有，最高优先）**：role e2e R5a 断言"admin 绑定全部菜单 → 所有 checkbox 全选/半选"（`active == total`）——菜单 e2e 若残留任何 E2E 行，admin 未绑定它，**下一轮回归 R5a 必红**。故结束必须按 F → C → M 自底向上删净并断言树中无 E2E 残留（CLEANUP 步骤）
- 场景与功能代码同 commit（技能纪律）

## 3. 页面结构设计（线框）

`/system/menu`，el-card 容器（与用户/角色页同构）：

```
┌ el-card ────────────────────────────────────────────────────────┐
│ header: [菜单管理]                              [新增菜单(primary)] │
│ ┌ el-table（树形 row-key=id default-expand-all，v-loading）─────┐ │
│ │ ▼ 名称     | 类型(tag) | 权限标识 | 排序 | 状态(tag) | 创建人   │ │
│ │            | 创建时间 | 更新人 | 更新时间 | 操作(编辑/删除)     │ │
│ │ ▼ 系统管理  [目录]     -          1    [正常]  -      …        │ │
│ │   ▼ 用户管理[菜单] system:user:list 1  [正常]  …               │ │
│ │     · 用户新增[按钮] system:user:add 1 [正常]  …    [编辑][删除]│ │
│ └───────────────────────────────────────────────────────────────┘ │
│ 无分页（全量树）；操作列 width 120 fixed right                      │
└──────────────────────────────────────────────────────────────────┘
弹窗（页面私有 components/）：
  MenuFormDialog —— 新增/编辑（类型/上级/名称/权限标识/排序/状态，D3 联动）
```

- 10 列：名称（min-width 240，树形缩进首列）/ 类型 90 / 权限标识 min-width 160 / 排序 70 / 状态 80 / 创建人 100 / 创建时间 160 / 更新人 100 / 更新时间 160 / 操作 120 fixed right（编辑 primary、删除 danger）
- 工具栏仅"新增菜单"（无搜索——全量树在前端，搜索属锦上添花不做，契约无此参数）

## 4. 组件设计：MenuFormDialog.vue（对照 RoleFormDialog）

- props：`modelValue: boolean` + `mode: 'add' | 'edit'` + `menu?: MenuTreeNode`（编辑回显行数据）+ `treeData: MenuTreeNode[]`（父级候选数据源，页面持有免二次请求）
- emits：`update:modelValue` + `success`
- 字段与校验（前端约定，后端仅 name 非空白——契约宽松语义）：

| 字段 | 组件 | 校验 |
|---|---|---|
| type 类型 | el-radio-group（M/C/F 绑定常量） | **编辑 disabled**（D3）；add 默认 M；切换时重置上级选择并 clearValidate |
| parentId 上级 | type=M：只读"根目录"；type=C/F：el-tree-select（候选见 D3） | C/F 必选（"请选择上级"）；M 固定 "0" |
| name 名称 | el-input | 必填；1-30 位 |
| perms 权限标识 | el-input（仅 C/F 显示） | F 必填 + pattern `^[a-zA-Z][a-zA-Z0-9:_-]{0,49}$`；C 选填（同 pattern，可空）；M 提交 "" |
| sort 排序 | el-input-number | 0-999，默认 0 |
| status 状态 | el-radio-group | 常量 0/1，默认 0 |

- `watch(modelValue)` 打开初始化：edit 回显六字段（type 锁定）；add 给默认（M/根目录/空名/0/0）；先 `clearValidate()`
- 候选树构建（纯函数，组件内）：`mCandidates(treeData)`（仅 M 分支递归保留 M 子孙）、`cCandidates(treeData)`（C 节点剥 children）
- 提交：validate → add 走 `createMenu`（toast"新增成功"）/ edit 走 `updateMenu`（toast"保存成功"）→ `emit('success')` + 关闭；catch 留空（3005/3006/3007 由拦截器 toast，弹窗不关可改后重提）；loading 防重复提交

## 5. api 层与类型扩展

### 5.1 types/api.ts

- `MenuTreeNode` 扩为 12 字段：+`status: number`、`createBy/createTime/updateBy/updateTime: string | null`（契约 v2 字段表；后端 listAll 恒查出，DDL NOT NULL/DEFAULT 语义见契约）

### 5.2 api/menu.ts（既有 menuTree() 保留不动，追加 3 函数）

```ts
createMenu(payload: CreateMenuPayload): Promise<string>    // 契约 §2.2，返回新 id 字符串
updateMenu(payload: UpdateMenuPayload): Promise<null>      // 契约 §2.3
deleteMenu(id: string): Promise<null>                      // 契约 §2.4
```

interface：`CreateMenuPayload { parentId; name; perms; type: 'M'|'C'|'F'; sort; status }` / `UpdateMenuPayload = CreateMenuPayload & { id }`（id/parentId 均 string，Long→String）。

## 6. 数据流

```
列表：onMounted / 弹窗 success ─→ loadTree() ─→ menuTree() ─→ treeData（无分页无 total）
新增/编辑：MenuFormDialog 提交 ─→ createMenu/updateMenu ─→ emit success ─→ loadTree()
删除：ElMessageBox.confirm（文案含菜单名+解除角色绑定提示）─→ deleteMenu ─→ toast ─→ loadTree()
（树操作后整树刷新——菜单量级小，不做行级局部更新；展开态随刷新重置为全展开，可接受）
```

## 7. 错误处理矩阵（前端策略：仅分流 200/401，其余统一 toast msg）

| 场景 | 后端 | 前端表现 |
|---|---|---|
| 删除有子级的菜单 | 200 + code 3005 "存在子菜单，先删除子级" | toast；行保留，用户自底向上删 |
| 编辑/删除目标已被他人删除 | 200 + code 3006 "菜单不存在" | toast；刷新后行消失 |
| 上级非法（自指/后代成环/不存在——直连 API 才会触发） | 200 + code 3007 | toast；弹窗保持打开（D3 已结构性规避 UI 路径） |
| name 空白（绕过前端校验直发） | 200 + code 1002 "菜单名称不能为空" | toast；前端 rules 必填已兜住正常路径 |
| 无操作权限 | 200 + code 403 | toast（MVP 按钮全显既有兜底） |
| 未认证/过期/被踢 | HTTP 401 | request.ts 清态跳登录（页面不处理） |
| 树加载失败 | - | 拦截器 toast；表格空态 |

## 8. 状态与常量映射

- `TYPE_MAP: Record<string, { label, tagType }>`：M 目录 primary / C 菜单 success / F 按钮 warning；未知 fallback info + 原值
- `STATUS_MAP`：0 正常 success / 1 停用 danger（同用户/角色页）
- `TYPE_DIR='M' / TYPE_MENU='C' / TYPE_FUNC='F'`、`STATUS_NORMAL=0 / STATUS_DISABLED=1`——radio/提交绑定常量，禁魔法数
- `rowOf(row): MenuTreeNode` 收窄函数——全页唯一断言点（EP 列插槽 DefaultRow 限制，同 role 页）

## 9. 与用户/角色页一致性对照

| 维度 | 用户/角色页 | 菜单页（本方案） | 一致性 |
|---|---|---|---|
| 页面骨架 | el-card + table + pagination | el-card + 树表（**无分页**） | 模式复制，分页随数据形态裁剪 |
| 数据源 | /role/page 分页 | /menu/tree 全量树（D1） | 端点不同、刷新模式同（success → load） |
| 表单弹窗 | UserFormDialog/RoleFormDialog | MenuFormDialog（**type 联动 + 类型化候选**，D3） | 结构同、字段联动随实体形态升级 |
| 删除确认 | 文案含名称+解绑提示 | 同（对齐 3005/3006 语义，D5） | 模式同 |
| e2e | S/R 场景 + harness lib | M 场景复用 lib（**删净纪律**，D8） | 断言模式复用 |

## 10. 测试策略

### 10.1 后端单测（构建期）

- `MenuTreeBuilderTest`：+用例——toNode 映射 status/createBy/createTime/updateBy/updateTime（树含停用节点，断言字段透传）
- `SysMenuManageServiceTest`：+用例——update 传空白 name 抛"菜单名称不能为空"且 never update；update 传 null name 放行走 mapper（部分更新语义）
- 守护测试零新违规（MenuTreeNode 豁免 VO 限制已核）

### 10.2 后端 curl 验收（经网关，主控或 backend-agent 执行）

- admin 登录取 token → GET /system/menu/tree 断言含 `"status"` 与审计字段名、时间格式、id 为字符串
- 冒烟（**ASCII 名，绝不碰种子 id**）：POST 临时目录 → PUT 改名（另用临时 id 验证 `name:""` → 1002）→ DELETE → tree 无残留

### 10.3 黑盒 e2e（cloud-e2e/run-menu-e2e.mjs，有头 + slowMo 300）

前置：后端三服务 + 网关 + 前端 dev 5173；admin 登录；菜单名 E2E 前缀+时间戳；不碰种子菜单；**结束删净**（D8）。

| 场景 | 断言要点 |
|---|---|
| M0 | admin 登录（前置） |
| M1 列表加载 | 侧边菜单出现"菜单管理"且顺序 用户→角色→菜单→工作台；面包屑 首页/菜单管理；树表默认全展开（根行与 F 行同屏可见）；表头 10 列；类型 tag 三色抽检（系统管理=目录/用户管理=菜单/用户新增=按钮+权限标识列文本）；状态 tag；时间格式；无分页组件 |
| M2 新增目录 | 空提交必填错误（0 请求）→ 新增 E2E 目录（根级）→ 行出现（类型=目录） |
| M3 新增 C 与 F | type=C 未选上级提交 → "请选择上级"+0 请求 → 选 E2E 目录建 E2E 页面（选填 perms 留空）→ type=F 必填上级=E2E 页面、perms 空提交报"请输入权限标识" → 填 `system:e2e:test{stamp}` 建按钮 → 三行层级正确（F 行含树缩进、权限标识列等于提交值） |
| M4 编辑 | E2E 页面改名+停用 → toast"保存成功" → 行内名称更新、状态 tag danger；type radio disabled；上级选择器回显当前父 |
| M5 删除约束 | 删 E2E 目录（有子级）→ toast 含"存在子菜单"（3005）→ 行仍在 → 确认框含菜单名与"解除"提示 → 依次删 F → C → M（目录）→ 各自 toast"删除成功"、行消失 |
| CLEANUP | 树中无 E2E 前缀残留（保护 role e2e R5a 全选断言与后续回归）；种子菜单行仍在 |

视觉核对：树表全貌 / 新增弹窗（type=M 与 type=F 两种联动态）/ 空提交错误 / 编辑回显（type 锁定）/ 3005 toast / 删除确认框，截图 ≥5 张走 analyze_image 比对，结论文字记录（截图不进 git）。

## 11. 已知缺口与取舍记录（不阻塞本需求，详记移交备忘）

1. **动态菜单消费不做**：侧边栏/路由保持静态表（D6），`menu/tree` 驱动动态路由是独立需求
2. **权限快照时效**：菜单变更不实时生效于在线会话（D7，后端既定架构）；"改完立即生效"属后端改造
3. **目录不嵌套**（D3）：前端约定 + 受 AssignMenuDialog 三层布局约束；后端不校验层级类型
4. **实体接参债务**：POST/PUT /menu 以 SysMenu 实体接收（阶段 2 遗留，超集字段不落库），与 role 同模式已在契约记录；DTO 化留待后续统一整治
5. **perms 无唯一性/格式校验**：两条菜单可挂同一 perms（均生效）；后端仅 DDL 长度约束
6. **删除无种子保护**：admin 可删"系统管理"导致功能不可用（同角色保护议题，已另案立项）
7. **停用菜单仍进 tree 与 AssignMenuDialog 候选**：管理页靠 status tag 区分；过滤策略待后续需求

## 给 backend-agent / frontend-agent 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-06-menu-management.md`（后端章 B1-B4 / 前端章 F1-F7，两章独立可并行）。要点：

- **backend**：B1 MenuTreeNode+5 字段与 toNode 映射（+Builder 单测）→ B2 update 补 name 空白校验（+Service 单测）→ B3 `mvn clean install -pl cloud-system -am` 全绿 → B4 起服务 curl 验收（ASCII 冒烟 + 删净）
- **frontend**：F1 类型扩展 → F2 api 3 函数 → F3 路由/Sidebar → F4 树表页 → F5 MenuFormDialog（D3 联动）→ F6 连续两次 build + 联通 → F7 run-menu-e2e.mjs + 全量回归（删净纪律）
- 对齐基线：契约 `2026-10-06-menu-management-api.md`；与实测不符回报主控，不得单方改契约
