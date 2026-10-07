# 按钮级权限（v-perms）实施计划

- 日期：2026-10-07
- 设计：`docs/superpowers/specs/2026-10-07-button-perms-design.md`（D1-D8）；契约：`docs/superpowers/contracts/2026-10-07-perms-api.md`（端点逐字段 + 指令语义 + menu-nav §5 增补）
- 基线：main@e103086；后端 9201/9202/18080 与前端 dev 5173 由**用户本人管理**——agent 不起停服务
- 改动面：后端**仅 cloud-sso**（3 文件改/增 + 1 测试文件）；前端 cloud-web（8 文件）；e2e run-nav-e2e.mjs（1 文件）。**网关 / cloud-system / common 模块 / DDL / 种子零改动**
- 红线（全任务适用）：契约 additive only（pilot §2.1-2.5 与 LoginResult 零变化）；错误码零新增；既有端点语义零变化；admin/种子零触碰；Element Plus 按需；零新 npm 依赖（指令手写）；e2e 黑盒纪律；git 提交按主控指挥

## 并行与依赖

- **后端章与前端章文件面零交集，可并行开发**；但**联调有先后**：前端 F4（守卫把 /me 纳入原子门）之后的应用行为依赖 9201 新端点存在——旧后端下 /me 404 会导致所有路由落 MenuError（这是设计 D3 原子门的预期行为，不是 bug）
- 推荐执行序：B 章 → B5 卡点（用户重启 9201）→ F 章（F1-F3 可与 B 并行先行，F4-F6 联调须在卡点后）→ E 章
- e2e（E 章）前置：B5 卡点完成 + F 章完成 + 前端 dev 5173 运行中

---

# 后端章（cloud-sso，给 backend-agent）

> 契约依据：`2026-10-07-perms-api.md` §2。方法命名/两行式/javadoc/log.error/原生 setter 等规范由 CLAUDE.md「编码规范」约束；sso 无 ArchitectureGuardTest（该测试在 cloud-system），按同标准书写。

## B1 dto/CurrentUserVo（新增）

- 文件：`cloud-base/cloud-sso/src/main/java/com/cloudai/sso/dto/CurrentUserVo.java`（新）
- 要点：
  - `@Data` + 类 javadoc（/auth/me 出参 VO；最小暴露面：不含 userId/ip/loginTime/tokenId——设计 D8）
  - 字段：`String account`、`List<String> permissions`（字段名/类型与契约 §2 逐字一致）
- 验收：编译通过；无多余字段

## B2 TokenService.findCurrentUser（修改）

- 文件：`cloud-base/cloud-sso/src/main/java/com/cloudai/sso/service/TokenService.java`（改）
- 要点（设计 D1/D8；全程复用既有范式，零新依赖）：
  - 方法签名 `public CurrentUserVo findCurrentUser(String accessToken)`（**find 前缀**；入参为已 strip Bearer 的纯 token，strip 由 controller 做——logout 同款）
  - `JwtUtil.parseToken(jwtProperties.getSecret(), accessToken)`：catch `JwtException` → `log.error` 记根因（含 account 不可得，记异常即可）→ `throw new BusinessException(401, "会话已失效，请重新登录")`
  - `claims.getId()`（jti）→ `stringRedisTemplate.opsForValue().get(SecurityConstants.ONLINE_KEY_PREFIX + tokenId)`：null → 同码同文案 BusinessException（防御路径，契约 §2 错误表）
  - 复用私有 `parseSession(json)`：null → 同上；正常 → 原生 setter 构造 CurrentUserVo（account + permissions 两字段，禁三方拷贝）
  - 只读不加 @Transactional；**不改** issueTokens/refresh/logout 等既有方法
- 验收：单测（B4）覆盖三路径；既有方法零 diff

## B3 AuthController.me（修改）

