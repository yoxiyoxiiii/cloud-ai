# 菜单管理实施计划（全栈：后端章 ∥ 前端章）

- 日期：2026-10-06
- 需求：菜单管理（cloud-web 菜单管理页 + cloud-system 菜单接口缺口补齐）
- 设计：`docs/superpowers/specs/2026-10-06-menu-page-design.md`
- 契约：`docs/superpowers/contracts/2026-10-06-menu-management-api.md`（菜单域现行版 v2，取代 pilot §5）
- 并行编排：后端章（B1-B4）与前端章（F1-F7）**无文件交集、可同时派发**；前端 F6/F7 联调与 e2e 依赖后端 B4 完成（此前按契约 mock 或等待）

```
主控：① 同消息并行派发 backend-agent（B1→B4）+ frontend-agent（F1→F6）
      ② 后端 curl 验收通过 → 前端 dev proxy 指 18080 真实联调（F6 复验）
      ③ 前端-agent 跑 F7（run-menu-e2e + 全量回归）+ 视觉核对
      ④ 双审（契约逐条 + quality）→ 修复循环 → 合并 main
```

## 后端章（cloud-base/cloud-system/，backend-agent）

> 改动最小化：**无 SQL / 无 mapper / 无 Controller / 无种子变更**（listAll 已查全列，权限种子已齐）。规范来源：CLAUDE.md 编码规范 + `/backend-crud` 技能；ArchitectureGuardTest 必须保持全绿（MenuTreeNode 已豁免 VO 直出限制）。

### B1 MenuTreeNode 扩展 + 树构建映射（契约 §3）

- 文件：
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/dto/MenuTreeNode.java`（改）
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/util/MenuTreeBuilder.java`（改 toNode）
  - `cloud-base/cloud-system/src/test/java/com/cloudai/system/util/MenuTreeBuilderTest.java`（改，加用例）
- 内容：
  - MenuTreeNode **additive** 追加 5 字段（勿动既有 7 字段与 children 初始化）：`Integer status`、`String createBy`、`LocalDateTime createTime`、`String updateBy`、`LocalDateTime updateTime`（import java.time.LocalDateTime；序列化走全局 Jackson yyyy-MM-dd HH:mm:ss，无需注解）
  - `MenuTreeBuilder.toNode` 补 5 个 setter 映射（数据源 listAll 已查出这些列）
  - 单测：构造含 status/createBy/createTime/updateBy/updateTime 的 SysMenu（含一个 status=1 停用节点）→ build 后断言字段透传（沿既有 menu() 辅助方法扩展）
- 验收：单测绿；出参既有 7 字段名/类型/顺序不变（additive）；叶子 children 仍为 `[]`

### B2 update() 补 name 空白校验（契约 §0 变更点 2 / §2.3）

- 文件：
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/SysMenuManageService.java`（改 update）
  - `cloud-base/cloud-system/src/test/java/com/cloudai/system/service/SysMenuManageServiceTest.java`（改，加 2 用例）
- 内容：
  - update() 在 requireMenu 后加（镜像 SysRoleManageService 对 roleKey 的口径）：
    ```java
    if (menu.getName() != null && menu.getName().isBlank()) {
        throw new BusinessException("菜单名称不能为空");
    }
    ```
    ——**null 不拦**（部分更新语义保留）；空白串（含 "" 与纯空格）拦截，默认码 1002
  - 单测：`edit_blankNameRejected`（name="" → BusinessException "菜单名称不能为空"，verify never update）；`edit_nullNameAllowed`（name=null、parentId=0 → verify update 调用一次——锁死部分更新语义不被误伤）
- 验收：新 2 例 + 既有 3 例全绿；save() 路径零改动

### B3 构建与守护验证

- 文件：无新增（验证任务）
- 内容：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am`
- 验收：全绿（含 ArchitectureGuardTest / MapperXmlBindingTest / 全部单测，无新违规）

### B4 服务级 curl 验收（经网关）

