# 字段统一翻译实施计划（全栈：后端章 ∥ 前端章 + e2e 章）

- 日期：2026-10-07
- 需求：字典含义/用户名称统一翻译（注解 + ResponseBodyAdvice + Redis 缓存）+ 字典消费端点 + 用户页样板
- 设计：`docs/superpowers/specs/2026-10-07-field-translation-design.md`（D1-D10；**设计规则 0 红线：翻译绝不覆盖原字段**）
- 契约：`docs/superpowers/contracts/2026-10-07-translation-api.md`（翻译域现行版；pilot user 契约 additive 声明；错误码零新增）
- 并行编排：后端章（B1→B7）与前端章（F1→F3）**无文件交集、可同时派发**；F3 联调与 e2e 章依赖 B7 完成（此前按契约 mock）

```
主控：① 同消息并行派发 backend-agent（B1→B7）+ frontend-agent（F1→F3）
      ② B1 落库 + B7【卡点】用户重启 9202 + curl 验收通过 → 前端 dev 指 18080 真实联调（F3 复验）
      ③ 前端-agent 跑 E1-E3（S13/D5 新场景 + 六脚本全量回归）+ 截图视觉核对
      ④ 双审（契约逐条 + quality）→ 修复循环 → 合并 main
```

- 既有断言兼容性前提（设计 §2 已核实）：user_status 种子 label 文案 = 前端现有硬编码（"正常"/"停用"）→ run-e2e.mjs 状态列断言零改动；创建人列无文本值断言 → admin→"管理员" 不破坏

## 后端章（cloud-base/，backend-agent）

> 规范来源：CLAUDE.md 编码规范 + `/backend-spec` 技能（**索引设计：新查询路径必 EXPLAIN 审查；事务口径：本轮零新增 @Transactional**）。ArchitectureGuardTest 对 common 模块不扫描，但 common 新代码自律同规范（方法动词集/两行式语义/javadoc）；cloud-system 改动全在其执法范围。

### B1 种子 SQL（user_status 内置字典 + 执行落库）

- 文件：
  - `cloud-base/scripts/sql/2026-10-07-translation.sql`（新建，增量）
  - `cloud-base/scripts/sql/cloud_system.sql`（改，基线同步：种子 INSERT 追加到字典种子段）
- 内容：
  - 增量脚本头注释（红线：不动既有表结构与数据；幂等提示：INSERT 显式 id 不幂等——执行前 `SELECT id FROM sys_dict_type WHERE id=1` 与 `SELECT id FROM sys_dict_data WHERE id IN (1,2)` 均应 0 行；基线已同步，新环境直接跑基线无需本增量）
  - 语句体（设计 D7，**label 文案锁定契约 §0.3——与前端现有文案逐字一致**）：

```sql
INSERT INTO sys_dict_type (id, dict_name, dict_key, status, create_by, create_time, update_by, update_time) VALUES
(1, '用户状态', 'user_status', 0, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (id, dict_type_id, label, value, sort, status, create_by, create_time, update_by, update_time) VALUES
(1, 1, '正常', '0', 1, 0, 'system', NOW(), 'system', NOW()),
(2, 1, '停用', '1', 2, 0, 'system', NOW(), 'system', NOW());
```

  - create_by='system'（内置标记，区别人工 admin 操作）；**不进 sys_menu**（无新权限，契约 §0.3）
  - 执行通路：java 单文件源码 + mysql-connector-j（B1 惯例，jar 取 ~/.m2，版本实现时验证；临时文件置 scripts 外用后即删）
- 验收（同通路回查）：sys_dict_type 恰 +1 行（id=1, user_status, status=0）、sys_dict_data 恰 +2 行（label 正常/停用, value 0/1, sort 1/2）；sys_menu/sys_user 行数不变；**本任务不起停任何服务**

### B2 翻译基建 starter（cloud-common-translate-starter，全新模块）