- 文件：`cloud-base/cloud-sso/src/main/java/com/cloudai/sso/controller/AuthController.java`（改）
- 要点（契约 §2）：
  - `/** 查询当前会话信息（账号与权限快照） */` + `@GetMapping("/me")` + `public R<CurrentUserVo> me(@RequestHeader("Authorization") String authorization)`
  - 复用既有私有 `stripBearer`；**两行式返回**：
    ```java
    CurrentUserVo vo = tokenService.findCurrentUser(stripBearer(authorization));
    return R.ok(vo);
    ```
  - **无 @PreAuthorize**（仅认证——网关已验 token；语义=任何已登录用户读自己的会话投影）
- 验收：编译通过；curl 冒烟（B5）

## B4 单测（修改）

- 文件：`cloud-base/cloud-sso/src/test/java/com/cloudai/sso/service/TokenServiceTest.java`（改）
- 要点（沿用既有 Mockito 范式，TEST_SECRET/JwtUtil 已在测试类可用）：
  1. 合法路径：JwtUtil 签发测试 token → mock `valueOps.get(startsWith("sso:online:"))` 返回 OnlineSession JSON（含 account + 非空 permissions）→ 断言 VO 两字段透传
  2. Redis 无会话：mock get 返回 null → assertThatThrownBy BusinessException，code==401
  3. 坏 token：传 "not-a-jwt" → 同上 code==401
- 验收：`MVN -f cloud-base/pom.xml test -pl cloud-sso -am` 绿

## B5 全量构建 +【卡点】请用户重启 9201 + 冒烟

- 步骤：
  1. `MVN=D:/software/apache-maven-3.8.4/mvn && $MVN -f cloud-base/pom.xml clean install` 全绿（既有 60+ 单测 + 本轮新增零失败）
  2. **【卡点】显式请求用户**：重启 cloud-sso（`java -jar cloud-base/cloud-sso/target/cloud-sso-1.0.0-SNAPSHOT.jar`，端口 9201）。**网关 18080 与 system 9202 无需重启**（零改动）
  3. curl 冒烟（Git Bash，**ASCII only**；经网关）：
     - 无 token：`curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/sso/auth/me` → 期待 **401**（网关通用例外）
     - admin：先 `curl -s -X POST http://localhost:18080/sso/auth/login -H "Content-Type: application/json" -d '{"account":"admin","password":"admin123"}'` 取 accessToken → `curl -s http://localhost:18080/sso/auth/me -H "Authorization: Bearer <token>"` → 期待 `code:200`、`data.account=="admin"`、`data.permissions` 含 `system:user:add`、`system:role:assignMenu`、`sso:online:list`（全量 17 项：user 6 + role 5 + menu 4 + sso 2）
- 验收：两冒烟符合契约 §2；重启后 9201 正常注册 Nacos（网关 /system/demo/ping 等既有链路不受影响）

---

# 前端章（cloud-web，给 frontend-agent）

> 契约依据：`2026-10-07-perms-api.md` §4（指令语义定稿口径）。规范见 CLAUDE.md 前端约定与 /frontend-page 技能；**零新 npm 依赖**。

## F1 类型与 api（可先行，不依赖后端）

- 文件：`cloud-web/src/types/api.ts`（改）、`cloud-web/src/api/auth.ts`（改）
- 要点：
  - types：`CurrentUserVo { account: string; permissions: string[] }`
  - api/auth.ts：`/** 当前会话信息（契约 perms-api §2）：账号 + 权限快照（登录时快照，会话内不变） */ export function getMe(): Promise<CurrentUserVo> { return request<CurrentUserVo>({ url: '/sso/auth/me', method: 'get' }) }`
- 验收：`npm run build` 绿

## F2 stores/perm.ts（可先行）

- 文件：`cloud-web/src/stores/perm.ts`（新）
- 要点（设计 D3，镜像 stores/menu.ts 范式，文件头注释写明同源性论证）：
  - state：`perms: string[]`、`loaded: boolean`、`_loading: Promise<boolean> | null`（in-flight 缓存）
  - getter `hasPerm(value?: string | string[])`：**`!loaded` 恒 false（fail-closed）**；单值 includes；数组 some；undefined/''/[] → false
  - 导出便利函数 `hasPerm(value?: string | string[]): boolean`（内部 `usePermStore()`，供指令与 v-if 场景）
  - actions：`ensureLoaded()`（调 getMe → 存 perms → loaded=true 返 true；catch 返 false **不 toast**——反馈归 MenuError/401 拦截器；finally 清 _loading）；`reset()`（清三态）
