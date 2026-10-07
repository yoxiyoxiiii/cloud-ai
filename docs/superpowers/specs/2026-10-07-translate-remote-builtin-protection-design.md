# 翻译 Provider 远程化 + 内置数据保护技术方案（纯后端）

- 日期：2026-10-07
- 状态：已定稿（阶段一需求分析经用户拍板：Q1 本轮=候选 1+候选 2 含 admin 用户、候选 3 另轮全栈；Q2 独立 remote 子模块+程序式 Feign 构建；Q3 新建 inner 契约文档；Q4 is_builtin 列 5 表；Q5 一刀切禁删禁改含停用+assignMenus 整体拒绝；Q6 错误码按域 5 码 3013-3017；Q8 admin 用户纳入防删防停）
- 需求原文：兑现上轮（5823fd9 字段统一翻译）移交备忘候选——① Provider 远程化（system 暴露数据端点 + 组件内置远程回源，其他服务引依赖即用）；② 内置角色/菜单/字典/admin 用户防删防改防停（错误码 3013+ 预留已两轮，本轮兑现）
- 范围：**cloud-common 新增 translate-remote 子模块 + cloud-system 两个 /inner 数据端点 + 5 表 is_builtin 列与保护校验 + e2e**；**不含** 4 页翻译铺开（另轮全栈）、内置数据 UI 强化（徽标/按钮禁用/builtin VO 字段）、真实跨服务集成验证（bpmn 阶段 4）
- 契约：`docs/superpowers/contracts/2026-10-07-inner-api.md`（服务间内部接口域 v1，新建）+ `docs/superpowers/contracts/2026-10-07-builtin-protection-api.md`（保护域 v1，新建，含对 pilot/dict/translation 三契约的声明性取代）
- 计划：`docs/superpowers/plans/2026-10-07-translate-remote-builtin-protection.md`

## 1. 需求与范围

