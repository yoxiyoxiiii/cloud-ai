# 翻译 Provider 远程化 + 内置数据保护实施计划（纯后端：后端章 + e2e 章）

- 日期：2026-10-07
- 需求：① 翻译 Provider 远程化（system /inner 数据端点 + translate-remote 子模块，消费方引依赖即用）；② 内置角色/菜单/字典/admin 用户防删防改防停（is_builtin + 3013-3017）
- 设计：`docs/superpowers/specs/2026-10-07-translate-remote-builtin-protection-design.md`（D1-D9；重点：D3 负缓存不做+短超时 1s/2s、D5 is_builtin 只读列结构性防篡改、D6 一刀切+用户域例外+assignRoles 延伸标注）
- 契约：`2026-10-07-inner-api.md`（inner 域 v1 新建）+ `2026-10-07-builtin-protection-api.md`（保护域 v1 新建，3xxx 账本新权威至 3017、3018+ 预留 bpmn）
- 轨道：**纯后端**——后端章 B1→B7（单 backend-agent 串行）+ e2e 章 E1-E3（B7 后）；**前端/网关/sso 零改动零重启**，cloud-system 重启一次（B7 用户执行）

```
主控：① 派发 backend-agent（B1→B7 串行）
      ② B1 落库 + B7【卡点】用户重启 9202 + curl 验收通过
      ③ e2e（E1 保护断言 + E2 六脚本回归）
      ④ 双审（契约逐条 + quality）→ 修复循环 → 合并 main
```

- 移交备忘前提（设计 §9）：远程链路真实跨服务验证推迟 bpmn 阶段 4；FeignClientBuilder API 实现时验证；候选 3（4 页铺开）与 UI 强化另轮全栈

## 后端章（cloud-base/，backend-agent）

> 规范来源：CLAUDE.md 编码规范 + `/backend-spec` 技能（**索引：本轮零增量——is_builtin 不进任何查询条件，设计 §5 已裁定；事务：零新增 @Transactional**）。ArchitectureGuardTest 对 cloud-system 全执法（BuiltinEnum 后缀/新 controller javadoc/@PathVariable/两行式自动受检）；common 新模块不扫描但自律同规范；MapperXmlBindingTest 语句计数 **46 不变**（本轮零新增 mapper 语句）。

### B1 DDL 增量（5 表 is_builtin + 种子 UPDATE + 执行落库）

- 文件：
  - `cloud-base/scripts/sql/2026-10-07-builtin-protection.sql`（新建，增量）
  - `cloud-base/scripts/sql/cloud_system.sql`（改，基线同步：5 表 CREATE TABLE 各补 is_builtin 列定义（AFTER status，含 COMMENT）；种子 INSERT 显式带 is_builtin=1——sys_role 1 行、sys_menu 23 行、sys_dict_type 1 行、sys_dict_data 2 行、sys_user 1 行）
- 增量脚本内容（保护契约 §3 全文照录）：
  - 头注释：幂等提示（**ALTER 不幂等——执行前经回查通道核对 5 表均无 is_builtin 列**；UPDATE 天然幂等）；基线已同步说明；红线：不动其他列与数据
  - 5 条 ALTER（TINYINT NOT NULL DEFAULT 0 + COMMENT + AFTER status；sys_user 的 COMMENT 用用户域文案"禁删禁停用，昵称可改"）
  - 5 条 UPDATE（角色 id=1；菜单 23 id 清单与基线 INSERT 逐一对齐；字典类型 id=1；字典项 id=1,2；用户 id=1）
- 执行通路：java 单文件源码 + mysql-connector-j（B1 惯例；临时文件置 scripts 外用后即删）
- 验收（同通路回查）：5 表各恰有 is_builtin 列（DEFAULT 0）；`SELECT COUNT(*) WHERE is_builtin=1` = sys_role 1 / sys_menu 23 / sys_dict_type 1 / sys_dict_data 2 / sys_user 1；其余行（e2e 遗留数据）is_builtin 全 0；**本任务不起停任何服务**

### B2 实体 + mapper：is_builtin 只读列接入

- 文件（改 10 个）：
  - `entity/SysRole.java` / `SysMenu.java` / `SysDictType.java` / `SysDictData.java` / `SysUser.java`：各 +`private Integer isBuiltin;` + 嵌套 `BuiltinEnum{DEFAULT(0), BUILT_IN(1)}`（Enum 后缀 + code 字段 + getCode + of(code)，StatusEnum 同款样板；**字段保持 Integer 映射**）
  - `resources/mapper/SysRoleMapper.xml` / `SysMenuMapper.xml` / `SysDictTypeMapper.xml` / `SysDictDataMapper.xml` / `SysUserMapper.xml`：各 allColumns 片段补 `is_builtin`（findById/pageList 共用，一处即全）