- 文件：无新增（验证任务）
- 内容（ASCII 入参——Git Bash 中文 JSON 是 GBK 会 500；**绝不以种子 id 做写操作**）：
  1. 重建重启 cloud-system（`java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar`，网关/sso 在跑则不动）
  2. `POST /sso/auth/login`（admin/admin123）取 accessToken
  3. `GET /system/menu/tree`：断言节点含 `"status"`、`"createBy"`、`"createTime"`、`"updateBy"`、`"updateTime"` 字段名；id/parentId 为字符串（如 `"id":"10"`）；createTime 为 `yyyy-MM-dd HH:mm:ss` 或 null；叶子 `"children":[]`
  4. 冒烟：`POST /system/menu`（`{"parentId":"0","name":"e2ecurl","type":"M","sort":9,"status":0}`）→ 记新 id → `PUT /system/menu`（该临时 id，`"name":""` → 断言 code 1002"菜单名称不能为空"）→ 再 PUT 正常改名 → `DELETE /system/menu/{临时id}` → tree 断言无 `e2ecurl` 残留
  5. 审计核对：tree 响应中临时行 createBy=createBy 值 `admin`（从响应断言即可，免 DB 直查）
- 验收：以上断言全过且无残留数据；完成后向主控回报（后端就绪信号，解锁前端 F6 联调）

## 前端章（cloud-web/ + cloud-e2e/，frontend-agent）

> 依赖顺序：F1 → F2 → F3 → F4/F5 → F6 → F7。规范唯一来源 `/frontend-page` 技能；样板 = 用户/角色页三件套；**Element Plus 一律按需引入**（el-tree-select 等模板组件由 unplugin 自动覆盖；ElMessage/ElMessageBox 显式 import）。

### F1 类型字典扩展

- 文件：`cloud-web/src/types/api.ts`（改）
- 内容：`MenuTreeNode` 扩为 12 字段（契约 §3）：+`status: number`、`createBy: string | null`、`createTime: string | null`、`updateBy: string | null`、`updateTime: string | null`；注释标"契约 v2（2026-10-06-menu-management-api §3）扩展，additive"
- 验收：`npm run build` 零错误；AssignMenuDialog / 角色页无类型回归（多字段向后兼容）

### F2 api 层扩展

- 文件：`cloud-web/src/api/menu.ts`（改；既有 `menuTree()` **保留不动**）
- 内容（每函数 JSDoc 注契约节号；入参出参 interface）：
  - `CreateMenuPayload { parentId: string; name: string; perms: string; type: 'M' | 'C' | 'F'; sort: number; status: number }`
  - `UpdateMenuPayload = CreateMenuPayload & { id: string }`（可独立 interface 展开）
  - `createMenu(payload): Promise<string>`（§2.2，返回新 id 字符串）/ `updateMenu(payload): Promise<null>`（§2.3）/ `deleteMenu(id): Promise<null>`（§2.4）
- 验收：build 零错误；URL 带 `/system` 前缀走 `/api` 代理；契约没有的参数不出现

### F3 路由与侧边栏（静态表追加，设计 D6）

- 文件：`cloud-web/src/router/index.ts`（改）、`cloud-web/src/layouts/components/Sidebar.vue`（改）
- 内容：
  - router children 在 `system/role` 之后追加 `{ path: 'system/menu', name: 'SystemMenu', component: MenuManageView, meta: { title: '菜单管理', icon: 'Menu' } }`（icon 已验证 @element-plus/icons-vue 导出 `Menu`；动态 import 同既有写法）
  - Sidebar 菜单数组同步追加 `{ path: '/system/menu', title: '菜单管理', icon: Menu }`（import Menu），顺序：用户管理 → 角色管理 → 菜单管理 → 工作台
- 验收：登录后侧边菜单出现"菜单管理"且顺序正确；面包屑 `首页/菜单管理`；高亮正确；**用户/角色页菜单项与断言不受影响**

### F4 菜单列表页（树表，设计 §3）

