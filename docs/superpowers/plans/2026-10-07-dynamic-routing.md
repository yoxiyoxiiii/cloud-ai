# 业务动态路由实施计划（sys_menu 扩列 + user-nav 端点 + 前端动态路由）

- 日期：2026-10-07
- 执行者：backend-agent（后端章 B1-B6）与 frontend-agent（前端章 F1-F7 + e2e 章 E1-E4）**并行**；两章无代码依赖（前端 F1-F6 结构档验收不依赖新后端，闭环档与 E 章依赖 B6 卡点）
- 关联：设计 `docs/superpowers/specs/2026-10-07-dynamic-routing-design.md`（**D1-D12 决策必读**）；契约 `docs/superpowers/contracts/2026-10-07-menu-nav-api.md`（前后端唯一对齐物）；后端规范 `/backend-crud` 技能 + CLAUDE.md 编码规范；前端规范 `/frontend-page` 技能
- 分支：建议 `feat/dynamic-routing` 从 main 切出（主控定夺）；后端工作目录 `D:\source\cloud-ai\cloud-base\`，前端 `cloud-web\`，e2e `cloud-e2e\`
- 基线：main@cc03174；e2e 四脚本全 PASS

## 任务依赖总览

```
后端章（backend-agent）                     前端章（frontend-agent）
B1 DDL（可独立执行，向后兼容）               F1 类型+api ──┐
B2 实体+XML 扩列                            F2 注册表+图标 ←┘
B3 nav SQL+VO+端点                          F3 menu store（依赖 F1/F2）
B4 tree 出参扩列                            F4 路由重构（依赖 F3）
B5 单测+全量构建                             F5 Sidebar/MenuSearch 切源（依赖 F3）
B6【卡点】用户重启 9202 + curl 冒烟           F6 MenuFormDialog（独立，依赖契约即可）
                                            F7 登录页 reset（依赖 F3）
                                            └──────────┬───────────┘
                                       E 章（frontend-agent，需 B6 完成）
                                       E1 既有脚本适配（M3/M4 补 path）
                                       E2 run-nav-e2e.mjs N 系列
                                       E3 全量回归（五脚本）
                                       E4 收尾（双 build/清理/提交收口）
```

## 文件结构终态

```
cloud-base/
├── scripts/sql/
│   ├── 2026-10-07-menu-nav.sql              # B1 ★ 增量 ALTER + 种子 UPDATE
│   └── cloud_system.sql                     # B1（基线 DDL 同步扩列与种子值）
└── cloud-system/src/
    ├── main/java/com/cloudai/system/
    │   ├── controller/SysMenuController.java     # B3（+userNav 端点）
    │   ├── entity/SysMenu.java                   # B2（+path/icon）
    │   ├── mapper/SysMenuMapper.java             # B3（+listNavByAccount）
    │   ├── service/SysMenuManageService.java     # B3（+listUserNav）
    │   ├── util/NavTreeBuilder.java              # B3 ★（剪枝/孤儿/组树）
    │   ├── vo/UserNavVo.java                     # B3 ★
    │   └── dto/MenuTreeNode.java                 # B4（+path/icon）
    ├── main/resources/mapper/SysMenuMapper.xml   # B2/B3（allColumns/insert/update 扩列 + nav 查询）
    └── test/java/com/cloudai/system/
        ├── util/NavTreeBuilderTest.java          # B5 ★
        └── service/SysMenuManageServiceTest.java # B5（如有 nav 服务断言补充）

cloud-web/src/
├── types/api.ts                             # F1（+UserNavNode；MenuTreeNode +path/icon）
├── api/menu.ts                              # F1（+userNav；CreateMenuPayload +path/icon）
├── constants/icons.ts                       # F2 ★（ICON_MAP/resolveIcon）
├── constants/menus.ts                       # F5（删除）
├── router/
│   ├── index.ts                             # F4（静态核心层/守卫/'/'→dashboard/catchAll）
│   └── viewRegistry.ts                      # F2 ★（path→component 显式注册表）
├── stores/menu.ts                           # F3 ★（导航单一来源）
├── layouts/components/
│   ├── Sidebar.vue                          # F5（嵌套渲染 + 工作台尾挂 + default-openeds）
│   └── MenuSearch.vue                       # F5（切源 menuStore.menuItems）
├── views/
│   ├── error/NotFound.vue                   # F4 ★（404/无权限/未开发 兜底）
│   ├── error/MenuError.vue                  # F4 ★（导航加载失败页：重试/重新登录）
│   ├── login/index.vue                      # F7（onMounted + menuStore.reset()）
│   └── system/menu/components/MenuFormDialog.vue  # F6（+path/icon 字段）