- **红线：INSERT/UPDATE/deleteById 语句零改动**——列枚举不含 is_builtin（结构性防篡改，设计 D5）；新增行落 DEFAULT 0
- 验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 编译过；MapperXmlBindingTest 绑定绿（计数 46 不变——核对注释算式仍 10+7+7+4+4+6+8=46）；ArchitectureGuardTest 绿（BuiltinEnum 后缀合规）；既有全部单测绿（mock 实体 isBuiltin=null 不触发保护）

### B3 保护校验（12 处 = 契约 §2 矩阵全量 + 错误码常量 + 单测）

- 文件（改 5 个 Service + 5 个 ServiceTest）：
  - `SysRoleManageService.java`：+`ERR_BUILTIN = 3013` 常量；update/delete/assignMenus 三方法在 requireRole 后加内联校验（`Integer.valueOf(SysRole.BuiltinEnum.BUILT_IN.getCode()).equals(role.getIsBuiltin())` → `throw new BusinessException(3013, "内置角色禁止修改/删除/修改权限")`——msg 按方法区分）；
  - `SysMenuManageService.java`：update/delete 同款 → 3014（内置菜单禁止修改/删除）；**校验插入在 requireMenu 之后、既有校验（name 空白/parentId 环检查/count 子级）之前**
  - `SysDictTypeManageService.java`：update/delete 同款 → 3015；**delete 中先于 count 项检查（3011）**
  - `SysDictDataManageService.java`：update/delete 同款 → 3016
  - `SysUserManageService.java`：delete → 3017（内置用户禁止删除）；update → isBuiltin ∧ `UserSaveRequest.status` 为 `SysUser.StatusEnum.DISABLED` → 3017（内置用户禁止停用），status null/0 放行（nickname 正常更新）；assignRoles → 3017（内置用户禁止修改角色）——**此条为拍板延伸（设计 D6/契约 §5.2），实现为独立三行校验便于单独剔除**
- 实现约束：校验内联三行、null-safe 比对（D8）；不建跨服务公共工具；**零新增 @Transactional**（校验为读+抛，落既有方法开头）；方法行数不超限（三行校验无风险）
- 单测（五个 ServiceTest 增补）：9 个校验点逐一 verify code+msg（mock findById 返回 isBuiltin=1 的实体）；放行路径：用户 update status=0/nickname 改 → 正常走 mapper；resetPassword 对内置用户放行；非内置（isBuiltin=0/null）全部照旧——**既有用例零改动应全绿**（回归验证项）
- 验收：cloud-system 单测全绿；`grep` 核对九处校验齐全（对照契约 §2 矩阵逐条）

### B4 inner 数据端点（cloud-system，两个）

- 文件：
  - `controller/feign/InnerUserController.java`（改）：+`@GetMapping("/all")` → `R<List<UserEntry>>`，javadoc（全量投影含停用用户、供翻译远程回源）、两行式、注入 `UserSourceProvider`（既有 @Service）
  - `controller/feign/InnerDictController.java`（新建）：`@RequestMapping("/inner/dict")` + `@GetMapping("/items/{dictKey}")` → `R<List<DictItemEntry>>`，显式 `@PathVariable("dictKey")`、javadoc、注入 `DictSourceProvider`
  - 无 @PreAuthorize（内网信任，/inner/user/{account} 先例）；**零新增 mapper 语句/Service**（直接委托既有 Provider bean——Controller→Service 分层满足，Provider 即 service 层 @Service）
- 单测：两 controller 委托转发 verify（mock Provider）；未知 dictKey → Provider 返回空列表（口径在 mapper，单测只验转发）
- 验收：构建绿；ArchitectureGuardTest 绿（javadoc/显式命名/两行式）；inner 契约 §2.2/§3.1 出入参与实现一致

### B5 translate-remote 子模块（common 第 6 模块，全新）