- 文件：`cloud-web/src/views/system/menu/index.vue`（新建）
- 内容：
  - `TYPE_MAP`（M 目录 primary / C 菜单 success / F 按钮 warning，未知 fallback info+原值）、`STATUS_MAP`（0 正常 success / 1 停用 danger）、`rowOf(row): MenuTreeNode` 唯一收窄函数
  - `loadTree()`：`menuTree()` → treeData；onMounted 与弹窗 `success`/删除成功后刷新；v-loading；**无分页**（全量树）
  - `el-table` 树形：`row-key="id"` + `:tree-props="{ children: 'children' }"` + `default-expand-all`；10 列（名称 min-width 240 / 类型 90 tag / 权限标识 min-width 160（M 行 `-`）/ 排序 70 / 状态 80 tag / 创建人 100 / 创建时间 160 / 更新人 100 / 更新时间 160 / 操作 120 fixed right——编辑 primary + 删除 danger）
  - 删除：`ElMessageBox.confirm('确定删除菜单 "{name}" 吗？删除后将解除该菜单与角色的绑定。', '删除确认', { type: 'warning' })` → `deleteMenu` → toast"删除成功" → `loadTree()`；catch 留空（3005/3006 由拦截器 toast）；**不做级联删除**
  - 工具栏仅"新增菜单"
- 验收：进页树全展开渲染（根/子/孙行同屏）；M/C/F tag 三色；停用行 danger；时间列 `yyyy-MM-dd HH:mm:ss` 或 `-`；删除确认框文案含菜单名与"解除"

### F5 MenuFormDialog（新增/编辑弹窗，设计 §4 / D3）

- 文件：`cloud-web/src/views/system/menu/components/MenuFormDialog.vue`（新建）
- 内容：
  - props `{ modelValue, mode: 'add' | 'edit', menu?: MenuTreeNode, treeData: MenuTreeNode[] }`；emits `update:modelValue` / `success`
  - 字段与联动（常量 `TYPE_DIR/TYPE_MENU/TYPE_FUNC`、`STATUS_NORMAL/STATUS_DISABLED`）：
    - type radio（M/C/F）：**编辑 disabled**；add 默认 M；切换时重置上级并 clearValidate
    - 上级：type=M → 只读展示"根目录"（提交 parentId='0'）；type=C → `el-tree-select` 候选=仅 M 分支（递归剥非 M 子孙）必选；type=F → 候选=全部 C 节点（剥 children）必选；node-key="id" + check-strictly + default-expand-all
    - name：必填 1-30；perms：仅 C/F 显示——F 必填 + pattern `^[a-zA-Z][a-zA-Z0-9:_-]{0,49}$`，C 选填同 pattern（空可提交），M 提交 `''`；sort：el-input-number 0-999 默认 0；status：radio 0/1 默认 0
  - `watch(modelValue)` 打开初始化：edit 回显六字段（parentId 取行值）；add 置默认；先 clearValidate
  - 提交：validate → add `createMenu`（toast"新增成功"）/ edit `updateMenu`（toast"保存成功"，**全量提交六写字段+id**）→ emit success + 关闭；catch 留空（3007 等由拦截器 toast，弹窗不关）；loading 防重
  - 候选构建为组件内纯函数（设计 §4），不额外发请求（treeData 由页面 prop 传入）
- 验收：空提交出必填错误且 0 请求；type 切换时上级选择器形态正确切换（M=根目录只读 / C=M 候选 / F=C 候选）；编辑弹窗 type 锁定且上级回显当前父；F 空 perms 提交被拦；编辑保存后行内名称/状态 tag 更新

### F6 构建与联通验证

- 文件：无新增（验证任务）
- 内容：连续两次 `npm run build` 全绿（components.d.ts 陷阱）；后端就绪（B4 完成信号）后 dev 起服，`curl http://localhost:5173/api/system/demo/ping` 代理链路通；手工走查：树表加载 / 新增 M→C→F / 编辑停用 / 3005 拦截 / 自底向上删除
- 验收：两次 build 零错误；手工冒烟五功能可用（后端未就绪阶段先按契约 mock，联调时切回 18080 并在报告注明）

### F7 e2e：M 场景 + 全量回归（设计 §10.3 / D8）