- 文件（全新增，结构见设计 §3.1）：
  - `cloud-common/pom.xml`（改：modules 追加 cloud-common-translate-starter）
  - `cloud-common/cloud-common-translate-starter/pom.xml`（依赖：core-starter compile / redis-starter compile / spring-boot-starter-web **provided**——security-starter 先例；test 依赖 spring-boot-starter-test沿兄弟模块）
  - 源码：annotation（TranslateVO/DictTrans/UserTrans）、domain（DictItemEntry/UserEntry——共享 DTO，UserEntry.id 序列化为字符串）、provider（DictSourceProvider/UserSourceProvider 接口）、core（TranslateAdvisor/TransFieldScanner/TranslationCacheService/TranslateJson）、config（CommonTranslateAutoConfiguration：@AutoConfiguration + imports + @ConditionalOnMissingBean，装配 Advisor 与 CacheService；Provider 为 @ConditionalOnBean 缺省不装——无实现时缓存未命中即返回空，记 log.warn 一次）
  - 单测：TranslateAdvisorTest / TransFieldScannerTest / TranslationCacheServiceTest / TranslateJsonTest（mock RedisTemplate，用例清单见设计 §7.1）
- 关键实现约束：
  - **设计规则 0（红线）**：Advisor 只写 labelField 配对字段，**绝不写原字段**；TransFieldScanner 解析 labelField 时校验目标字段存在且为 String 且可写——否则该字段元数据作废 + log.warn 跳过（不抛错）
  - supports() 泛型解析结果按 Method 缓存；beforeBodyWrite 深度上限 3 + IdentitySet 防环 + 仅 @TranslateVO 类实例下钻；全程 try/catch Throwable → log.error → 原 body
  - 缓存值 = 手写 JSON String（TranslateJson 静态 ObjectMapper 配 Long→String）；RedisTemplate opsForValue 读写；TTL 30 分钟（常量可被 @ConditionalOnMissingBean 的自定义 CacheService 覆盖）
  - 方法命名自律动词集：find/list/delete 前缀（findDictLabels/listDictItems/findUserNames/deleteDict/deleteUsers）
- 验收：`mvn -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-translate-starter -am` 全绿；**TranslateAdvisorTest 含守护用例：翻译前后原字段（status/createBy/updateBy）值逐字段一致 + labelField 目标名写错时跳过不抛错**；TranslateJsonTest 断言 raw JSON 不含 "@class"

### B3 cloud-system 接入：依赖 + Provider + 只读查询

- 文件：
  - `cloud-system/pom.xml`（改：+cloud-common-translate-starter 依赖，版本随父 pom）
  - `cloud-system/.../mapper/SysDictDataMapper.java` + `resources/mapper/SysDictDataMapper.xml`（+`listEnabledByDictKey(dictKey)`：JOIN 消费口径查询，deleted=0 显式、`<if>` 不需要（无动态列）、#{} only）
  - `cloud-system/.../mapper/SysUserMapper.java` + `SysUserMapper.xml`（+`listTransAll()`：SELECT id,account,nickname WHERE deleted=0——主表 SQL 显式 deleted=0 守护必过）
  - 新增 `service/translate/DictItemSourceProvider.java` / `UserSourceProvider.java`（实现 common 接口，@Service，转发 mapper；放 service 子包避免与 ManageService 混排）
  - `cloud-system/src/test/.../mapper/MapperXmlBindingTest.java`（改：语句计数 44→46——SysUserMapper +`listTransAll`、SysDictDataMapper +`listEnabledByDictKey`，注释算式同步 10(User)+7(Role)+7(Menu)+4(UserRole)+4(RoleMenu)+6(DictType)+8(DictData)=46。**主控规格审查裁定**：覆盖扩张合法，上轮字典 31→44 同类先例）
- 索引审查（backend-spec 强制项，设计 §6 已裁定）：EXPLAIN 抽查 `listEnabledByDictKey`（驱动表 uk_dict_key、被驱动表 uk_type_value 左前缀）与 `listTransAll`（全表扫小表取舍）——java 单文件通路执行 EXPLAIN 并记录 key 列命中；**结论应为零增量索引**，若实测未命中回报主控（不自行加索引）
- 验收：`mvn test -pl cloud-system -am` 编译过、MapperXmlBindingTest 绑定绿；EXPLAIN 记录在案

### B4 缓存失效挂钩（三个 ManageService 写操作尾部 DEL）