- 文件（全新增，结构见设计 §3.1）：
  - `cloud-common/pom.xml`（改：modules 追加）
  - `cloud-common/cloud-common-translate-remote-starter/pom.xml`：translate-starter(compile) + `spring-cloud-starter-openfeign`(compile) + `spring-cloud-starter-loadbalancer`(compile)（版本随根 pom dependencyManagement，sso 无版本号先例）；test 依赖 spring-boot-starter-test 沿兄弟模块
  - `remote/client/SystemTranslateClient.java`：`@FeignClient(name="cloud-system", contextId="systemTranslateClient", path="/inner")` + 两方法（GET /dict/items/{dictKey} → `R<List<DictItemEntry>>`、GET /user/all → `R<List<UserEntry>>`，@PathVariable 显式命名）；**javadoc 注明程序式构建、不经 @EnableFeignClients 扫描**
  - `remote/provider/RemoteDictSourceProvider.java` / `RemoteUserSourceProvider.java`：实现 common 两 SPI——转发 client；`r == null || r.getCode() != 200` → `throw new IllegalStateException`（交缓存层统一降级 log.error）；data null → 空列表
  - `remote/config/CommonTranslateRemoteAutoConfiguration.java`：`@AutoConfiguration` + `@ConditionalOnClass(FeignClientBuilder.class)` + `@ConditionalOnProperty(name="cloud.translate.remote.enabled", havingValue="true", matchIfMissing=true)`；@Bean systemTranslateClient（程序式构建，**connect 1s / read 2s**——Builder options 定制 API **实现时验证**；不通则备选 @FeignClient(configuration=) 或文档化消费方 yml `feign.client.config`，回报所选通路）；两 Provider @Bean 均 `@ConditionalOnMissingBean(具体 SPI 接口)`（本地优先双保险）
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- 单测：
  - Provider：mock client——R.ok 转发；R.fail 抛 IllegalStateException；data=null 空列表
  - `CommonTranslateRemoteAutoConfigurationTest`（ApplicationContextRunner）：无本地 bean → 远程两 Provider 装配；注册本地 DictSourceProvider/UserSourceProvider bean → 远程对应 bean 不装配（条件优先级锁死）
  - JSON 往返：`R<List<UserEntry>>` 中 id="1"（Long→String 形态）反序列化回 Long（Jackson coercion）——锁跨服务 DTO 形态（实现时验证，若 coercion 被全局禁用则调整 UserEntry 反序列化注解，回报）
