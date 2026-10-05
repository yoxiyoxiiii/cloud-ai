# cloud-web 前端试点实施计划（登录 + 布局 + 用户管理）

- 日期：2026-10-05
- 执行者：frontend-agent（角色定义 `.claude/agents/frontend-agent.md`）
- 关联：设计 `docs/superpowers/specs/2026-10-05-cloud-web-frontend-design.md`；契约 `docs/superpowers/contracts/2026-10-05-pilot-auth-user-api.md`（接口入参/返回/错误码**一律以契约为准**，本文不重复罗列字段）
- 分支：`feat/multi-agent-setup`；工作目录 `D:\source\cloud-ai\cloud-web\`

## 任务拆分总览

- **后端任务：无**（cloud-base 阶段 1-3 已完成并验收，本计划仅前端）。
- 前端任务 T1→T6 **串行**（后任务依赖前任务产物），单人执行无并行点。每 Task 一次提交。

### 执行前须知

- 环境：Node v24.14.0 / npm 10.8.0 已验证可用；Git Bash 下执行 npm 命令正常。
- 后端 4 服务（system/sso/bpmn/gateway）按 CLAUDE.md 用 `java -jar` 启动，**仅 T2 冒烟与 T6 验收需要**；启动/停止（taskkill）注意 CLAUDE.md「Windows 陷阱」。
- Git Bash curl 发中文 JSON 是 GBK 会 500——本文 curl 示例均为纯 ASCII，照抄即可。
- 遇契约与实测不符：停下回报主控，不自行改后端、不猜接口。

## 文件结构总览（终态）

```
cloud-web/
├── index.html / package.json / tsconfig*.json / .gitignore / .env.development / .env.production
├── vite.config.ts                       # T1（代理 /api→18080）
└── src/
    ├── main.ts / App.vue                # T1 脚手架 → T2 装配
    ├── types/api.ts                     # T2
    ├── utils/request.ts / storage.ts    # T2
    ├── api/auth.ts / user.ts / role.ts  # T2 / T5
    ├── stores/auth.ts                   # T2
    ├── router/index.ts                  # T2
    ├── layouts/Layout.vue + components/ # T3（T2 先占位）
    └── views/
        ├── login/index.vue              # T2 占位 → T4 实现
        ├── dashboard/index.vue          # T2 占位 → T3 内容
        └── system/user/index.vue + components/  # T2 占位 → T5 实现