| 项 | 结论 |
|---|---|
| 本轮做 | ① cloud-common-translate-remote-starter 新子模块（程序式 Feign 客户端 + 两远程 Provider + 自动装配，消费方零配置）；② cloud-system 新增 `GET /inner/user/all` + `GET /inner/dict/items/{dictKey}` 两内部端点（网关 /system/inner/** 屏蔽规则已有，零网关改动）；③ 5 表（sys_role/sys_menu/sys_dict_type/sys_dict_data/sys_user）is_builtin 列 + 种子 UPDATE 置 1 + 基线同步；④ 12 处保护校验（角色 update/delete/assignMenus、菜单 update/delete、字典类型 update/delete、字典项 update/delete、用户 delete/update 停用分支/assignRoles）+ 错误码 3013-3017；⑤ e2e 保护断言 + 六脚本回归 |
| 本轮不做 | 4 页翻译铺开（role/menu/dict 三页 status + 审计字段——机制就绪，与前端消费同轮另案）；内置 UI 强化（builtin 徽标/删除按钮禁用/VO additive builtin 字段——无前端消费方不做字段）；远程链路真实跨服务集成验证（无第二消费者，bpmn 阶段 4 届时做，记档）；回源失败负缓存（D3 明确取舍：不做） |
| 改动面 | cloud-common 聚合（+1 模块）；cloud-system（9202）代码 + DDL 增量，**重启一次**（验收阶段用户执行）；**网关 / sso / 前端零改动零重启**；cloud-bpmn 本轮不引入（阶段 4 接入即"加两个依赖"） |

## 2. 现状盘点（设计前提，2026-10-07 逐项核实）

- **Provider SPI 现状**：接口在 translate-starter（`provider/DictSourceProvider`/`UserSourceProvider`），实现在 cloud-system `service/translate/`（@Service 直读自家 mapper）；`TranslationCacheService` 经 `ObjectProvider.getIfAvailable()` 取实现——**无 bean 时缓存未命中返回空列表 + log.warn 一次**（译文降级 null），这就是其他服务引组件后"不可用"的现状
- **本地覆盖机制天然成立**：自动装配（@ConditionalOnMissingBean）在用户 bean 定义注册之后求值——system 的 @Service Provider 存在时远程 bean 不装配；system 不引入 remote 模块则远程装配根本不在 classpath（双保险）
- **Feign 先例与关键约束**：sso 的 `SystemUserClient`（@FeignClient + fallback，openfeign + loadbalancer 两依赖）；sso 用**裸 `@EnableFeignClients`（只扫 com.cloudai.sso 包）**——common 包里的 @FeignClient 接口在消费服务的默认扫描下**扫不到**，这是选择程序式构建的直接动因
- **网关屏蔽规则已有**：`/system/inner/**` → 403（application.yml inner-block-system），新 inner 端点天然被保护，本轮零网关改动（"首个 /inner 端点与屏蔽规则同任务落地"红线已由 phase3 满足，本轮纯增量）
- **mapper 查询复用面**：`SysDictDataMapper.listEnabledByDictKey(dictKey)`（消费口径 JOIN）与 `SysUserMapper.listTransAll()`（全量投影）即 inner 端点所需数据，**零新增 mapper 语句**（MapperXmlBindingTest 计数 46 不变）；五个 mapper 均用共享 `<sql id="allColumns">` 片段（findById/pageList 共用），is_builtin 补进片段即全覆盖
- **结构性防篡改已天然成立**：五个 mapper 的 INSERT（trim 动态列）与 UPDATE（set 动态列）列枚举**不含 is_builtin**——即使实体字段被赋值也不会写库，新增行落 DEFAULT 0，is_builtin 无 API 写入口
- **种子现状**：sys_role 仅 id=1（admin）；sys_menu 23 行种子（10/11/12/13/14/20/21/211/111-115/121-124/131-133/141-143）；sys_dict_type id=1（user_status）、sys_dict_data id=1/2；sys_user id=1（admin）。五者 create_by 均无统一标记（dict 有 'system'，role/menu/user 为 NULL）——统一判定需新列（拍板 Q4）
- **危险路径已核实**（阶段一实测）：登录聚合 JOIN 带 `r.status=0 AND r.deleted=0`——删或停 admin 角色 = 重登后全员无权限；角色 assignMenus 可清空绑定（效果同删）；菜单 update 可改 perms/type/path（破坏授权/路由映射）；字典类型可改 dictKey（打断 @DictTrans 注解引用）、可停用（消费口径过滤 = 翻译静默死亡）；删除 admin 用户 = 唯一账号消失
- **校验插入点零额外查询**：五域写操作均已 requireX（findById 加载实体）——保护校验读实体 isBuiltin 字段即可，搭便车零成本
- **守护测试影响**：ArchitectureGuardTest 扫 cloud-system 的 service/mapper——本轮改动全在其执法范围（新 controller javadoc/显式 @PathVariable/两行式自动受检）；common 新模块不受扫描但自律同规范；MapperXmlBindingTest 语句计数不变（零新增语句）
- **事务结构**：12 处保护校验全部是"读实体 + 抛异常"，落在既有方法开头——**零新增 @Transactional**（assignMenus/角色 delete/菜单 delete/用户 delete 等既有事务结构不动）

## 3. 架构设计（候选 1：Provider 远程化）

### 3.1 新子模块：cloud-common-translate-remote-starter（common 第 6 模块）

```
cloud-common/cloud-common-translate-remote-starter/
├── pom.xml                                    # translate-starter(compile) + openfeign(compile) + loadbalancer(compile)
├── src/main/java/com/cloudai/common/translate/remote/
│   ├── client/SystemTranslateClient.java      # @FeignClient(name="cloud-system", contextId="systemTranslateClient", path="/inner")
│   │                                          #   GET /dict/items/{dictKey} → R<List<DictItemEntry>>
│   │                                          #   GET /user/all            → R<List<UserEntry>>
│   ├── provider/RemoteDictSourceProvider.java # 实现 DictSourceProvider：转发 client，R 非 200 → 抛异常交缓存层降级
│   ├── provider/RemoteUserSourceProvider.java # 实现 UserSourceProvider：同上
│   └── config/CommonTranslateRemoteAutoConfiguration.java
└── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

- **程序式 Feign 构建**（拍板 Q2）：自动装配内以 `FeignClientBuilder`（spring-cloud-openfeign 4.x）创建客户端实例，不依赖消费方 `@EnableFeignClients` 扫描（common 包不在扫描范围，见 §2）——消费方**零配置，引依赖即用**
- **B5 实测结论（2026-10-07 已验证，主选通路走通、备选未启用）**：4.1.3 `FeignClientBuilder` **不读 @FeignClient 注解**（javadoc 明示 "without using the annotation"）——name/path 以 `.forType(type, "cloud-system").path("/inner")` 显式传参；超时经 `.customize(b -> b.options(new Request.Options(1, TimeUnit.SECONDS, 2, TimeUnit.SECONDS, true)))` 直设 feign `Request.Options`，customizer 在 properties 之后应用、**优先级最高（消费方 feign.client.config 不覆盖它）**；@FeignClient 注解保留为文档锚点
- **超时**：connect 1s / read 2s（内网小负载 RPC；默认超时数十秒不可接受——见 D3 故障窗口分析）
- **自动装配条件**：`@AutoConfiguration` + `@ConditionalOnClass(FeignClientBuilder.class)` + `@ConditionalOnProperty(name="cloud.translate.remote.enabled", havingValue="true", matchIfMissing=true)`（默认开，应急可关）；客户端 bean 与两 Provider bean 均 `@ConditionalOnMissingBean`——**本地 @Service Provider 优先**（system 即使误引本模块也不冲突）
- **降级复用组件现状**：Provider 不写 Feign fallback 类——`TranslationCacheService` 已有"回源异常 → log.error → 空列表 → 译文 null（不写缓存）"总纲，远程 Provider 的 R 非 200 / IO 异常统一走这条路（错误根因进日志）
- **依赖理由**：openfeign/loadbalancer 由本模块 compile 引入（模块存在的意义），版本随 cloud-base 根 pom dependencyManagement 收敛（sso 无版本号先例）；不设 optional（显式 opt-in 靠"引不引本模块"表达，拍板 Q2 甲案）

### 3.2 cloud-system 两个 /inner 数据端点

| 端点 | 实现 | 委托 |
|---|---|---|
| `GET /inner/user/all` | `InnerUserController` 扩展（既有 /inner/user/{account} 旁加 @GetMapping("/all")，精确路径优先于 /{account} 模板，Spring 匹配规则保证共存） | `UserSourceProvider`（既有 @Service，转发 `SysUserMapper.listTransAll()`） |
| `GET /inner/dict/items/{dictKey}` | 新建 `controller/feign/InnerDictController`（@RequestMapping("/inner/dict")） | `DictItemSourceProvider`（既有 @Service，转发 `SysDictDataMapper.listEnabledByDictKey()`） |

- 出参直接复用共享 DTO（DictItemEntry/UserEntry——common 类型，无守护豁免问题）；语义与本地回源**完全同源**（同一 Provider bean），保证"system 本地回源"与"他服务远程回源"看到同一份数据
- 无 @PreAuthorize（内网信任，/inner/user/{account} 先例）；javadoc + 显式 @PathVariable + 两行式返回；`/inner/user/all` 恒返回数组（含停用用户——审计字段翻译需要全量昵称），`/inner/dict/items/{dictKey}` 未知 dictKey → 200 空数组（消费口径同款容错）
- 契约归 inner 域新文档（拍板 Q3），并声明性收编既有 /inner/user/{account}

### 3.3 数据流（bpmn 阶段 4 接入视角——本轮不实现，验证形态）

```
bpmn 服务（未来）：引 translate-starter + translate-remote-starter 两依赖 → 自动装配
  GET /bpmn/xxx/page → VO 标 @TranslateVO/@DictTrans/@UserTrans
  → TranslateAdvisor 收集 → TranslationCacheService：
      1. 读 Redis trans:dict:{dictKey} / trans:user（共享 Redis，纯 JSON 跨服务直读——B7 已实证）
      2. 未命中 → RemoteProvider → Feign(1s/2s) → system /inner/** → DB 回源
      3. 结果写回同一 trans:* 键（与 system 本地回源写的键同键同形态，互为兜底）
      4. 回填译文
失效通道不变：system 写操作 DEL trans:*（全局生效，bpmn 下次读未命中 → 重新回源）
```

### 3.4 system 自身排除机制（拍板 Q2 补充）

1. **主通道**：cloud-system 的 pom **不引入** cloud-common-translate-remote-starter——远程装配不在 classpath，system 永远用本地 @Service Provider（直读 mapper，无网络跳数）
2. **双保险**：@ConditionalOnMissingBean 使本地 bean 优先——即使误引模块，装配结果仍是本地直读（不产生双 Provider 冲突）
3. 单测以 ApplicationContextRunner 验证条件装配逻辑（本地 bean 存在时远程不装配）——system 侧无需真实引入

## 4. 架构设计（候选 2：内置数据保护）

### 4.1 判定机制：is_builtin 列（拍板 Q4）

- 5 表各 `ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0 COMMENT '...'`（列位置 AFTER status，业务属性紧邻状态语义）；种子 5 条 UPDATE 置 1（幂等）；基线 cloud_system.sql 同步（列定义 + 种子 INSERT 显式带 is_builtin=1）
- **只读列设计**：五个 mapper 仅 allColumns 片段补 is_builtin（findById/pageList 共用）供实体加载；INSERT/UPDATE 列枚举不含此列（§2 结构性防篡改）——is_builtin 无任何 API 写入口，只能经 SQL 变更
- 实体侧：五实体加 `private Integer isBuiltin;`（Integer 映射）+ 嵌套 `BuiltinEnum{DEFAULT(0), BUILT_IN(1)}`（Enum 后缀合规 + Java 侧禁魔法数，backend-spec 步骤 1 同款）；BaseEntity 不动（非全表列，放各实体）
- **VO 零暴露**：pageList 虽多查出 is_builtin，Convert 不映射——契约零变化；UI 强化轮需要徽标时 additive 出 VO 字段（届时零额外改动，列已在）

### 4.2 保护粒度矩阵（拍板 Q5 一刀切 + Q8 用户域例外）

| 域 | 内置行 | 删除 | 修改 | 停用（status→1） | 说明 |
|---|---|---|---|---|---|
| 角色 | id=1（admin） | **禁**（3013） | **全禁**（3013）——name/roleKey/status 无论改什么都拒 | 含于禁改 | 一刀切 |
| 角色分配权限 | 同上 | — | **assignMenus 整体拒绝**（3013） | — | 清空绑定同删角色；种子绑新菜单走 SQL 惯例 |
| 菜单 | 23 行种子 | **禁**（3014） | **全禁**（3014）——name/icon/sort/path/perms/type/parentId/status | 含于禁改 | 一刀切（菜单"安全字段"仅 name/icon/sort，分级收益小规则面大，D6） |
| 字典类型 | id=1（user_status） | **禁**（3015，优先于 3011 项检查） | **全禁**（3015）——dictKey/dictName/status | 含于禁改 | dictKey 改动会打断 @DictTrans 注解引用 |
| 字典项 | id=1/2 | **禁**（3016） | **全禁**（3016）——value/label/sort/status | 含于禁改 | value 改动打断存量数据对齐 |
| 用户 | id=1（admin） | **禁**（3017） | **例外：仅禁停用**（3017）——nickname 可改、status=0 提交放行 | **禁**（3017） | Q8 拍板"防删+防停"；account 本就不可改；resetPassword 放行（admin 忘密码是合法运维） |
| 用户分配角色 | 同上 | — | **assignRoles 整体拒绝**（3017） | — | **拍板精神延伸**（Q5 assignMenus 同构：取消 admin 用户的角色绑定 = 无权限锁死）；见 D6 标注 |

- **校验顺序**：requireX（加载实体）→ isBuiltin 检查 → 既有校验链（空白/查重/父子等）——内置行的操作无论其余参数如何都先拒，一次交互拿到准确原因
- 实现：各 ManageService 内联三行校验（读实体 BuiltinEnum 常量比对，null-safe：`Integer.valueOf(BUILT_IN.getCode()).equals(entity.getIsBuiltin())`）+ 类内错误码常量；不建跨服务公共工具（避免 common 依赖业务实体）
- 判定语义放宽预告：将来放开某字段（如菜单改名）是向后兼容的 additive 变更（原本拒绝的操作变允许，对前端无破坏）——收紧难、放宽易，本轮取最简

### 4.3 7+2 处校验插入点（全部 Service 层）

| Service 方法 | 插入内容 |
|---|---|
| SysRoleManageService.update / delete / assignMenus | requireRole 后 assertBuiltin → 3013（msg 分别：内置角色禁止修改/删除/修改权限） |
| SysMenuManageService.update / delete | requireMenu 后 → 3014（禁止修改/删除） |
| SysDictTypeManageService.update / delete | requireType 后 → 3015（禁止修改/删除；delete 中先于 count 项检查） |
| SysDictDataManageService.update / delete | requireData 后 → 3016（禁止修改/删除） |
| SysUserManageService.delete | requireUser 后 → 3017（内置用户禁止删除） |
| SysUserManageService.update | requireUser 后：isBuiltin ∧ 请求 status==DISABLED → 3017（内置用户禁止停用）；status null/0 放行（nickname 正常更新） |
| SysUserManageService.assignRoles | requireUser 后 → 3017（内置用户禁止修改角色）——D6 延伸标注 |

## 5. DDL 与索引审查 + 事务口径（backend-spec 强制项）

**DDL 增量**（`scripts/sql/2026-10-07-builtin-protection.sql`，基线 cloud_system.sql 同步）：

```sql
-- 5 表同款（列语义随域微调 COMMENT）；幂等提示：ALTER 不幂等——执行前 SHOW COLUMNS 核对列不存在
ALTER TABLE sys_role
    ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0 COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建' AFTER status;
-- sys_menu / sys_dict_type / sys_dict_data / sys_user 同款（sys_user COMMENT：'内置标记：1=系统内置（禁删禁停用，昵称可改），0=用户创建'）

-- 种子置 1（UPDATE 天然幂等；菜单 id 清单与基线 sys_menu INSERT 逐一对齐，共 23 行）
UPDATE sys_role      SET is_builtin = 1 WHERE id = 1;
UPDATE sys_menu      SET is_builtin = 1 WHERE id IN (10,11,12,13,14,20,21,211,111,112,113,114,115,121,122,123,124,131,132,133,141,142,143);
UPDATE sys_dict_type SET is_builtin = 1 WHERE id = 1;
UPDATE sys_dict_data SET is_builtin = 1 WHERE id IN (1,2);
UPDATE sys_user      SET is_builtin = 1 WHERE id = 1;
```

**索引审查**：is_builtin 不进任何 WHERE/JOIN/ORDER BY——校验经 findById 主键路径（搭既有便车），pageList 不按内置过滤，inner 端点复用既有查询（上轮 EXPLAIN 已审）。**零索引增量**。`is_builtin` 也不值得单建索引（基数 0/1 选择性极差，backend-spec"不建"清单同款理由）。

**事务口径**：保护校验全部"读 + 抛"，落在既有方法开头；既有事务结构（assignMenus/各 delete 的 @Transactional）不动，**零新增 @Transactional**——满足"只挂多写语句方法"口径（本轮无新多写方法）。

## 6. 错误处理与降级（远程路径全景）

| 场景 | 行为 | 结果 |
|---|---|---|
| system 不可达 / Feign 超时（connect 1s / read 2s） | Provider 抛异常 → 缓存层 catch + log.error → 空列表，**不写缓存** | 译文 null 降级（前端降级链既有）；故障窗口内每次未命中付一次超时（见 D3） |
| system 返回 R.fail（非 200） | Provider 转 IllegalStateException → 同上 | 同上 |
| inner 端点返回空数组（未知 dictKey/无启用项） | 合法空数据 → 正常写缓存 TTL 30min | 空列表与本地回源同语义 |
| Redis 不可用 / Advisor 异常 | 组件既有降级总纲不变 | 原样返回 body |
| 内置行写操作 | BusinessException 3013-3017（HTTP 200 + body code） | 前端拦截器 toast（既有机制零改动） |

## 7. 测试策略

### 7.1 remote 子模块单测（构建期，mock client，无真实 Feign/Redis）

- RemoteDictSourceProvider / RemoteUserSourceProvider：client 正常 R.ok → 转发数据；R.fail → 抛异常（verify 交缓存层降级）；data=null → 空/异常口径一致
- 自动装配条件（ApplicationContextRunner）：无本地 Provider → 远程 bean 装配；注册本地 DictSourceProvider bean → 远程对应 bean 不装配（@ConditionalOnMissingBean 优先级）
- `R<List<UserEntry>>` JSON 往返：Long→String 序列化形态下 id="1" 反序列化回 Long（Jackson coercion，实现时验证）——锁跨服务 DTO 形态

### 7.2 cloud-system 单测（Mockito mock mapper，既有范式）

- 保护分支：五域 9 个校验点逐一 verify BusinessException code+msg（含用户 update status=0 放行、status=1 拒；resetPassword 不受保护影响）
- **既有单测零破坏论证**：既有用例 mock findById 返回的实体 isBuiltin 为 null——null-safe 比对不触发保护，全部照旧绿（计划明示回归验证）
- inner 端点：controller 委托 Provider 的转发 verify（javadoc/@PathVariable 合规由 ArchitectureGuardTest 机械保证）

### 7.3 后端 curl 验收（B7 卡点，经网关 + 直连 9202）

- 保护矩阵 12 条：DELETE /system/role/1 → 3013；PUT /system/role（id=1）→ 3013；PUT /system/role/menu → 3013；菜单 PUT/DELETE（种子 id，如 10）→ 3014；字典类型 PUT/DELETE（id=1）→ 3015；字典项 PUT/DELETE（id=1）→ 3016；DELETE /system/user/1 → 3017；PUT /system/user（id=1, status=1）→ 3017、（id=1, nickname 改, status=0）→ 200
- inner 直连（**不走网关**）：`GET localhost:9202/inner/dict/items/user_status` → 数组两项；`GET localhost:9202/inner/user/all` → 含 admin
- **网关屏蔽实证**：`GET localhost:18080/system/inner/user/all` → HTTP 403（设计行为，须记录在案）
- 翻译回归：`GET /system/user/page` statusLabel/createByName 正常（本地 Provider 路径不受影响）
- 非内置行冒烟：e2e 造的数据删/改照常（保护只拦内置行）

### 7.4 黑盒 e2e（计划 E 章）

- 四脚本各加内置保护场景：run-role-e2e（对 admin 行删除/编辑/分配权限 → toast 含"内置角色禁止" + 行仍在）、run-menu-e2e（对种子菜单删除/编辑 → 3014 toast + 仍在）、run-dict-e2e（user_status 类型/项删改 → 3015/3016 toast + 仍在）、run-e2e（admin 用户删除/停用编辑 → 3017 toast + 仍在）
- 六脚本全量回归：**先逐脚本 grep 种子操作点复核**（既有场景只操作自造数据——scaffold/nav 脚本不涉 system 管理写端点），复核清单进计划任务

## 8. 设计决策（D 系列）

### D1 远程形态 = 独立 remote 子模块 + 程序式 Feign 构建（拍板 Q2）

- 弃"单 starter + openfeign optional + @ConditionalOnClass"（乙）：消费方要自己补 openfeign+loadbalancer 两依赖、条件装配有类加载顺序细节，排障面大；弃"Feign compile 进主 starter 作默认 Provider"（丙）：违背上轮 D2 边界理由（system 被迫带用不上的 feign）
- 程序式构建（FeignClientBuilder）绕开 @EnableFeignClients 包扫描限制（common 包不在消费方扫描范围，§2 实证）——消费方零配置；注解接口仍标 @FeignClient（**实测 Builder 不读注解元数据**——注解保留为文档锚点，name/path 显式传参；超时通路见 §3.1 实测结论）
- 不写 Feign fallback 类：降级总纲已在缓存层（异常 → 空 → null），fallback 类是重复的一层

### D2 system 排除 = 不引模块（主通道）+ @ConditionalOnMissingBean（双保险）（拍板）

- system 永远本地直读 mapper（无网络跳数、无 Feign 超时面）；即使误引 remote 模块，本地 @Service bean 优先，行为不变。单测以 ApplicationContextRunner 锁条件逻辑

### D3 回源失败 = 短超时 + 不做负缓存（主控点名的方案期决策）

- **不做负缓存**（含"独立 neg: 短 TTL 键"变体）：① 空列表是合法数据形态（类型无启用项），负缓存若写正键会污染语义（把"system 暂时故障"误当"无数据"缓存 30min）；② neg 键方案语义干净但引入第二键契约 + 读路径多一次 Redis 查（仅未命中时），复杂度与收益不匹配——回源本就低频（TTL 30min + 写操作 DEL 主通道下，正常运行几乎全缓存命中）
- **代价记档**：system 宕机窗口内，"缓存未命中 ∧ 回源失败"的请求每次付 Feign 超时（connect 1s + read 2s，dict/user 串行各一，最坏数秒级响应劣化）——仅此窗口出现，Advisor 有全程 catch 兜底不炸响应；短超时把单次上限压到秒级
- 真实运行若此窗口延迟不可接受：neg 键是 additive 演进（不动现有键形态与契约），记档移交

### D4 inner 端点 = 委托既有 Provider bean，零新增 mapper 语句；契约立 inner 域新文档（拍板 Q3）

- inner 端点与本地回源同一数据源同一口径（同一 @Service），杜绝"两条回源路径数据口径漂移"；mapper 零新增（MapperXmlBindingTest 计数 46 不变）
- 新建 inner 契约文档并声明性收编 /inner/user/{account}（原文不回改）：inner 是后端跨服务契约，与"前后端对齐物"性质不同；bpmn 阶段 4 还有 inner 端点，先立域

### D5 内置判定 = is_builtin 只读列（拍板 Q4）

- 弃 id 白名单（魔法 id 入代码、每加种子改代码）；弃 create_by='system' 复用（审计语义与内置语义混用、契约层无法一等表达，且 role/menu/user 种子也要补 UPDATE，省不了多少）
- **只读防线是结构性的**：INSERT/UPDATE 列枚举不含 is_builtin——无 API 写入口，防篡改不靠校验靠"根本写不进"；VO 零暴露（UI 强化轮 additive 出字段，列与数据已备好）
- 只补 allColumns 一处（findById/pageList 共用片段）——最小改动面

### D6 保护粒度 = 一刀切 + 用户域例外 + 绑定操作整体拒绝（拍板 Q5/Q8；一处延伸明示）

- 一刀切理由：菜单"安全字段"仅 name/icon/sort（perms/type/path/status/parentId 全敏感），分级矩阵收益小、规则面与测试面大；错误码/断言/契约文本最简；将来放宽是 additive（收紧难放宽易）
- 用户域例外（Q8 拍板字面）：仅禁删+禁停，nickname 可改，resetPassword 放行——admin 改昵称与重置密码是合法运维
- **延伸标注（供主控确认）**：`SysUserManageService.assignRoles` 对内置用户整体拒绝未在拍板字面内，但与已拍板的"admin 角色 assignMenus 整体拒绝"完全同构（取消 admin 用户的角色绑定 = 无权限锁死，对称漏洞）。按拍板精神一致性纳入（3017），如不认可可单独剔除——文档与计划中该点独立成条便于剔除
- 种子绑新菜单/新角色给 admin 走 SQL（既有惯例：sys_role_menu INSERT ... SELECT）——API 层拒绝不影响种子通路

### D7 错误码 3013-3017 按域分配（拍板 Q6）；账本接续

- 3013 角色 / 3014 菜单 / 3015 字典类型 / 3016 字典项 / 3017 用户，msg 带具体操作（禁止删除/禁止修改/禁止修改权限/禁止停用）——按域分段与账本惯例一致，e2e/单测按 code 断言
- 保护契约成为 3xxx 账本新权威：3013-3017 占用，**3018 起预留给 bpmn 域（阶段 4）**；对 dict-api §5（至 3012）与 translation-api §4（3013+ 预留声明）的取代关系在保护契约 §0 声明性记录

### D8 校验形态 = requireX 后内联三行，null-safe 比对

- 校验先于既有校验链（一次交互拿到准确原因）；null-safe 比对保证既有单测（mock 实体 isBuiltin=null）零破坏；各 Service 类内错误码常量 + 实体内嵌 BuiltinEnum 常量引用，无魔法数

### D9 改动面收敛

- 网关/sso/前端零改动零重启；system 一次重启；common 新模块只增不改（translate-starter 零触碰——远程化全部增量在 remote 子模块，SPI/缓存/Advisor 一行不动）；bpmn 本轮不动

## 9. 已知取舍与移交备忘（下一阶段规划前必读）

1. **远程链路未做真实跨服务集成验证**：无第二消费者——单测（mock client + ApplicationContextRunner）+ inner 端点 curl 直连实证；bpmn 阶段 4 接入时补真实链路验收（届时"加两依赖"即完成接入）
2. **FeignClientBuilder 通路已验证记档（B5 落地）**：4.1.3 Builder 不读 @FeignClient 注解——name/path 显式传参（`.forType().path()`）；超时经 `.customize` 直设 `Request.Options(1s/2s)`，customizer 优先级最高（消费方 `feign.client.config` **无法覆盖** starter 内建超时——bpmn 接入若需调超时：自建 client bean 覆盖，或 `cloud.translate.remote.enabled=false` 关远程回源）
3. **故障窗口响应劣化**（D3）：system 宕机 + 缓存未命中时最坏数秒级（1s/2s 超时 × 串行两类回源）；neg 负缓存为 additive 后手
4. **4 页翻译铺开**（候选 3）与**内置 UI 强化**（builtin 徽标/按钮禁用/VO additive builtin 字段）：另轮全栈——两者可同轮（都动 role/menu/dict 页面）
5. **种子惯例**：后续新增内置菜单/字典/角色种子，SQL 记得带 is_builtin=1（基线与增量都写）；admin 角色绑新菜单继续走 sys_role_menu SQL 通路
6. **assignRoles 保护为拍板延伸**（D6）：若用户不认可，剔除该条不影响其余（独立三行校验）
7. **放宽路径**：任何"内置行放开某字段"的未来需求均为 additive 契约变更（拒绝变允许），方向安全