cloud-e2e/
├── run-menu-e2e.mjs                         # E1（M3/M4 补填 path）
├── run-nav-e2e.mjs                          # E2 ★（N 系列）
└── package.json                             # E2（e2e 链尾追加 + e2e:nav）
```

---

# 后端章（backend-agent，B1→B6 串行）

执行环境：`MVN=D:/software/apache-maven-3.8.4/bin/mvn`（Git Bash PATH 无 mvn）；**不起停任何服务**（9202 等由用户管理——B1 的 DDL 直连 MySQL 执行，不涉服务重启；B6 是唯一卡点）。

## B1 DDL：sys_menu 扩列 + 种子补值 + 基线同步

**Files:**
- Create: `cloud-base/scripts/sql/2026-10-07-menu-nav.sql`
- Modify: `cloud-base/scripts/sql/cloud_system.sql`

**Steps:**
- [ ] 增量脚本 `2026-10-07-menu-nav.sql`（设计 D1 原文，每列 COMMENT）：
  ```sql
  ALTER TABLE sys_menu
      ADD COLUMN path VARCHAR(100) NOT NULL DEFAULT '' COMMENT '前端路由路径，C型菜单有效（以/开头），空串=不进导航' AFTER type,
      ADD COLUMN icon VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '菜单图标名（@element-plus/icons-vue 组件名），空串=默认图标' AFTER path;

  UPDATE sys_menu SET icon = 'Setting'    WHERE id = 10;  -- 系统管理 M
  UPDATE sys_menu SET path = '/system/user', icon = 'User'       WHERE id = 11;
  UPDATE sys_menu SET path = '/system/role', icon = 'UserFilled' WHERE id = 12;
  UPDATE sys_menu SET path = '/system/menu', icon = 'Menu'       WHERE id = 13;
  UPDATE sys_menu SET icon = 'Lock'       WHERE id = 20;  -- 认证管理 M（path 留空）
  -- id 21 在线用户：path/icon 均不补（无前端页面，保持不进导航，契约 §2）
  ```
  脚本头注释写明：**只补新列值，不动 name/perms/type/sort/status/deleted/审计**；幂等提示（重复执行 ALTER 会报 Duplicate column，脚本头注明"重复执行前先核对列已存在"）
- [ ] 基线 `cloud_system.sql` 同步：CREATE TABLE sys_menu 在 type 之后加同定义两列；种子 INSERT 的列清单与 VALUES 加 path/icon 初值（与上表一致，21 为 `''`/`''`）——新环境一次成型，与增量脚本语义等价
- [ ] **执行增量脚本到本机库**（MySQL 无客户端，CLAUDE.md 通路）：临时 java 单文件源码（JDBC url `jdbc:mysql://127.0.0.1:3306/cloud_system`，root/空密码）执行 ALTER 与 UPDATE，逐条打印影响行数；mysql-connector-j jar 从本地 maven 仓库取（`~/.m2/repository/com/mysql/mysql-connector-j/`，版本实现时验证）；执行后同通路回查 `SELECT id,name,path,icon FROM sys_menu ORDER BY sort,id` 核对；临时 java 文件放 `cloud-base/scripts/` 外的临时目录用后即删，不进 git

**验收标准:**
1. 回查结果：10=Setting、11=/system/user+User、12=/system/role+UserFilled、13=/system/menu+Menu、20=Lock、21 空空；其余列与执行前一致（name/perms/type/sort 零变化）
2. `cloud_system.sql` 全文可重放（DROP 重建后种子含新列值）；ArchitectureGuardTest 的 DDL COMMENT 扫描通过（B5 构建验证）
3. ALTER 向后兼容：旧 9202（未重启）继续正常运行（加列带默认值，无 DML 锁冲突风险——本机单用户开发库）

**提交:** `feat(cloud-system): B1 sys_menu 扩 path/icon 列（增量脚本+种子补值+基线同步）`

## B2 SysMenu 实体与 Mapper XML 扩列

**Files:**
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/entity/SysMenu.java`
- Modify: `cloud-base/cloud-system/src/main/resources/mapper/SysMenuMapper.xml`

**Steps:**
- [ ] SysMenu：type 字段后加 `private String path;`（javadoc：前端路由路径，C 型有效，空串=不进导航）与 `private String icon;`（javadoc：图标名，空串=默认图标）
- [ ] SysMenuMapper.xml：`allColumns` 加 `path, icon`（type 之后，与 DDL 列序一致）；`save` 的两个 `<trim>` 各加 path/icon 的 `<if>`（**标签体换行**，守护规则）；`update` 的 `<set>` 同理（null 不更新的部分更新语义与既有字段一致）

**验收标准:**
1. `MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 绿（含守护测试：单行 `<if>`/COMMENT 扫描）
2. 既有 /menu/tree 等 4 端点行为不变（path/icon 随实体自然带出，B4 才进 MenuTreeNode——本步树端点出参尚无新字段，允许）

**提交:** `feat(cloud-system): B2 SysMenu 实体与 XML 扩 path/icon 动态列`

## B3 导航端点：SQL + NavTreeBuilder + VO + Controller