- 验收：build 绿

## F3 指令与注册（可先行）

- 文件：`cloud-web/src/directives/perms.ts`（新）、`cloud-web/src/main.ts`（改）
- 要点（设计 D2/D4，示意 ≤30 行内的等价实现由 frontend-agent 落地）：
  - `export const vPerms: Directive<HTMLElement, string | string[]>`：`mounted` 与 `updated` 双钩子均执行 `if (!hasPerm(binding.value)) el.parentNode?.removeChild(el)`
  - 文件头注释写明：数组=任一命中；隐藏=DOM 移除；未加载 fail-closed；只用于原生元素/单根组件（多根组件 Vue 忽略并告警）；perms 会话内不变
  - main.ts：`import { vPerms } from './directives/perms'` + `app.directive('perms', vPerms)`（全局，模板直接 `v-perms="..."`）
- 验收：build 绿；任意页面挂一个试验指令（验收后撤）手测移除生效

## F4 守卫编排 + 收敛点（联调须在 B5 卡点后）

- 文件：`cloud-web/src/router/index.ts`（改）、`cloud-web/src/views/error/MenuError.vue`（改）、`cloud-web/src/views/login/index.vue`（改）
- 要点（设计 D3）：
  - 守卫 `!menuStore.loaded` 块：`const [menuOk, permOk] = await Promise.all([menuStore.ensureLoaded(), permStore.ensureLoaded()])`；`ok = menuOk && permOk`（原子门）；失败分流沿用既有矩阵（`!getAuth()?.accessToken` → /login 带 redirect；否则 MenuError）；成功 `return to.fullPath` 不变
  - MenuError.vue：`handleRetry` 追加 `permStore.reset()`（与 menuStore.reset 并列）；文案微调——title `加载失败`、sub-title `菜单或权限加载失败，请检查网络后重试`（**路由名 MenuError / 路径 /menu-error 不动**）
  - login/index.vue onMounted：追加 `permStore.reset()`（tags/menu/perm 三件套，注释更新）
- 验收：build 绿；F5 后按钮显隐正确；登出→重登另一账号显隐切换无残留；手工构造失败路径（如临时改错 getMe url）验证落 MenuError 且重试可恢复

## F5 三页 12 挂载点（联调须在 B5 卡点后）

- 文件：`cloud-web/src/views/system/user/index.vue`、`role/index.vue`、`menu/index.vue`（改）
- 要点（设计 D5 表逐条，perms 值与种子 111-133 一一对应，勿手打错——从契约 §6 表复制）：
  - user：表头新增用户 `system:user:add`；行内 编辑 `system:user:edit` / 重置密码 `system:user:resetPwd` / 分配角色 `system:user:assignRole` / 删除 `system:user:remove`
  - role：表头新增角色 `system:role:add`；行内 编辑 `system:role:edit` / 分配权限 `system:role:assignMenu` / 删除 `system:role:remove`
  - menu：表头新增菜单 `system:menu:add`；行内 编辑 `system:menu:edit` / 删除 `system:menu:remove`
  - 只挂 el-button，不动操作列本身；弹窗组件不挂
- 验收：build 绿；admin 全显（与改造前逐按钮一致）；受限用户（可临时建 N2 同款角色/用户手工验）粒度显隐

## F6 前端收尾

- 连续两次 `npm run build`（components.d.ts 陷阱惯例）
- 手工冒烟清单：admin 三页全显 / 受限用户粒度显隐 / F5 保持 / 登出重登切换 / MenuError 重试（e2e 不覆盖失败路径惯例延续）
- 验收：全项通过后交 e2e 章

---

# e2e 章（cloud-e2e，给主控/e2e 执行）

> 契约依据：perms-api §5（e2e 纪律增补）+ menu-nav §3。黑盒纪律：page.request 直连 API 允许（不 import 前端内部代码）；token 取自 `localStorage['cloud-web:auth']` 属浏览器态读取，允许。