- 验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-translate-remote-starter -am` 全绿；**cloud-system/pom.xml 零改动**（system 不引本模块——设计 D2 主通道）；translate-starter 主模块零触碰

### B6 全量构建 + 守护核对

- 命令：`$MVN -f cloud-base/pom.xml clean install`（全模块，Windows 绝对路径 MVN）
- 核对：MapperXmlBindingTest 计数 46 注释算式不变；ArchitectureGuardTest 全绿；既有 60+ 单测 + 本轮新增全绿；surefire 3.2.5 生效（假绿防线）
- 验收：BUILD SUCCESS 全绿

### B7【卡点】重启 9202 + curl 验收（请用户执行重启）

- 前置：B1 已落库、B6 全绿；用户重启 cloud-system（9202）——网关/sso 不动
- curl 清单（token 经网关登录获取；**inner 直连不走网关**）：
  1. 保护矩阵 12 条（保护契约 §2/§4）：`DELETE /system/role/1`→3013；`PUT /system/role`(id=1)→3013；`PUT /system/role/menu`(roleId=1)→3013；`PUT /system/menu`(id=10)→3014；`DELETE /system/menu/10`→3014；`PUT /system/dict/type`(id=1)→3015；`DELETE /system/dict/type/1`→3015（**先于 3011**——user_status 有项，旧路径会 3011，新路径须 3015）；`PUT /system/dict/data`(id=1)→3016；`DELETE /system/dict/data/1`→3016；`DELETE /system/user/1`→3017；`PUT /system/user`(id=1,status=1)→3017；`PUT /system/user/role`(内置用户)→3017
  2. 放行冒烟：`PUT /system/user`(id=1, nickname 改后复原或改值, status=0)→200；`PUT /system/user/password/1` 重置 admin 密码（改回原值或告知用户新密码）→200；非内置行（e2e 造的或新造）删/改→200
  3. inner 直连 9202：`GET localhost:9202/inner/dict/items/user_status` → code 200 data 两项（value 0/1）；`GET localhost:9202/inner/user/all` → 含 admin 投影（id 字符串形态）；`GET localhost:9202/inner/dict/items/nonexist` → 200 空数组
  4. **网关屏蔽实证**：`GET localhost:18080/system/inner/user/all` → HTTP 403（设计行为，记录在案）
  5. 翻译回归：`GET /system/user/page` → rows[0] statusLabel/createByName 正常（本地 Provider 路径不受 B5 影响）
- 注意：Git Bash curl 中文 JSON GBK 陷阱——请求体全 ASCII；响应中文正常断言
- 验收：清单逐条通过并记录（含 403 实证）；发现问题回报主控（不自行改契约）

## e2e 章（cloud-e2e/，B7 后执行）

### E1 四脚本内置保护断言（新增场景）

- 文件（改 4 个）：`run-e2e.mjs`（+admin 用户保护）、`run-role-e2e.mjs`（+admin 角色保护）、`run-menu-e2e.mjs`（+种子菜单保护）、`run-dict-e2e.mjs`（+user_status 类型/项保护）
- 场景模式（每脚本同构）：对内置行触发写操作 → 断言错误 toast 出现（含"内置…禁止"文案）→ 断言行仍在表格（未被删/未被改）；覆盖点：
  - run-e2e：admin 行删除确认 → 3017 toast + 行在；编辑弹窗 status 选停用提交 → 3017 toast（**admin 昵称编辑放行路径不在种子行上断言**——避免污染种子）
  - run-role-e2e：admin 行删除 → 3013；编辑提交 → 3013；分配权限保存 → 3013
  - run-menu-e2e：任一种子菜单（如"用户管理"）删除 → 3014；编辑提交 → 3014
  - run-dict-e2e：user_status 类型删除 → 3015（toast 文案是 3015 不是 3011 也是断言点）；项删除 → 3016
- 纪律：**内置行操作一律"尝试后验证拒"，不得让任何断言路径真正改掉种子**；toast 断言失败信息含实际文案
- 验收：四脚本新场景全过；种子行最终状态与初始一致（脚本尾断言 admin/菜单数/user_status 项数）

### E2 种子操作点复核 + 六脚本全量回归

- 前置复核（E1 前做）：逐脚本 grep 既有场景对种子 id/名称（admin、用户管理、user_status 等）的写操作点，确认既有场景只操作自造数据（scaffold/nav 脚本预期不涉 system 管理写端点）——复核清单（脚本 × 结论）作为 E2 产出记录；发现碰种子的既有场景 → 回报主控裁定（保护生效会使其红——属契约变更范畴，修脚本不属私改）
- 回归：`cd cloud-e2e && npm run e2e`（有头全量六脚本）；翻译断言（S13/D5）照旧绿
- 验收：六脚本全绿 + 复核清单在案

### E3 文档同步核对

- 核对：本计划/两契约/设计三文档与实现一致（错误码 msg 文案逐字、菜单 23 id 清单、DDL COMMENT）；`docs/superpowers/` 无遗留 TODO
- 验收：无差异或差异已回报主控修订

## 移交后续阶段的备忘

1. **bpmn 阶段 4 接入**：cloud-bpmn 加 translate-starter + translate-remote-starter 两依赖即获得翻译能力（零配置）——届时补真实跨服务 Feign 链路验收（本轮仅有单测 + inner 直连 curl，设计 §9.1）
2. **FeignClientBuilder 通路记档（B5 已验证）**：4.1.3 Builder 不读 @FeignClient 注解（name/path 经 `.forType().path()` 显式传参）；超时经 `.customize(b->b.options(new Request.Options(1s,2s,true)))` 直设，customizer 优先级最高——消费方 `feign.client.config` 不覆盖；@FeignClient 注解保留为文档锚点
3. **候选 3（4 页翻译铺开）+ 内置 UI 强化（builtin 徽标/按钮禁用/VO additive 字段）**：另轮全栈——两者都动 role/menu/dict 三页，建议同轮
4. **种子惯例**：后续内置种子 SQL 带 is_builtin=1（基线与增量）；admin 角色绑新菜单继续走 sys_role_menu SQL
5. **assignRoles 保护为拍板延伸**（设计 D6）：主控/用户如不认可，剔除 `SysUserManageService.assignRoles` 内三行校验 + 契约 §2/§5.2 对应行即可，其余零牵连
6. **故障窗口响应劣化**（D3）：system 宕机 + 缓存未命中时最坏数秒级（1s/2s 超时串行）；不可接受时 neg 负缓存 additive 演进
7. **历史环境治理**：is_builtin UPDATE 只对存活种子行生效——已被删/改的历史种子不自动恢复，治理另案