```

---

## Task 1: 工程初始化（Vite 脚手架 + 依赖 + 代理 + ESLint 可选）

**Files:**
- Create: `cloud-web/`（create-vite 脚手架全部产物）、`cloud-web/vite.config.ts`（改写）、`cloud-web/.env.development`
- Keep: 脚手架自带 `.gitignore`（确认含 `node_modules`、`dist`）

**Steps:**

- [ ] **Step 1: 脚手架**（在仓库根执行；目录已存在则失败，先确认 `cloud-web` 不存在）

```bash
cd /d/source/cloud-ai
npm create vite@latest cloud-web -- --template vue-ts
cd cloud-web && npm install
```

- [ ] **Step 2: 业务依赖**

```bash
npm install element-plus @element-plus/icons-vue pinia vue-router axios
```

- [ ] **Step 3: vite.config.ts**（按设计 §9：port 5173 + `/api` 代理 rewrite 去前缀）
- [ ] **Step 4: 环境文件**：`.env.development` 写 `VITE_API_BASE_URL=/api`；`.env.production` 占位同键（值 `实现时验证`，部署形态未定）
- [ ] **Step 5: 脚手架演示清理**：删 `src/components/HelloWorld.vue`、`src/assets/vue.svg` 等演示内容，`App.vue` 置为最小模板；保留 `src/vite-env.d.ts`
- [ ] **Step 6（可选，P2）**：ESLint——`npm i -D eslint eslint-plugin-vue @vue/eslint-config-typescript` 并配 `eslint.config.js` + `lint` script；时间紧可跳过，**不阻塞验收**

**验收标准:**
1. `npm run dev` 起在 5173，浏览器见到最小页面（无 HelloWorld）
2. `npm run build` 通过（含 vue-tsc 类型检查），产出 `dist/`
3. `git status` 确认 `node_modules` 未被跟踪

**提交:** `feat(cloud-web): T1 Vite+TS 脚手架/依赖/开发代理`

---

## Task 2: 请求封装 + auth store + 路由守卫（含占位视图）

**Files:**
- Create: `src/types/api.ts`、`src/utils/storage.ts`、`src/utils/request.ts`、`src/api/auth.ts`、`src/stores/auth.ts`、`src/router/index.ts`
- Create（占位）: `src/views/login/index.vue`、`src/views/dashboard/index.vue`、`src/views/system/user/index.vue`、`src/layouts/Layout.vue`
- Modify: `src/main.ts`（pinia/router/Element Plus 全量引入 + 样式）、`src/App.vue`

**Steps:**

- [ ] **Step 1: 类型**：`types/api.ts` 按契约 §6 字典逐项定义（`R<T>`/`PageResult<T>`/`LoginResult`/`SysUserVo`/`SysRoleVo`，注意 Long 字段全 string）
- [ ] **Step 2: storage**：单键 `cloud-web:auth` 存 `{ accessToken, refreshToken, account }` JSON；导出 `getAuth()/setAuth()/clearAuth()`
- [ ] **Step 3: request**：按设计 §5——baseURL 取 env、timeout 15s、请求拦截注入 Bearer、响应拦截 `code===200` 解包 `data` / `code===401` 或 HTTP 401 清态跳登录（防重复跳转）/ 其余 toast `msg`；`declare module 'axios'` 扩展 `skipErrorMessage` 配置
- [ ] **Step 4: api/auth.ts**：`login`/`refresh`/`logout`/`listOnline`/`kickOnline` 类型化函数（refresh/online MVP 未消费但一并定义，量小且契约在手）
- [ ] **Step 5: stores/auth.ts**：state 自 storage 恢复；`loginAction`（成功写 storage+state）/`logoutAction`（调 api，**失败也清本地态**；跳路由由调用方做）
- [ ] **Step 6: router/index.ts**：按设计 §7 路由表（`/login` meta.public；`/` → Layout 子路由 `/system/user`、`/dashboard`；`*` → `/`）+ 前置守卫（token 存在性检查 + redirect 回跳）；四个视图文件与 Layout 先放单行占位模板
- [ ] **Step 7: main.ts**：`app.use(createPinia())`、`app.use(router)`、`app.use(ElementPlus)` + `import 'element-plus/dist/index.css'`
- [ ] **Step 8: 构建验证**：`npm run build` 绿

**验收标准（后端 4 服务启动后）:**
1. `npm run build` 零错误
2. 代理登录冒烟（纯 ASCII）：

```bash
curl -s -X POST http://localhost:5173/api/sso/auth/login \
  -H "Content-Type: application/json" -d '{"account":"admin","password":"admin123"}'
# Expected: {"code":200,...,"data":{"accessToken":"eyJ...","refreshToken":"...","expiresIn":"7200"}}
```

3. 401 透传冒烟：`curl -s -o /dev/null -w "%{http_code}" http://localhost:5173/api/system/user/page -H "Authorization: Bearer bad"` → `401`
4. 守卫行为（浏览器）可在 T4 后复验，本任务以代码评审 + build 为准

**提交:** `feat(cloud-web): T2 axios封装/auth store/路由守卫（占位视图）`

---

## Task 3: Layout 布局（侧边菜单两项 + 顶栏用户名/退出 + 面包屑）

**Files:**
- Modify: `src/layouts/Layout.vue`（实现三段式）
- Create: `src/layouts/components/Sidebar.vue`、`Navbar.vue`、`Breadcrumb.vue`
- Modify: `src/views/dashboard/index.vue`（占位内容："工作台建设中"）、`src/router/index.ts`（补 meta.title/icon）

**Steps:**

- [ ] **Step 1: Layout.vue**：`el-container` → `el-aside`（Sidebar）+ `el-header`（Navbar）+ `el-main`（`<router-view/>`）；aside 宽 200px 固定，不做折叠
- [ ] **Step 2: Sidebar.vue**：静态菜单数组两项（用户管理 `/system/user` icon User；工作台 `/dashboard` icon Monitor），`el-menu` router 模式，`default-active` 跟随 `route.path`；不调 menu/tree 接口
- [ ] **Step 3: Navbar.vue**：左Breadcrumb 右 `el-dropdown`——显示 `authStore.account`，菜单项"退出登录"→ `authStore.logoutAction()` 后 `router.push('/login')`
- [ ] **Step 4: Breadcrumb.vue**：`route.matched` 过滤 `meta.title` 渲染 `el-breadcrumb-item`
- [ ] **Step 5: dashboard 占位页** + 路由 meta（title/icon）补齐；`npm run build` 绿