**Files:**
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/vo/UserNavVo.java`
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/util/NavTreeBuilder.java`
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/mapper/SysMenuMapper.java`
- Modify: `cloud-base/cloud-system/src/main/resources/mapper/SysMenuMapper.xml`
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/SysMenuManageService.java`
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/controller/SysMenuController.java`

**Steps:**
- [ ] `UserNavVo`（vo 包；字段=契约 §2 八字段：id/parentId/name/type/path/icon/sort/children，@Data；children 初始化 `new ArrayList<>()`）
- [ ] Mapper 接口：`List<SysMenu> listNavByAccount(@Param("account") String account);`（javadoc：按账号聚合可见导航菜单，DISTINCT，F 与空 path 的 C 在 SQL 排除）
- [ ] XML 新增 select（契约 §2 SQL 原文：五表 JOIN，`r.status = 0 AND r.deleted = 0`、`m.status = 0 AND m.deleted = 0 AND (m.type = 'M' OR m.path != '')`、`u.deleted = 0`、`ORDER BY m.sort, m.id`；只用 `#{}`；注释写明 DISTINCT 语义与两处排除规则）
- [ ] `NavTreeBuilder.build(List<SysMenu>)`（util 包 final 工具类，镜像 MenuTreeBuilder 风格）：toNode 原生 setter 直构 UserNavVo（禁三方拷贝）；按 parentId 分组 → 根（parentId=0）→ 孤儿提升（父不在集合 → 挂根级，保留原 parentId）→ sort 升序（nullsLast）→ **自底向上剪枝：type=M 且 children 空 → 不入父级/根级**（实现顺序建议：先递归 fillChildren 再剪，或构建时 M 无子直接跳过——单测覆盖两种路径）
- [ ] Service：`public List<UserNavVo> listUserNav()`——`SecurityUtils.currentAccount()` 空/空白 → 返回 `List.of()`；否则 `menuMapper.listNavByAccount(account)` → `NavTreeBuilder.build(...)`；只读不加 @Transactional
- [ ] Controller（契约 §2）：
  ```java
  /** 查询当前用户的导航树（仅 M/C，实时查库按角色聚合；无 @PreAuthorize——任何已登录用户可访问自己的投影） */
  @GetMapping("/user-nav")
  public R<List<UserNavVo>> userNav() {
      List<UserNavVo> nav = manageService.listUserNav();
      return R.ok(nav);
  }
  ```
  两行式（守护红线）、无 @PreAuthorize、无 @PathVariable

**验收标准:**
1. 单测绿（B5）；守护全绿（list 前缀命名、R<List<UserNavVo>> 不涉实体直出规则、新 SQL 含 deleted）
2. 方法体行数 ≤50 行目标（build 拆 toNode/sortNodes 私有方法，镜像 MenuTreeBuilder）

**提交:** `feat(cloud-system): B3 当前用户导航树端点 GET /menu/user-nav（JOIN 聚合+剪枝组树+VO）`

## B4 MenuTreeNode 出参扩列（tree 端点 additive）

**Files:**
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/dto/MenuTreeNode.java`
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/util/MenuTreeBuilder.java`

**Steps:**
- [ ] MenuTreeNode 加 `private String path;`、`private String icon;`（javadoc 引契约 §5.1）
- [ ] MenuTreeBuilder.toNode 补两行 setter 映射

**验收标准:**
1. `/menu/tree` 出参节点多 path/icon 两字段（契约 §5.1 additive；AssignMenuDialog 运行时忽略）；既有 12 字段名/类型/语义零变化

**提交:** `feat(cloud-system): B4 MenuTreeNode 出参 additive 扩 path/icon（v2 增补）`

## B5 单元测试与全量构建

**Files:**
- Create: `cloud-base/cloud-system/src/test/java/com/cloudai/system/util/NavTreeBuilderTest.java`
- Modify（按需）: `cloud-base/cloud-system/src/test/java/com/cloudai/system/service/SysMenuManageServiceTest.java`

**Steps:**
- [ ] NavTreeBuilderTest（镜像 MenuTreeBuilderTest 风格）至少覆盖：a) M+C 正常组树与 sort 排序；b) M 全部子级缺席（空 path 的 C 在 SQL 已滤——builder 输入侧用"仅 M 无子"模拟）→ M 被剪；c) 孤儿 C（父不在集）→ 挂根级且 parentId 保留原值；d) 根级 C 直接挂根；e) children 叶子为 `[]` 非 null
- [ ] SysMenuManageServiceTest：listUserNav 的 account 空防御断言（返回空列表，不打 mapper）——SecurityUtils 静态调用的可测性按既有测试模式处理（实现时验证，若静态不可 mock 则该断言移入 NavTreeBuilderTest 注释说明）
- [ ] `MVN -f cloud-base/pom.xml clean install` 全量绿（含 sso/gateway 模块回归与全部守护测试）

**验收标准:**
1. 全量构建零红；新增单测全部真实断言（非假绿——surefire 3.2.5 固定，CLAUDE.md）

**提交:** `test(cloud-system): B5 NavTreeBuilder 单测（剪枝/孤儿/排序）+ 全量构建绿`

## B6【卡点】请用户重启 9202 + curl 冒烟（主控协调，非 agent 自行操作）

> **本任务是全栈联调卡点**：前端 E 章与手工闭环验收都依赖它。主控在 backend/frontend 结构档任务完成后，向用户请求：
> **"请重启 cloud-system 服务（9202）——重新打包并 java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar"**（网关 18080 / sso 9201 无需重启）。