## E1 run-nav-e2e.mjs 改造

- 文件：`cloud-e2e/run-nav-e2e.mjs`(改)
- 要点（设计 D7，场景号沿用 N 系不新开脚本）：
  1. **me 计数器**：仿 `navCount` 加 `meCount = () => h.state.apiCalls.filter((c) => c.url === '/api/sso/auth/me').length`
  2. **N1 增补**：reload 后断言 meCount 恰 1 + me 响应形状（code 200、`data.account === 'admin'`、permissions 含 `system:role:add` 与 `sso:online:list`）
  3. **N2 增补 N2f**（2e 之后）：受限用户角色页——表头"新增角色" `count() === 0`；行内"编辑"可见、"分配权限"与"删除" `count() === 0`（粒度证明；快照=list+edit）
  4. **N5 重设计**（标题与注释同步改：两维时效=按钮随快照隐藏 + 后端仍 403 兜底）：
     - UI 断言：表头"新增角色"按钮 `count() === 0`（替代原点击开弹窗全流程）
     - 直连断言：`const auth = await page.evaluate(() => JSON.parse(localStorage.getItem('cloud-web:auth') || 'null'))` → `page.request.post(BASE + '/api/system/role', { headers: { Authorization: 'Bearer ' + auth.accessToken }, data: { name: TEST_403_ROLE_NAME, roleKey: TEST_403_ROLE_KEY } })` → 断言 HTTP **200** + `body.code === 403` + `body.msg` 非空（后端最终防线证据）
     - 列表无探针行断言保留（findRoleRowByKey）；CLEANUP 的 403 兜底删除保留
     - 注意：page.request 响应不经 page 网络事件，N-VERIFY badResponses 统计不受污染（勿改）
  5. **N4 增补**：reload 后"新增角色"仍隐藏 + meCount 恰 +1
  6. **N6 增补**：admin 重登后角色页表头"新增角色"可见 + 行内 编辑/分配权限/删除 全可见
  7. **N3 微适配**：MenuError 文案改后，原 `getByText('菜单加载失败')` 断言改为 `!page.url().includes('menu-error')`
  8. 脚本头注释与运行前提更新（9201 须为含 /me 版本）
- 验收：`cd cloud-e2e && npm run e2e:nav` 全 PASS（N0-N6 + CLEANUP + N-VERIFY）；两轮连跑均绿（删净纪律）

## E2 全量回归

- `cd cloud-e2e && npm run e2e`（五脚本串行）
- 兼容论证（设计 D7，如遇红先对照）：run-user/role/menu/scaffold 全部按钮交互由 admin 发起（全显）零影响；nav 建数据步骤均 admin 零影响；唯一行为翻转 N5 已重设计
- 验收：全 PASS；无 e2e 残留数据

---

## 移交备忘（下一阶段规划前必读）

1. **操作列整列不隐藏**：仅 list 权限用户见空"操作"列（外观缺口）；收口方案已论证（hasPerm 对 el-table-column 加 v-if），未纳入本轮
2. **perms 无通配语义**：精确匹配对齐 @PreAuthorize；后端若引通配，前端 hasPerm 须同步改——显隐与执法不可分叉是硬约束
3. **登录响应仍不含 perms**（D1 弃选 c 落款）：若未来登录即需权限渲染，复活 c 方案的评估表在设计 D1
4. **/me 最小暴露**：仅 account/permissions；用户资料（昵称/头像）扩展留给用户中心需求，勿往 /me 堆字段
5. **静默刷新未做**（pilot §7.2 延续）：上线静默刷新后 F5 重拉 /me 自然取新快照，指令无需改
6. **menu-error 失败路径仍无 e2e**：本轮 /me 失败并入同页，手工验收兜底
7. **3008+ 错误码仍归内置角色/菜单保护另案**（零占用声明延续）
8. **/frontend-page 技能修订候选**：补"操作按钮挂 v-perms + 新页面接续"约定（设计 D5 表为范本）