- 文件（改）：
  - `SysDictTypeManageService.java`：save/update/delete 尾部 + `translationCacheService.deleteDict(dictKey)`（update 改键时新旧两键都 DEL；delete 从 requireType 已取实体拿 dictKey）
  - `SysDictDataManageService.java`：save/update/delete 尾部 + deleteDict(所属类型 dictKey)（typeId→dictKey 经 dictTypeMapper.findById）
  - `SysUserManageService.java`：save/update/delete 尾部 + `deleteUsers()`（resetPassword 不挂）
  - 三处注入 TranslationCacheService（common 的 bean，@ConditionalOnMissingBean 默认实现）
- 实现约束（设计 §4.2）：DEL 调用各自 try/catch，失败 log.error 不抛（DB 已提交，TTL 兜底）；**不动既有 @Transactional 结构**（DEL 非事务资源，事务口径零新增）；方法体行数超限风险——DEL 封装为私有辅助方法（private 不受命名白名单扫描）
- 单测（改三个 ServiceTest）：verify deleteDict/deleteUsers 调用与键值（update 改键两键）；DEL 抛异常时主流程不炸（原有断言仍过）；既有全部用例不回归
- 验收：cloud-system 单测全绿（既有 + 新增）

### B5 SysUserVo 译文字段 + 字典消费端点

- 文件：
  - `SysUserVo.java`（改：类标 @TranslateVO；+statusLabel/createByName/updateByName 三 String 字段；`@DictTrans(dictKey="user_status", labelField="statusLabel")` 标 status、`@UserTrans(labelField="createByName")` 标 createBy、`@UserTrans(labelField="updateByName")` 标 updateBy——契约 §3.1/§3.2 形态）
  - `SysDictDataController.java`（改：+`GET /type/{dictKey}`，`@PathVariable("dictKey")` 显式命名、无 @PreAuthorize（user-nav 先例）、javadoc、两行式返回；出参 `R<List<DictItemEntry>>`——共享 DTO 非实体，守护豁免同 LoginUserDTO 先例）
  - `SysDictDataManageService.java`（改：+`listByDictKey(dictKey)` 委托 TranslationCacheService.listDictItems——回源即 Provider 的 DB 查询；未知 dictKey 空数组语义由查询天然返回）
  - `dto/` 无新增（无入参对象）；`types` 契约 §6 同步前端
- 验收：契约 §2.1/§3 逐条对照（路径/权限/空数组/字段名）；ArchitectureGuardTest 全绿（Map 接参/隐式 PathVariable/实体直出均不触雷）

### B6 全量构建与守护验证