**验收标准:**
1. devtools 手工写 localStorage：键 `cloud-web:auth` 值 `{"accessToken":"dev-stub","refreshToken":"x","account":"admin"}`，访问 `http://localhost:5173/` → 渲染 Layout，重定向 `/system/user`（用户管理页仍为占位模板）
2. 两菜单项可切换，`default-active` 高亮正确，面包屑随路由变化（首页/用户管理 或 工作台）
3. 顶栏显示 `admin`；点"退出登录"→ storage 清空 + 跳 `/login`（登录页占位，完整链路 T4 复验）
4. `npm run build` 绿

**提交:** `feat(cloud-web): T3 Layout布局/静态侧边菜单/顶栏退出/面包屑`

---

## Task 4: 登录页

**Files:**
- Modify: `src/views/login/index.vue`（替换占位）
- 可选 Create: `src/views/login/index.scss` 或组件内样式（不引全局主题）

**Steps:**

- [ ] **Step 1: 表单**：居中卡片 + `el-form`（account/password 两输入框，rules 必填；回车提交；submit 按钮 loading 态）
- [ ] **Step 2: 提交逻辑**：调 `authStore.loginAction`；成功跳 `route.query.redirect || '/'`；失败用 `skipErrorMessage` 静默 + 表单上方 `el-alert` 内联展示后端 `msg`（如 2001"账号或密码错误"，文案直接用 msg 不逐码映射）
- [ ] **Step 3: 边界**：重复提交防抖（loading 期间禁用）；登录成功后清空表单
- [ ] **Step 4: `npm run build` 绿**

**验收标准:**
1. 未登录访问 `http://localhost:5173/system/user` → 跳 `/login?redirect=%2Fsystem%2Fuser`
2. `admin/admin123` 登录成功 → 回跳目标页进入 Layout
3. 错密码 → 内联提示"账号或密码错误"（HTTP 200 + code 2001 路径，不弹全局 toast）
4. 已登录状态访问 `/login` → 守卫直接跳 `/`
5. 登录后 F5 → 登录态保持（storage 恢复）

**提交:** `feat(cloud-web): T4 登录页（表单校验/错误内联/redirect回跳）`

---

## Task 5: 用户管理页

**Files:**
- Create: `src/api/user.ts`（分页/新增/修改/删除/重置密码/分配角色/角色回显）、`src/api/role.ts`（角色列表）
- Modify: `src/views/system/user/index.vue`（替换占位）
- Create: `src/views/system/user/components/UserFormDialog.vue`、`ResetPwdDialog.vue`、`AssignRoleDialog.vue`

**Steps:**

- [ ] **Step 1: api 层**：按契约 §3/§4 定义类型化函数（注意 `id/total/roleIds` 均字符串；`PUT /system/user` 只传 `{id,nickname,status}`）
- [ ] **Step 2: 页面骨架**：工具栏（"新增用户"按钮）+ `el-table` + `el-pagination`（`total` 用 `Number(total)`；pageNum=1/pageSize=10 初始，`layout="total, prev, pager, next, sizes"` 可选）；`loadUserPage(pageNum)` 加载函数，操作后刷新当前页
- [ ] **Step 3: 表格列**：账号/昵称/状态（el-tag：0 绿"正常" 1 红"停用"）/创建人/创建时间/更新人/更新时间/操作；操作列按钮：编辑、重置密码、分配角色、删除（后者红色文字按钮）
- [ ] **Step 4: UserFormDialog**：`mode: 'add' | 'edit'` + `user?: SysUserVo` props；add 表单 account/nickname/password/status，edit 只 nickname/status（account 只读展示）；rules：必填 + password 6-32 位（前端约定，契约 §7.4）；emit('success') 通知父刷新
- [ ] **Step 5: ResetPwdDialog**：`user` prop + 新密码输入（同 6-32 位校验）→ `PUT /system/user/password/{id}`
- [ ] **Step 6: AssignRoleDialog**：打开时并行 `GET /system/role/list` + `GET /system/user/{id}/roles` → `el-checkbox-group` 回显（回显 id 均为字符串，比对注意类型一致）→ 提交 `PUT /system/user/role` `{userId, roleIds}`（全量覆盖语义，空选即清空）
- [ ] **Step 7: 删除**：`ElMessageBox.confirm` 确认 → `DELETE /system/user/{id}`
- [ ] **Step 8: `npm run build` 绿**

**验收标准（admin 登录后手工过一遍）:**
1. 初始加载：表格出数据、总数与后端一致、翻页/改每页条数正常
2. 新增 `testuser01/昵称/123456` → 提示成功 → 当前页刷新出现该行
3. 编辑该行昵称与状态（停用）→ 保存后行内生效；account/password 不出现在编辑表单
4. 重置密码为新值 → 退出后用 `testuser01/新密码` 能登录（或 curl 验证 200）
5. 分配角色：勾选任一角色保存 → 重开弹窗回显一致；全不勾保存 → 重开为空
6. 删除 `testuser01` → 确认框 → 行消失；再新增同账号可成功（逻辑删除不占位时的正常路径，若报"已存在"属后端墓碑已知取舍，回报即可）
7. `npm run build` 绿