**Steps（用户重启后，backend-agent 或主控执行）:**
- [ ] 重启前先 `MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 产出新 jar（B5 已做可跳过）
- [ ] curl 冒烟（经网关 18080；**Git Bash 中文 JSON 是 GBK 会 500——只发 ASCII**；token 用 admin/admin123 登录获取）：
  1. `POST /sso/auth/login`（admin/admin123）取 accessToken
  2. `GET /system/menu/user-nav`（Bearer）→ code 200；data 形状与契约 §2 示例一致（根=系统管理 M + 3 个 C 子级带 path；无 F、无 认证管理、无 在线用户）——用 python/jq 只校验结构字段名与 path 值
  3. 无 token 直调 → 网关真实 401
  4. `GET /system/menu/tree`（Bearer，admin 有 system:menu:list）→ 节点含 path/icon（B4 验证）
  5. （可选）造一个普通账号绑部分角色走 user-nav 验证子集——留给 e2e N2，curl 不做
- [ ] 冒烟产物（命令与响应摘录）回报主控，通知 frontend-agent 联调开启

**验收标准:**
1. 上述 4 项冒烟全部符合契约；HTTP 恒 200（除网关 401 例外）
2. 卡点解除标记：主控在两 agent 群组明确"9202 已重启，E 章可启动"

**提交:**（无代码，冒烟证据不入 git；若冒烟发现缺陷，修复走 B 章追加任务并重新走 B6）

---

# 前端章（frontend-agent，F1→F7 串行；F6 可与 F3-F5 并行）

执行环境：Node 24 / npm 10；**不新增任何 npm 依赖**；Element Plus 按需红线（组件自动导入已就绪）；`npm run build`（vue-tsc strict）每任务绿；F1-F6 为"结构档"验收（不依赖新后端——B6 卡点前登录会落 /menu-error，属预期可观测行为，正好顺路验证 D6 失败路径）。

## F1 类型与 API 对齐

**Files:**
- Modify: `cloud-web/src/types/api.ts`
- Modify: `cloud-web/src/api/menu.ts`

**Steps:**
- [ ] types：`UserNavNode`（契约 §7 八字段，type `'M' | 'C'`）；`MenuTreeNode` 追加 `path: string; icon: string`（additive）
- [ ] api/menu.ts：`userNav(): Promise<UserNavNode[]>`（GET /system/menu/user-nav）；`CreateMenuPayload` 追加 `path: string; icon: string`（UpdateMenuPayload 继承）

**验收标准:**
1. build 绿；既有 AssignMenuDialog/菜单页消费不受类型扩展影响（运行时忽略）

**提交:** `feat(cloud-web): F1 导航类型与 userNav API（契约 §2/§7）`

## F2 视图注册表与图标常量

**Files:**
- Create: `cloud-web/src/router/viewRegistry.ts`
- Create: `cloud-web/src/constants/icons.ts`

**Steps:**
- [ ] viewRegistry（设计 D1/D5）：`export const VIEW_REGISTRY: Record<string, Component> = { '/system/user': UserManageView, '/system/role': RoleManageView, '/system/menu': MenuManageView }`（静态 import，路径键=sys_menu.path 约定）；导出 `resolveView(path: string): Component`——未注册返回 NotFound 视图（F4 产物，本任务可先占位 import 自 F4 路径，若循环依赖则把 resolveView 的 fallback 放 F4 实现并在此导出类型——实现时按构建结果定，优先简单方案）
- [ ] icons（设计 D8）：具名导入 16 个图标（Setting/User/UserFilled/Menu/Monitor/Lock/Bell/Document/Files/PieChart/DataAnalysis/OfficeBuilding/Cpu/Connection/Key/Link——**存在性实现时验证，缺名即从清单去掉并记录**）组 `ICON_MAP`（markRaw 包裹）；`resolveIcon(name?: string): Component` 未知名/空 → Menu 兜底（无 console 输出）

**验收标准:**
1. build 绿；`import *` 禁令遵守（bundle 体积不显著增长，`npm run build` 产物 chunks 目测核对）

**提交:** `feat(cloud-web): F2 视图注册表（path→component）与图标白名单映射`

## F3 菜单 store（导航单一来源）

**Files:**
- Create: `cloud-web/src/stores/menu.ts`

**Steps（设计 D7/D9，全部要点）:**
- [ ] state：`navTree: UserNavNode[] = []`、`loaded = false`、`_loading: Promise<boolean> | null = null`、`_addedNames: string[] = []`
- [ ] `MenuItem` 接口（path/title/icon: Component）与 `DASHBOARD_ITEM = { path: '/dashboard', title: '工作台', icon: Monitor }` 迁入本文件（constants/menus.ts 的接任者）
- [ ] getter `menuItems`：树遍历收集全部 C 节点（M 递归）→ map 成 MenuItem（icon 经 resolveIcon）→ 尾挂 DASHBOARD_ITEM
- [ ] `pathToRouteName(path)`：段首大写驼峰（'/system/user'→'SystemUser'），导出供注册与约定核对
- [ ] `ensureLoaded()`：loaded 已真直接 return true；`_loading` 非空 await 复用；否则 `userNav()` → 存 navTree → `buildRoutes()` → loaded=true → return true；catch → `_loading=null`、loaded 保持 false、return false（**不 toast**——守卫落 MenuError 页，页面负责反馈；toast 会与 MenuError 页重复）
- [ ] `buildRoutes()`：遍历 C 节点——去重（path 已注册过/派生 name 已存在跳过）+ 保留字（'/login' '/dashboard' '/redirect' 派生名与静态 name：Login/Dashboard/Redirect/NotFound/MenuError）跳过 → `router.addRoute('Layout', { path, name, component: resolveView(path), meta: { title: name字段, icon } })`；登记 `_addedNames`
- [ ] `reset()`：清 navTree/loaded/_loading + `_addedNames.forEach(name => router.removeRoute(name))` + 清空数组（幂等）
- [ ] 顶部 `import router from '../router'`（模块环仅函数内使用，安全——设计 D7 论证）

**验收标准:**
1. build 绿；单文件可独立 typecheck；store 不 import 任何视图组件（视图经 viewRegistry 间接解析）

**提交:** `feat(cloud-web): F3 菜单 store（ensureLoaded/buildRoutes/reset，动态路由单一来源）`

## F4 路由重构：静态核心层 + 守卫 + 兜底页

**Files:**
- Modify: `cloud-web/src/router/index.ts`
- Create: `cloud-web/src/views/error/NotFound.vue`
- Create: `cloud-web/src/views/error/MenuError.vue`
- Modify: `cloud-web/src/views/dashboard/index.vue`（如有引用调整；defineOptions 不动）

**Steps（设计 D5/D6）:**
- [ ] 静态表重构：移除 system/user|role|menu 三条子路由；`'/'` redirect 改 `'/dashboard'`；Layout children 终态 = dashboard / `redirect/:path(.*)`（既有） / `{ path: '/:pathMatch(.*)*', name: 'NotFound', component: NotFoundView, meta: { title: '404' } }` / `{ path: '/menu-error', name: 'MenuError', component: MenuErrorView, meta: { title: '加载失败' } }`
- [ ] NotFound.vue：居中卡片文案"页面不存在或无访问权限"（同时承接 404/无权限直链/未开发 path 三义）+ 主按钮"返回工作台"（router.push('/dashboard')）；`defineOptions({ name: 'NotFound' })`
- [ ] MenuError.vue：文案"菜单加载失败，请检查网络后重试" + 按钮"重试"（`menuStore.reset()` 后 `router.push(route.query.redirect 或 '/')`）+ 次按钮"重新登录"（clearAuth + push /login）；`defineOptions({ name: 'MenuError' })`
- [ ] 守卫（设计 D6 流程原文实现）：MenuError 恒放行最前 → public 分支 → 未登录分支 → `!menuStore.loaded` → `await ensureLoaded()`，失败 `return { name: 'MenuError', query: { redirect: to.fullPath } }`，成功 `return to.fullPath`；afterEach 标题逻辑不动
- [ ] 注意 import 顺序：router/index.ts 顶部 import stores/menu 会成环（store 也 import router）——两文件均只在函数体内使用对方（守卫回调/buildRoutes 运行时调用），ESM 安全；若 vue-tsc 报循环类型错误，将 store 的 router 引用改为 `import type` + 运行时 `import()` 动态取（实现时验证，优先前者）

**验收标准（结构档，B6 前）:**
1. build 绿；dev 起服：未登录直访 /system/user → /login（S1 语义不变）
2. 登录 admin（后端若为旧版 9202，user-nav 404）→ 落 /menu-error 页可点"重试"（D6 失败路径顺路验证——**此为结构档预期行为**）
3. B6 后复核：登录 → 落 /dashboard（无 redirect 参数时）；直链 /system/user 刷新守卫重放后命中；无权限路径（手工造：登出后直链乱路径）→ NotFound 页 + 返回工作台按钮可用
4. `'/'` 不再产生重定向环（零菜单用户模拟可后置到 e2e N2）

**提交:** `feat(cloud-web): F4 路由两层结构（静态核心层/守卫等菜单/NotFound 与 MenuError 兜底）`

## F5 Sidebar 嵌套渲染 + MenuSearch 切源

**Files:**
- Modify: `cloud-web/src/layouts/components/Sidebar.vue`
- Modify: `cloud-web/src/layouts/components/MenuSearch.vue`
- Delete: `cloud-web/src/constants/menus.ts`

**Steps（设计 D7/D10）:**
- [ ] Sidebar：数据源改 `menuStore.navTree`；模板 `v-for` 根级节点——`type==='M'` → `el-sub-menu`（`:index="node.id"`；#title 插槽 = resolveIcon(node.icon) + `<span>{{ node.name }}</span>`；内部 `v-for` children（恒 C）→ `el-menu-item :index="c.path"` 图标+title 插槽）；根级 C（孤儿提升场景）→ 直接 `el-menu-item`；**动态树之后**静态尾挂 `<el-menu-item index="/dashboard">`（Monitor 图标 + 工作台）——**模板顺序即 DOM 顺序，M1 精确串断言依赖此结构（设计 D10 四条件）**；`el-menu` 加 `:default-openeds="openedIds"`（根级 M 节点 id 列表，默认展开）；`:default-active="route.path"` 与 `:collapse` 不动；空 navTree 时仅工作台一项（合法态）
- [ ] MenuSearch：数据源改 `menuStore.menuItems`（getter 已含工作台尾挂）；过滤/键盘交互全部不动
- [ ] 删除 `constants/menus.ts`（消费方已全量切换；`MenuItem`/`DASHBOARD_ITEM` 已迁 F3）
- [ ] 折叠态 tooltip 依赖 #title 插槽的既有约定在 sub-menu/item 两级均保留

**验收标准（结构档，B6 后手工闭环）:**
1. admin 登录：侧边 = 系统管理（展开）> 用户/角色/菜单 + 尾挂工作台；`.el-menu .el-menu-item` 四项 DOM 顺序 `'用户管理,角色管理,菜单管理,工作台'`（DevTools 或临时脚本核对——**这是 M1 保真的前置人工验证**）
2. 菜单高亮/跳转正常；折叠态 sub-menu 出 tooltip；Ctrl+K 搜索四项可过滤可跳转
3. build 绿；console 零错误

**提交:** `feat(cloud-web): F5 侧边菜单嵌套渲染与搜索切源（动态树+工作台尾挂，M1 DOM 保真）`

## F6 MenuFormDialog 扩 path/icon 字段

**Files:**
- Modify: `cloud-web/src/views/system/menu/components/MenuFormDialog.vue`

**Steps（设计 D11 / 契约 §5.2）:**
- [ ] form 加 `path: string`、`icon: string`；type 联动：`handleTypeChange` 重置 `path=''/icon=''`；M/F 隐藏两字段提交空串，C 显示
- [ ] 校验：path 仅 C 必填——自定义 validator（与 perms 同款分流）：C 空值报"请输入路由路径"；非空校验 `/^\/[a-zA-Z][\w/-]*$/`（"以 / 开头，仅字母/数字/中横线/下划线/斜杠"）+ maxlength 100（DDL 对齐）；icon 选填 maxlength 50；M/F 恒过
- [ ] 编辑回显 `props.menu.path/icon`（MenuTreeNode 已扩字段，B4 后可用——结构档期后端未重启则编辑种子菜单回显为空串，属预期）；提交八写字段 + id（CreateMenuPayload/UpdateMenuPayload 已扩）
- [ ] 字段位置：名称之后、权限标识之前；placeholder："必填，如 /system/xxx"（C）

**验收标准（B6 后手工）:**
1. 新增 C 不填 path → 内联报错 0 请求；填 `/e2e/test` 可提交且请求体含 path/icon；M/F 提交体两字段为空串
2. 编辑回显正确、可改可存；AssignMenuDialog 打开勾选提交零变化（红线回访）

**提交:** `feat(cloud-web): F6 菜单弹窗扩路由路径/图标字段（C 型 path 必填，v2 增补契约）`

## F7 登录页会话清理点扩容

**Files:**
- Modify: `cloud-web/src/views/login/index.vue`

**Steps:**
- [ ] onMounted 追加 `menuStore.reset()`（与既有 `tagsStore.closeAll()` 并列；javadoc 注明三路径收敛：手动退出/401 清态/直接访问——脚手架 D4 模式延续）

**验收标准:**
1. 登出 → 登录页 → 重登另一账号：侧边菜单即刻反映新账号（无旧路由/菜单残留）；devtools 无 removeRoute 报错（未注册 name 移除幂等）

**提交:** `feat(cloud-web): F7 登录页清动态路由（menuStore.reset 收敛点）`

---

# e2e 章（frontend-agent；**前置 = B6 卡点解除**）

## E1 既有脚本适配核查（预期仅 run-menu 一处改动）

**Files:**
- Modify: `cloud-e2e/run-menu-e2e.mjs`（M3/M4 补填 path）
- 其余三脚本：**预期零改动**（设计 D10 论证），红了按下方清单定位

**Steps:**
- [ ] run-menu M3（新增 C）/M4（编辑 C）：表单填名称后补填路由路径输入框（placeholder "必填，如 /system/xxx" 定位或 form-item label 定位）——值用 `/e2e/page${stamp}` 风格；请求体断言追加 `reqBody.path` 核对（可选）
- [ ] M1 **不改动**（设计保真）；跑一遍确认精确串仍成立——**红了先查实现是否违反 D10 四条件（sub-menu 结构/尾挂顺序/21 排除/default-openeds），修实现优先于改断言；确属 EP DOM 差异才允许按 D10 兜底条款改断言并登记**
- [ ] 逐脚本回归核查表（跑前静态核对 + 跑后红项定位）：

| 脚本/场景 | 新结构影响点 | 预期 |
|---|---|---|
| user S1-S4 | 守卫/redirect 链路 | 无改动 |
| user S6/S7 | 菜单 labels includes/点击/F5 | 无改动（工作台静态恒在） |
| user S8+/S12 | 401 清态跳登录 | 无改动（login onMounted 扩 reset 不影响断言） |
| role R1-R6 | 相邻断言/.perm-* 域/请求体 | 无改动 |
| menu M0/M1/M2 | login 回跳/精确串/目录新增 | 无改动（M2 是 M 型无 path 字段） |
| menu M3/M4 | **C 型表单新必填 path** | **补填（本任务唯一改动）** |
| menu M5/CLEANUP | F 型/删净 | 无改动（F 无 path 字段） |
| scaffold T1-T9 | 搜索域（menuItems 同序）/tags/落点 dashboard | 无改动 |
| 全局 | `'/'` redirect 改 /dashboard | 无断言依赖（harness login 只断言离开 /login） |

**验收标准:**
1. `npm run e2e:user` / `e2e:role` / `e2e:menu` / `e2e:scaffold` 四脚本全 PASS（适配仅 M3/M4 两处填值）

**提交:** `test(cloud-e2e): E1 既有脚本适配（M3/M4 C 型补 path）+ 兼容核查`

## E2 新增 run-nav-e2e.mjs（N 系列）

**Files:**
- Create: `cloud-e2e/run-nav-e2e.mjs`
- Modify: `cloud-e2e/package.json`（`e2e`/`e2e:headless` 链尾追加 `&& node run-nav-e2e.mjs`；新增 `"e2e:nav"`）

**Steps（测试数据纪律：e2e 前缀+时间戳；绝不改 admin/种子；种子菜单零触碰；结束删净；黑盒——禁 import 前端代码）：**
- [ ] N0 前置：admin 登录（harness login）
- [ ] N1 admin 全量可见 + API 形状：`waitForResponse /api/system/menu/user-nav` 捕获响应体——断言：code 200；根级恰 1 节点 type M name 系统管理；children 恰 3 个 C 且 path 依次 /system/user、/system/role、/system/menu；无 F 节点（递归全树 type ∈ {M,C}）；无 认证管理/在线用户；侧边 `.el-menu .el-menu-item` join === `'用户管理,角色管理,菜单管理,工作台'`（M1 同款断言复核动态源）
- [ ] N2 RBAC 闭环（核心场景）：UI 建角色 `e2e导航{ts}`（roleKey `e2enav{ts}`）→ 角色页"分配权限"弹窗仅勾选 **角色管理**（半选自动带上系统管理）保存 → UI 建用户 `e2enav{ts}`（密码沿用既有 e2e 口令风格）并分配该角色 → `logoutViaUi` → 以新用户 login → 断言：侧边 `.el-menu .el-menu-item` join === `'角色管理,工作台'`；无 用户管理/菜单管理；点角色管理 → /system/role 表格加载成功（其 C 节点 perms system:role:list 在登录快照内——绑定先于登录，契约 §3）
- [ ] N3 直链无权限兜底：新用户 `page.goto /system/menu` → 落 NotFound 兜底（断言文案含"页面不存在或无访问权限"）；点"返回工作台" → URL /dashboard
- [ ] N4 F5 刷新保持：新用户在 /system/role reload → 路由/菜单/高亮保持；本轮 user-nav 请求恰 +1（内存态重建）
- [ ] N5 两维时效语义（可见≠可操作）：新用户在角色页点"新增角色"开弹窗（按钮可见——前端无按钮权限）填名提交 → 断言 toast 出现 403 类提示（HTTP 200 + body 403，网络捕获佐证）且角色列表不新增该行；弹窗取消
- [ ] N6 登出清态 + admin 恢复：新用户 `logoutViaUi` → admin login → 侧边恢复全量四项（动态路由清空重建的证据）
- [ ] CLEANUP：admin 删除测试用户（用户页）→ 删除测试角色（角色页，确认框含解绑提示）→ 断言两行删净；**N5 若意外成功落库（断言失败路径）也须删净**
- [ ] N-VERIFY：零 console error/pageerror/≥400 /api/网络失败（403 为 HTTP 200 不入 ≥400；favicon 白名单沿用）

**验收标准:**
1. `npm run e2e:nav` 全 PASS；造数删净（复跑两轮均绿即证明）
2. 场景脚本与 package.json 同 commit

**提交:** `test(cloud-e2e): E2 动态路由 N 系列（RBAC 闭环/直链兜底/F5 保持/登出清态）`

## E3 全量回归（五脚本）

**Steps:**
- [ ] B6 卡点已解除 + 4 后端服务在线 + 前端 dev 5173 → 连续两次 `npm run build` 全绿 → `npm run e2e`（user→role→menu→scaffold→nav 串行有头）全 PASS
- [ ] 红项定位原则：先查是否违反设计 D10 四条件/契约字段，其次才考虑脚本适配；修复与场景同 commit

**验收标准:**
1. 五脚本全 PASS；无 console error/≥400/网络失败
2. 截图 ≥3 张（n1-admin-menu.png 嵌套菜单/n2-nav-user.png 受限菜单/n3-notfound.png 兜底页）→ analyze_image 视觉核对（嵌套缩进/图标/高亮要素），结论文字记录，截图不入 git

**提交:** `test(cloud-e2e): E3 动态路由全量回归（五脚本）`

## E4 收尾

**Steps:**
- [ ] 临时 java DDL 执行器等杂物确认未入 git；artifacts 未入 git
- [ ] 全部功能/脚本提交收口；回报主控：五脚本结果 + 截图视觉结论 + 已知事项

**验收标准:**
1. git status 干净（除计划内文件）；两次 build 绿复核

---

# 移交后续阶段的备忘（下轮规划前必读）

1. **设计 §7 八条移交项**随设计文档一体生效（按钮级权限指令/两维时效中间态/component 落库翻案条件/icon picker/M 嵌套 M/导航缓存/menu-error e2e 缺口/新建菜单不自动绑 admin）
2. **错误码 3008+ 归属**：本轮零新增，3008 起整段仍留给「内置角色/菜单保护」另案——该案立项时须同步核对本文档契约 §4 占位声明
3. **视图注册表是前端发版清单**：后端配了 path 的 C 菜单要真正出页面，必须在前端 `viewRegistry.ts` 注册组件并 defineOptions 命名= path 派生名（D9 约定）；建议把此约定沉淀进 /frontend-page 技能（脚手架备忘 5 的延续，主控裁定）
4. **icon 白名单治理**：ICON_MAP 是唯一图标字典，扩图标=改 constants/icons.ts；若后端配了白名单外 icon 名，前端静默兜底 Menu 图标——菜单管理页将来可做下拉选择（数据源即 ICON_MAP）
5. **e2e M1 精确串的保真条件**（设计 D10 四条件）是隐性契约：sub-menu 结构、工作台尾挂、21 无 path、default-openeds——任何侧边栏重构须先读 D10
6. **`'/'` redirect 已改为 /dashboard**：后续若做"首页/欢迎页"需求，直接替换该 redirect 目标即可，守卫与兜底无需动
7. **9202 重启卡点模式**：本轮验证了"DDL 先行（向后兼容）→ agent 不碰服务 → 用户重启收口"的协作可行，后续涉及 system 服务的全栈需求沿用
8. **menu store 与 router 的模块环**是刻意的（D7 论证），重构时不得把 router 引用提升到模块初始化期使用

---

# 给 backend-agent 的任务清单（可直接粘发）

按 B1→B6 串行执行。对齐基线 = 设计 `docs/superpowers/specs/2026-10-07-dynamic-routing-design.md`（**D1-D12 必读**）+ 契约 `docs/superpowers/contracts/2026-10-07-menu-nav-api.md` + 本计划后端章 + `/backend-crud` 技能 + CLAUDE.md 编码规范。要点重申：

- **B1 DDL**：增量脚本 `cloud-base/scripts/sql/2026-10-07-menu-nav.sql`（ALTER 加 path/icon 两列带 COMMENT + 种子五条 UPDATE 只补新列值，21 在线用户不补 path）+ 基线 `cloud_system.sql` 同步；**java 单文件源码执行**（mysql-connector-j 本地仓库 jar，路径实现时验证），回查核对；**不起停任何服务**
- **B2 实体/XML 扩列**：SysMenu +path/icon；SysMenuMapper.xml allColumns/insert/update 扩列（`<if>` 换行，null 不更新语义延续）
- **B3 导航端点**：`SysMenuMapper.listNavByAccount`（五表 JOIN + DISTINCT + `(type='M' OR path!='')` + status/deleted 全显式，契约 §2 SQL 原文）+ `NavTreeBuilder`（剪空 M/孤儿提升/sort；toNode 原生 setter 禁三方拷贝）+ `SysMenuManageService.listUserNav()`（account 空防御返空表）+ Controller `GET /menu/user-nav` **无 @PreAuthorize、两行式返回 `R<List<UserNavVo>>`**、javadoc；方法名 list 前缀合规
- **B4 tree 出参 additive**：MenuTreeNode +path/icon + toNode 映射；AssignMenuDialog 消费不得破坏
- **B5 单测+全量**：NavTreeBuilderTest 五场景；`MVN -f cloud-base/pom.xml clean install` 全绿（守护零豁免）
- **B6 卡点**：构建新 jar → **请用户重启 9202（主控协调，你不自行操作）** → curl 冒烟四项（ASCII only：登录取 token/user-nav 形状/无 token 401/tree 含新字段）→ 回报解锁 E 章
- **红线**：HTTP 恒 200 + body code；Long→String；契约字段表逐字段对齐；发现契约与实测冲突回报主控修契约，不自行改；不加 menu 域 3008+ 新错误码

# 给 frontend-agent 的任务清单（可直接粘发）

按 F1→F7 串行（F6 可与 F3-F5 并行）执行；B6 卡点解除后进 E 章。对齐基线 = 设计文档（**D1-D12 必读，D5/D6/D10 逐条对齐**）+ 契约（§2/§5/§7）+ 本计划前端章/e2e 章 + `/frontend-page` 技能。要点重申：

- **F1 类型/API**：UserNavNode 八字段全 string 化 Long；MenuTreeNode/CreateMenuPayload additive 扩 path/icon
- **F2 注册表/图标**：`router/viewRegistry.ts` path→component 显式表（未注册→NotFound 兜底）；`constants/icons.ts` 具名导入 ~16 图标（禁 `import *`），resolveIcon 兜底 Menu 零 console
- **F3 menu store**：ensureLoaded（in-flight promise 共享）/buildRoutes（**去重+保留字跳过**，addRoute 挂 Layout）/reset（removeRoute 登记名）；MenuItem+DASHBOARD_ITEM 迁入；**失败不 toast**（守卫落 MenuError）
- **F4 路由重构**：静态核心层仅 login/'/'(redirect **/dashboard**)/dashboard/redirect/catchAll→NotFound/menu-error；守卫流程按 D6 原文（**MenuError 恒放行最前防环；失败禁止跳 /login**；成功 `return to.fullPath` 重匹配）；NotFound/MenuError 两兜底页
- **F5 Sidebar/搜索**：navTree 嵌套渲染（M→el-sub-menu 用 id 作 index、#title 插槽；C→el-menu-item 用 path 作 index）+ **工作台静态尾挂**（模板顺序=DOM 顺序，M1 精确串依赖）+ `:default-openeds`；MenuSearch 切 menuStore.menuItems；**删 constants/menus.ts**
- **F6 菜单弹窗**：C 型 path 必填（`/^\/[a-zA-Z][\w/-]*$/`、maxlength 100）、M/F 隐藏提交空串；icon M/C 选填 maxlength 50；编辑回显、八写字段全量提交
- **F7 登录页**：onMounted 追加 menuStore.reset()
- **E 章（B6 后）**：E1 仅 run-menu M3/M4 补填 path（其余零改动核查表跑完登记）；E2 N0-N6+N-VERIFY（**N2 是核心：建角色只绑角色管理→建用户→登录断言侧边恰 `'角色管理,工作台'`**；N3 直链兜底；N4 F5+1 次请求；N5 可见≠可操作 403；N6 登出重登恢复；删净纪律）；E3 `npm run e2e` 五脚本全 PASS + 截图 analyze_image；E4 收尾双 build/无杂物入 git
- **红线**：Element Plus 按需（不引全量样式/不 `import *` 图标）；不新增 npm 依赖；黑盒纪律（cloud-e2e 禁 import 前端代码）；场景脚本与功能代码同 commit；测试数据 e2e 前缀+时间戳、绝不改 admin/种子；发现契约与实测冲突回报主控；中文注释