- 文件：
  - `cloud-e2e/run-menu-e2e.mjs`（新建，复用 `lib/harness.mjs`；场景 M0-M5 + CLEANUP）
  - `cloud-e2e/package.json`（改 scripts：`e2e` = `run-e2e && run-role-e2e && run-menu-e2e`；`e2e:headless` 同理追加；新增 `e2e:menu`）
- 场景断言要点（设计 §10.3 表格，逐条实现）：
  - M1：菜单项顺序 用户→角色→菜单→工作台；面包屑 首页/菜单管理；树表默认全展开（"系统管理"与"用户新增"行同屏可见）；表头 10 列；类型 tag 抽检（系统管理=目录/用户管理=菜单/用户新增=按钮）；权限标识列文本；状态 tag；时间格式；**无 `.el-pagination`**
  - M2：空提交"请输入菜单名称"+0 请求 → 新增 E2E 目录（type=M，根级）→ 行出现（类型=目录）
  - M3：type=C 未选上级提交 → "请选择上级"+0 请求 → 建 E2E 页面（父=E2E 目录，perms 留空）→ type=F：空 perms 报"请输入权限标识" → 填 `system:e2e:test` 建按钮（父=E2E 页面）→ 三行存在、F 行权限标识列=提交值、F 行含树缩进（`.el-table__indent`）
  - M4：编辑 E2E 页面 → type radio disabled、上级回显、改名+停用 → 保存 → 行内名称更新 + 状态 tag danger
  - M5：删 E2E 目录 → toast 含"存在子菜单"（3005）→ 行仍在 → 确认框含菜单名与"解除" → 依次删 F → C → 目录 → 行依次消失
  - CLEANUP：树中无 E2E 前缀残留（**保护 role e2e R5a 的 admin 全选断言与后续回归**）；种子菜单行仍在（系统管理/用户管理等可见）
- 纪律：黑盒（禁 import 前端内部代码）；菜单名 `E2E` 前缀+时间戳；**绝不编辑/删除种子菜单**；e2e 不做"权限改完立即可用"断言（快照时效，契约 §1）；场景脚本与功能代码同一 commit；截图 ≥5 张（树表全貌/新增弹窗 M 态/F 态/编辑回显/3005 toast/删除确认框）走 analyze_image 视觉核对，结论文字记录，截图不进 git
- 验收：`npm run e2e`（有头）三脚本全 PASS；无 console error / pageerror；≥400 响应仅预期业务码（HTTP 恒 200）；CLEANUP 断言通过（无残留）

## 集成与验收编排（主控）

1. 同消息并行派发两章（不同目录零冲突）；各自完成章内验收后回报
2. B4 通过 = 后端就绪 → 前端 F6 真实联调复验 → F7 全量 e2e（三脚本）+ 视觉核对
3. 双审：spec 审查按契约逐条（字段表/错误码/权限标识/additive 声明）+ quality 审查 → 修复循环 → 合并 main（场景脚本与功能代码同一 commit）

## 移交备忘（下一阶段规划前必读）

1. **动态菜单消费未做**：侧边栏/路由仍静态表（设计 D6）——`menu/tree` 驱动动态路由/菜单是独立需求，届时需处理 404 兜底、停用菜单过滤（status 已具备）、按 perms 过滤按钮等
2. **权限快照时效**：菜单变更（新增 perms/停用）不实时生效于在线会话，需重登/refresh（契约 §1）——"改完立即生效/踢会话"属后端改造需求
3. **目录不嵌套为前端约定**（设计 D3）：受 AssignMenuDialog M/C/F 三层布局约束；若将来支持嵌套目录，须同步升级 AssignMenuDialog 渲染、MenuFormDialog 候选与两级 e2e
4. **实体接参债务**：POST/PUT /menu 以 SysMenu 实体接收（阶段 2 遗留，超集字段不落库）——后续统一 DTO 化（MenuSaveRequest）时走契约修订（行为兼容，仅收紧文档）
5. **perms 无唯一性/格式校验**：同 perms 多菜单均生效；若需唯一性（3xxx 新码）另立后端任务
6. **删除无种子保护**：admin 可删"系统管理"致功能不可用——与"内置角色保护"同属已立项的后端保护任务（3008 段错误码预留）
7. **停用菜单仍进 tree 与 AssignMenuDialog 候选**：管理页靠 status tag 区分；"停用菜单不参与分配"过滤策略待后续需求议
8. **e2e 树计数耦合**：menu e2e 的删净纪律保护 role e2e R5a（admin 全选断言 `active==total`）——两脚本通过种子菜单数据耦合，改种子菜单或新增"菜单回收站"须重审两套断言
9. **e2e 脚本顺序**：`npm run e2e` = user → role → menu（menu 最后，其造数不污染前两者当轮断言；残留只影响下一轮，靠 CLEANUP 消除）
10. **type 编辑锁定为前端约定**：后端可改 type（契约 §4.3 宽松语义）——直连 API 改 type 产生的畸形层级无治理入口，数据治理需求另议