**提交:** `feat(cloud-web): T5 用户管理页（分页/CRUD/重置密码/分配角色）`

---

## Task 6: 构建与联通验收

**Files:**
- Create（可选，P2）: `cloud-web/README.md`（改写脚手架 README 为 3-5 行：技术栈、`npm run dev/build`、代理说明）
- 无其他代码变更

**Steps:**

- [ ] **Step 1: 全量构建**：`npm run build` 零错误零警告（警告记录但不阻塞）
- [ ] **Step 2: 起后端**：4 服务 `java -jar` 后台启动（顺序 system→sso→bpmn→gateway），`curl http://localhost:18080/system/demo/ping` 通
- [ ] **Step 3: 起前端**：`npm run dev`
- [ ] **Step 4: 代理冒烟（纯 ASCII）**：

```bash
curl -s http://localhost:5173/api/system/demo/ping
# Expected: {"code":200,...}（白名单匿名，验证代理 rewrite 正确）
TOKEN=$(curl -s -X POST http://localhost:5173/api/sso/auth/login \
  -H "Content-Type: application/json" -d '{"account":"admin","password":"admin123"}' \
  | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')
curl -s -o /dev/null -w "%{http_code}" http://localhost:5173/api/system/user/page?pageNum=1\&pageSize=10 -H "Authorization: Bearer $TOKEN"
# Expected: 200
```

- [ ] **Step 5: 手工全链路清单**：登录（错/对凭证）→ 布局两菜单切换 + 面包屑 → 用户管理 T5 验收项抽测（新增/编辑/删除/重置密码/分配角色/翻页）→ F5 保持登录态 → 退出 → 回登录页 → 浏览器后退被守卫拦截（重定向 /login）→ 退出后旧 token 手工 curl 应 401
- [ ] **Step 6: 停服**：按 CLAUDE.md 用 netstat 找 PID + `taskkill //F`，确认 9201/9202/9203/18080/5173 释放
- [ ] **Step 7: 提交**

**验收标准:**
1. build 产物存在且 vue-tsc 零错误
2. Step 4 两条 curl 输出符合预期
3. Step 5 清单逐项通过（发现缺陷回对应 Task 修复后复验）
4. 工作区干净、全部 6 个 Task 已提交

**提交:** `feat(cloud-web): T6 构建与端到端联通验收`

---

## 计划自检记录（写计划时核对）

1. **Spec 覆盖**：选型/结构/分层（spec §2-4）→ T1/T2；请求封装与错误矩阵（§5）→ T2；令牌策略与 401（§6）→ T2/T4；路由守卫（§7）→ T2/T4；Layout 与 YAGNI 决策（§8）→ T3/T5；代理构建（§9）→ T1/T6；缺口（§10）均已写入契约 §7 且不产生本计划任务。
2. **契约一致性**：本计划所有接口调用引用契约章节号而不重复字段，防双源漂移；Long→String（id/total/roleIds/expiresIn）与 status 0/1 语义已在 T2/T5 显式提醒。
3. **任务粒度**：每 Task 一次提交、独立可验收；T2 的占位视图保证 T3/T4/T5 各自独立可运行可验收（T3 验收用 devtools 手工注入 stub token，不依赖登录页完成）。
4. **风险预案**：`npm create vite` 交互提示（如询问覆盖/包名）——目录不存在时直接产出；Element Plus 大版本 API 差异（如 el-menu 属性）以官方文档当前稳定版为准，标注"实现时验证"；后端未启动时 T2 冒烟 502——先起服务再验，不是前端缺陷。

## 执行完成后（移交备忘）

- 验收通过后由主控决定合并/集成测试（浏览器自动化）；以下为**已知缺口，不阻塞验收**，规划后续阶段前必读：
  1. **按钮级权限**：全显（后端无权限清单接口，契约 §7.1）；后端补 `/me` 或登录响应带 permissions 后，前端上 `v-permission` 指令 + 菜单过滤。
  2. **静默刷新**：未做（设计 §6 方案 B）；升级条件=后端 refresh 多会话模型落地。
  3. **在线会话管理页 / 角色管理页 / 动态菜单**：接口与契约已就绪，页面未排期。
  4. **生产部署**：`.env.production` 与 nginx 反代形态未定（实现时验证）。
  5. **前端单测/ESLint**：未建（ESLint 为 T1 可选项）；逻辑量增长后补。