- 内容：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am`（-am 自动带上新 starter 与 common 链）
- 验收：全绿（既有全部单测 + B2/B4 新增 + ArchitectureGuardTest/MapperXmlBindingTest 无新违规）；确认 starter jar 已 install 进本地仓库

### B7【卡点：需用户重启 cloud-system】curl 验收（经网关）

- 内容（ASCII 入参；不碰种子字典与 admin 数据）：
  1. **请用户重启 9202**（`java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar`；网关 18080 / sso 9201 在跑则不动）——**启动依赖 Redis**：system 首次引 redis-starter。主控已核验：**Nacos 现无任何 Redis 配置**（共享 cloud-common.yaml 仅 JWT；cloud-sso.yaml/cloud-system.yaml 不存在），sso 的 Redis 连接即 Spring Boot 默认 localhost:6379（本机 Docker Redis 匹配）——system 同样开箱即连，**无需改 application.yml**；启动日志若报 Redis 连接失败，回报主控排查（不自行改仓库配置）
  2. `POST /sso/auth/login`（admin/admin123）取 accessToken
  3. **翻译链路**：`GET /system/user/page?pageNum=1&pageSize=10` → rows[0]：status=0（原字段在）、statusLabel="正常"、createBy="admin"（原字段在）、createByName="管理员"、updateByName="管理员"；**原字段与译文字段并存**（契约 §3.2 形状）
  4. **Redis 键核验**：Docker Redis（127.0.0.1:6379）查 `EXISTS trans:dict:user_status` 与 `trans:user` → 1；`GET trans:dict:user_status` 值为纯 JSON 数组且**不含 "@class"**（docker exec 或适配通路，实现时验证——本机 Redis 是 Docker 容器）
  5. **失效链路**：`PUT /system/dict/data`（把 id=1 项 label 改 "Normal"）→ 再分页 statusLabel="Normal" → `trans:dict:user_status` 键已被 DEL 后回写；改回 "正常" 复原种子
  6. **消费端点**：`GET /system/dict/data/type/user_status` → 2 项 sort 升序；`GET /system/dict/data/type/nonexistent_key` → 200 `data:[]`；无 token 直调 → 网关 401
  7. **回归**：字典 8 管理端点冒烟（分页/新增/删除 e2ecurl 前缀数据并删净）；`GET /system/user/{id}`（pilot §3.2 detail 如存在则原样）零变化
- 验收：断言全过、种子与 admin 零残留变化（label 复原）；完成后回报主控（后端就绪信号，解锁 F3 联调）

## 前端章（cloud-web/，frontend-agent）

> 规范唯一来源 `/frontend-page` 技能；改动只 2 文件；Element Plus 按需引入现状不变。依赖顺序 F1→F2→F3。

### F1 类型字典扩展

- 文件：`cloud-web/src/types/api.ts`（改）
- 内容（契约 §6）：SysUserVo interface 追加 `statusLabel: string | null; createByName: string | null; updateByName: string | null`（既有字段零改动）；新增 `DictItemVo { value: string; label: string; sort: number }`；注释标"契约 2026-10-07-translation-api §6"
- 验收：`npm run build` 零错误；既有类型零改动

### F2 api 层 + 用户页降级链消费

- 文件：
  - `cloud-web/src/api/dict.ts`（改：+`getDictItems(dictKey: string): Promise<DictItemVo[]>`，JSDoc 注契约 §2.1，URL `/system/dict/data/type/${dictKey}`——本轮无页面调用，契约先行）
  - `cloud-web/src/views/system/user/index.vue`（改三处，**降级链必须实现**——契约 §3.1）：
    - 状态列 tag 文本：`{{ rowOf(row).statusLabel ?? STATUS_MAP[rowOf(row).status]?.label ?? rowOf(row).status }}`；**tagType 仍按原 status 映射**（`STATUS_MAP[rowOf(row).status]?.tagType ?? 'info'`，颜色语义保留本地）
    - 创建人列：`{{ rowOf(row).createByName ?? rowOf(row).createBy ?? '-' }}`
    - 更新人列：`{{ rowOf(row).updateByName ?? rowOf(row).updateBy ?? '-' }}`
    - STATUS_MAP 注释更新：标注 label 仅作译文缺位降级、tagType 为颜色映射本体；**编辑弹窗回填/提交仍用原字段 status（零改动）**——契约 §1 红线的前端面
- 验收：build 零错误；后端未就绪时三列走降级链显示与改造前一致（本地 mock 验证 null 分支）

### F3 构建与联通验证（dev 5173 由 agent 自管，不起不动后端）

- 内容：连续两次 `npm run build` 全绿；B7 就绪后 agent 自起 dev server，用户页走查：状态列"正常"（经 statusLabel）、创建人/更新人列"管理员"、DevTools 网络断言响应含三译文字段且原字段并存；**降级验证**：临时停 Redis 或删 trans:user 键不可行则跳过（后端 B7 已测降级），至少断言译文字段为 null 时页面不白屏（改本地 mock 状态验证）
- 验收：两次 build 零错误；走查记录译文与原字段并存截图说明

## e2e 章（cloud-e2e/，frontend-agent 或主控指派）

### E1 run-e2e.mjs 新增 S13 翻译断言（既有断言零改动——兼容性已由种子文案锁定）

- 文件：`cloud-e2e/run-e2e.mjs`（改：S12 后 CLEANUP 前插 S13）；`package.json` 不动（e2e 链已在）
- 场景断言：
  - S13a：用户页 admin 行——状态列 tag 文本 "正常"、**创建人列文本 "管理员"**（admin 种子 nickname，翻译链路端到端证据；改造前该列显示 "admin"，断言升级为锁定翻译行为）
  - S13b：页内 fetch `GET /api/system/user/page` 断言 body.data.rows[0] 同时含原字段（status:0, createBy:"admin"）与译文字段（statusLabel:"正常", createByName:"管理员"）——**契约红线（原字段不丢）的黑盒锁定**
  - 兼容回归：S10/S11 既有状态列断言（'正常'/'停用'）不改——种子 label 同文案天然通过
- 验收：单跑 run-e2e.mjs 全 PASS

### E2 run-dict-e2e.mjs 新增 D5 消费端点场景

- 文件：`cloud-e2e/run-dict-e2e.mjs`（改：D4 后 CLEANUP 前插 D5）
- 场景断言（页内 fetch，admin 会话）：
  - D5a：造类型 `E2E消费{ts}`（dictKey `e2econs{ts}`）+ 3 项（sort 3/1/2，其中 1 项 status=1 停用）→ `GET /api/system/dict/data/type/e2econs{ts}` → data 长度 2（停用项过滤）、首项 sort 最小（排序）、字段恰 value/label/sort
  - D5b：`GET /api/system/dict/data/type/nonexistent_key` → 200 且 data 为 `[]`
  - D5c：无 token 直调网关 `GET /system/dict/data/type/user_status` → HTTP 401
  - D5d（顺带回归）：`GET /api/system/dict/data/type/user_status` → 2 项（种子，"正常"/"停用"）——内置字典消费面锁定
  - CLEANUP 追加：删 e2econs 类型（先删项）；种子 user_status 零触碰
- 验收：单跑 run-dict-e2e.mjs 全 PASS；无 console error / pageerror

### E3 全量回归

- 内容：`cd cloud-e2e && npm run e2e`（有头）六脚本顺序全跑
- 验收：六脚本全 PASS、无数据残留、种子/admin 零变化；截图 ≥3 张（用户页翻译列特写/消费端点 fetch 结果/D5 断言）走 analyze_image 视觉核对，结论文字记录，截图不进 git；**场景脚本与功能代码同一 commit**

## 集成与验收编排（主控）

1. 同消息并行派发两章（后端改 cloud-base / 前端改 cloud-web，零目录冲突）；各自完成章内验收后回报
2. B1 落库 → B7【卡点】请用户重启 9202 + curl 验收（含 Redis 键与失效链路）= 后端就绪 → F3 真实联调复验 → E1-E3
3. 双审：spec 审查按契约逐条（**重点：§3.1 字段表原字段不变+译文字段新增的红线声明、§4 错误码零新增与 3013+ 声明、§2.1 空数组语义与权限口径**）+ quality 审查（守护单测"原字段一致"是否落位）→ 修复循环 → 合并 main

## 移交备忘（下一阶段规划前必读）

1. **id→用户名注解形态未做**（设计 D4）：UserEntry 已含 id，bpmn 业务表出现时 additive（@UserTrans 扩 byId 或独立注解 + 缓存 id 索引）
2. **bpmn 接入清单**：pom 补 translate-starter（传递 redis）+ openfeign + loadbalancer；Provider 以 Feign 实现（SystemUserClient 先例）或直读共享缓存 + Feign 回源；共享缓存纯 JSON 形态已备好（设计 §4.1）
3. **内置字典保护未做**：user_status 可删可停（后果=译文 null + 消费空数组，优雅降级）；「内置角色/菜单/字典保护」backlog 从 3013 接续（契约 §4）
4. **4 页铺开**：role/menu/dict 三页 status 翻译与审计字段翻译逐页 additive 改 VO+契约——机制就绪无额外基建
5. **缓存陈旧窗口 30 分钟**（TTL 兜底）与管理端即时失效并存——若运营反弹缩短 TTL 或加 afterCommit（设计 §4.2 记档取舍）
6. **user 全量单键量级上限**：数万用户以上改分键/增量同步（设计 D5）
7. **序列化行为存档**：GenericJackson2JsonRedisSerializer 存 String 的 raw 形态以 B2 单测断言为准；若未来换 StringRedisTemplate 直写，契约面（键/值形态）不变
8. **dictKey 拼写防呆**：@DictTrans 字面量拼错静默降级——启动期/测试期校验待多字典场景出现后再议
9. **错误码账本**：本轮零新增；3xxx 用至 3012，**3013 起预留「内置角色/菜单/字典保护」backlog**（本轮已并入字典项，契约 §4 声明）

## 给 backend-agent 的任务清单（可直接粘发）

按 B1→B7 执行；对齐基线 = 契约 `docs/superpowers/contracts/2026-10-07-translation-api.md` + 设计 `2026-10-07-field-translation-design.md`（**§3.3 设计规则 0 与 D3/D5/D6/D7 必读**）+ 本计划后端章 + `/backend-spec` 技能 + CLAUDE.md 编码规范。要点重申：

- **红线（用户明示）**：翻译绝不覆盖原字段——Advisor 只写 labelField；TransFieldScanner 校验目标字段存在/可写否则跳过 + log.warn；**B2 守护单测必须含"翻译前后原字段一致"用例**
- B1：增量 `2026-10-07-translation.sql`（type 1 行 + data 2 行，create_by='system'，label 文案逐字=契约 §0.3）+ 基线同步；**执行前核对 dict 两表 0 行、id 1/1/2 空闲**；java 单文件执行 + 回查；不动服务
- B2：新 starter 全套（pom 挂模块/web provided/注解/共享 DTO/Provider 接口/Advisor/Scanner/CacheService/AutoConfiguration.imports + 4 个单测类）；缓存值纯 JSON String 无 @class（单测断言）；TTL 30 分钟；全程降级不抛
- B3：system pom + translate 依赖；mapper +listEnabledByDictKey（JOIN 消费口径，deleted=0 显式）+ listTransAll；**EXPLAIN 审查两查询**（预期 uk_dict_key/uk_type_value 命中、listTransAll 全表小表取舍），结论记录，未命中回报不自行加索引
- B4：三个 ManageService 写操作尾部挂 DEL（DictType update 改键删两键；DictData 经 typeId 查 dictKey；User save/update/delete 挂 trans:user）；DEL try/catch log.error 不抛；事务结构零改动
- B5：SysUserVo @TranslateVO + 三译文字段 + 三字段注解（契约 §3 形态）；消费端点 GET /dict/data/type/{dictKey}（无 @PreAuthorize、显式 @PathVariable、javadoc、两行式、空数组语义、出参 DictItemEntry）
- B6：`mvn clean install -pl cloud-system -am` 全绿（新 starter 一并构建）
- B7：【卡点】**请用户重启 9202**（网关/sso 不动；Redis 连接取 Nacos 共享配置，启动失败回报主控）+ curl 验收七步（翻译链路/Redis 键核验/失效链路/消费端点/回归/种子 label 复原）
- 红线：与契约不符回报主控不自行猜测；既有端点与 pilot 字段零触碰（additive only）；错误码零新增；种子与 admin 数据不碰（验收改 label 后必须复原）

## 给 frontend-agent 的任务清单（可直接粘发）

按 F1→F3 顺序执行；对齐基线 = 契约 `2026-10-07-translation-api.md` §3/§6/§7 + 本计划前端章 + `/frontend-page` 技能。要点重申：

- F1：types/api.ts——SysUserVo 追加三可空字段（既有字段零改动）+ DictItemVo 新类型（契约 §6）
- F2：api/dict.ts +getDictItems；user/index.vue 三列降级链（`statusLabel ?? STATUS_MAP[status].label ?? status` / `createByName ?? createBy ?? '-'` / `updateByName ?? updateBy ?? '-'`）；**tagType 颜色仍按原 status 本地映射；编辑弹窗回填提交零改动（原字段红线的前端面）**
- F3：连续两次 `npm run build` 全绿；B7 就绪后 **agent 自管 dev 5173** 联调走查（译文与原字段并存的网络断言）
- e2e（E1-E3）：E1 run-e2e.mjs +S13（admin 行创建人列"管理员" + fetch 断言原字段/译文字段并存——**红线黑盒锁定**；既有断言零改动）；E2 run-dict-e2e.mjs +D5（消费端点停用过滤/排序/未知键空数组/无 token 401/种子回归）+ CLEANUP 扩展；E3 六脚本全量回归 + 截图 ≥3 张 analyze_image 视觉核对（结论文字记录）
- 红线：Element Plus 按需引入（禁全量）；契约与实测不符回报主控；e2e 黑盒 + e2e 前缀数据 + 不碰种子/admin（user_status 种子不删不改）；场景脚本与功能代码同一 commit