## 给 backend-agent 的任务清单（可直接粘发）

按 B1→B2→B3→B4 执行；对齐基线=契约 `docs/superpowers/contracts/2026-10-06-menu-management-api.md`（菜单域 v2）+ 本计划后端章。要点重申：

- B1：`dto/MenuTreeNode.java` additive +5 字段（status/createBy/createTime/updateBy/updateTime，LocalDateTime 走全局 Jackson）+ `util/MenuTreeBuilder.java` toNode 映射 + `MenuTreeBuilderTest` 用例（含 status=1 节点透传）
- B2：`service/SysMenuManageService.java` update() 补 `name != null && isBlank()` → 1002"菜单名称不能为空"（**null 不拦**，镜像 role 的 roleKey 口径）+ `SysMenuManageServiceTest` 加 blankNameRejected / nullNameAllowed 两例
- B3：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am` 全绿（守护测试无新违规；无 SQL/mapper/Controller/种子改动）
- B4：重启 cloud-system 后经网关 curl 验收（admin token；tree 断言新字段名/字符串 id/时间格式/叶子空数组；**用临时 id** 验 PUT name="" → 1002；POST/PUT/DELETE 冒烟 ASCII 名 `e2ecurl` 并删净）
- 红线：与契约不符回报主控不自行猜测；不碰种子数据；完成后回报（解锁前端联调）

## 给 frontend-agent 的任务清单（可直接粘发）

按 F1→F7 顺序执行；对齐基线=契约 §2/§3/§6 + 设计 `2026-10-06-menu-page-design.md`（D2/D3 必读）+ 本计划前端章。要点重申：

- F1：`types/api.ts` MenuTreeNode +5 字段（契约 §3）
- F2：`api/menu.ts` 追加 CreateMenuPayload/UpdateMenuPayload + createMenu/updateMenu/deleteMenu（**menuTree() 保留不动**——AssignMenuDialog 在用）
- F3：`router/index.ts` + `layouts/components/Sidebar.vue` 追加 `/system/menu`（icon `Menu`，顺序 用户→角色→菜单→工作台）
- F4：`views/system/menu/index.vue` 树表（row-key=id + tree-props + default-expand-all；10 列；TYPE_MAP/STATUS_MAP/rowOf；删除确认含"解除…与角色的绑定"；无分页；不做级联删除）
- F5：`views/system/menu/components/MenuFormDialog.vue`（D3 联动：M 固定根目录/C 候选=M/F 候选=C；编辑 type 锁定；F 必填 perms pattern；edit 全量提交六写字段+id；catch 留空；loading 防重）
- F6：连续两次 `npm run build` 全绿；后端就绪后 dev 联通冒烟（未就绪先按契约 mock 并注明）
- F7：`cloud-e2e/run-menu-e2e.mjs`（M0-M5+CLEANUP，复用 lib/harness.mjs）+ `package.json` scripts 追加；**删净纪律**（E2E 前缀+时间戳、不碰种子、F→C→M 自底向上删、CLEANUP 断言无残留）；`npm run e2e` 三脚本全 PASS；截图 ≥5 张 analyze_image 视觉核对（结论文字记录）
- 红线：Element Plus 按需引入（禁全量）；契约与实测不符回报主控；场景脚本与功能代码同一 commit；AssignMenuDialog 行为不得破坏
