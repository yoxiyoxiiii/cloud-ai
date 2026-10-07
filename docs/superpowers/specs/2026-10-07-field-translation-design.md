# 字段统一翻译技术方案（字典含义 + 用户名称：注解 + ResponseBodyAdvice + Redis 缓存）

- 日期：2026-10-07
- 状态：已定稿（阶段一需求分析已经用户拍板，四项结论全按倾向：翻译层=ResponseBodyAdvice 批量回填；样板=用户管理页 + 例外种 user_status 内置字典；显示名=nickname 且本轮翻审计字段；范围=基建+消费端点+样板，bpmn/铺开/颜色后端化/remark 推迟记档）
- 需求原文：「后端数据库中所有的字典字段可能在业务列表查询或者查询的时候需要返回字典的含义，通过注解统一处理，字典存入 redis 提高查询效率；业务表中一般会关联用户id，列表查询或者单个查询也可能返回或者回显用户名称，同样通过注解统一处理」
- 范围：**翻译基建（common 新 starter）+ 字典消费端点 + 用户管理页样板 + e2e**；**不含** bpmn 侧接入、4 页全量铺开、tagType 颜色语义后端化、字典 remark 列、内置字典防删（并入 3013+ 保护 backlog）
- 契约：`docs/superpowers/contracts/2026-10-07-translation-api.md`（翻译域现行版 + pilot user 契约的 additive 声明）
- 计划：`docs/superpowers/plans/2026-10-07-field-translation.md`

## 1. 需求与范围

| 项 | 结论 |
|---|---|
| 本轮做 | cloud-common-translate-starter 新 starter（两注解 + ResponseBodyAdvice 批量回填 + Redis 缓存 + Provider 回源接口）；字典消费端点 `GET /dict/data/type/{dictKey}`（兑现 dict 移交备忘 1，停用过滤口径）；cloud-system 补 redis/translate 依赖 + 两个 Provider 实现 + 缓存失效挂钩；用户页样板（SysUserVo additive 三字段：statusLabel/createByName/updateByName）；user_status 内置字典种子；e2e（翻译断言 + 消费端点场景 + 回归） |
| 本轮不做 | bpmn 侧接入（无业务表，阶段 4）；role/menu/dict 三页 status 铺开；tagType 颜色语义后端化（保留前端值→颜色映射）；字典 remark 列；内置字典防删防改（并入「内置角色/菜单/字典保护」backlog，错误码 3013+ 预留项）；id→用户名注解形态（仅预留，见 D4） |
| 改动面 | cloud-common 聚合 + 新模块；cloud-system（9202）重启（新依赖 + 新端点 + VO 变更）；**网关 / sso 零改动零重启**（消费端点走既有 /system/** 路由；本轮无新 /inner 端点）；cloud-web 用户页小改 |

## 2. 现状盘点（设计前提，2026-10-07 逐项核实）

- **字典域已落地、零消费方**：sys_dict_type/sys_dict_data 两表 + 8 管理端点已实现；种子零字典数据（dict 设计 D7）；移交备忘 1 预留消费端点与缓存策略——本轮兑现并扩展到出参翻译
- **当前系统真实"字典字段"仅各表 status（Integer 0/1），全在前端硬编码**：`cloud-web/src/views/system/{user,role,menu,dict}/index.vue` 各自 `STATUS_MAP: {0:{label:'正常',tagType:'success'},...}`——label + tagType 双语义，翻译只供 label（D9）
- **"业务表关联用户 id"的首个真实消费者尚不存在**（bpmn 阶段 4 未开发）；现有关联是审计字段 createBy/updateBy 存 **account 字符串**（`SysUserMapper.xml` 显式传 `SecurityUtils.currentAccount()`）——本轮用户翻译落 account→nickname 形态
- **Jackson 全局定制先例**：`CommonJacksonAutoConfiguration`（Jackson2ObjectMapperBuilderCustomizer，Long→String/时间格式）——翻译回填发生在序列化**前**，与之零冲突
- **Redis 形态与硬约束**：`CommonRedisAutoConfiguration` RedisTemplate<String,Object>，value 为 GenericJackson2JsonRedisSerializer（**跨服务直读 DTO 会因 @class 类型头失败**）——翻译缓存必须**纯 JSON 字符串 + common 共享 DTO 投影**（学 sso:online 契约，`TokenService` 手写 ObjectMapper 先例）；RedisUtil 薄封装无 hash 操作——本方案只用 opsForValue（单键 String），不需扩展 hash
- **服务依赖差异**：sso 已有 redis-starter + openfeign；**cloud-system 无 redis-starter**（本轮补）；**cloud-bpmn 无 feign 无 redis**（阶段 4 再补）；starter 的 web 依赖用 provided scope（security-starter 先例）
- **用户通路**：/inner 仅单查（InnerUserController）；SysUserMapper 无 IN 批量查/全量查——本轮补两条只读查询（见 §6 索引审查）；种子 admin（id=1, account=admin, nickname=管理员，`cloud_system.sql:150-151`）
- **无 @PreAuthorize 先例**：`SysMenuController.userNav()`「无 @PreAuthorize——任何已登录用户可访问自己的投影」——消费端点同口径
- **守护测试**：ArchitectureGuardTest 命名白名单只扫 cloud-system 的 service/mapper；common 组件不受强制但方法名自律同动词集；VO 隔离规则禁 R<SysUser> 等——翻译字段是 VO 上普通 String 字段，不冲突
- **e2e 断言耦合点**：run-e2e.mjs 状态列断言文本 `'正常'`/`'停用'`（:199,:322,:352 等）——**种子字典 label 文案锁定为同文案**，断言零改动变回归验证；创建人列无文本值断言（仅表头 :188）——admin→"管理员" 不破坏断言

## 3. 架构设计

### 3.1 组件结构（新 starter：cloud-common-translate-starter）

```
cloud-common/cloud-common-translate-starter/
├── pom.xml                                    # 依赖 core-starter(compile) + redis-starter(compile) + starter-web(provided)
├── src/main/java/com/cloudai/common/translate/
│   ├── annotation/TranslateVO.java            # 类级注解：启用该 VO 的翻译扫描（supports 快速判定）
│   ├── annotation/DictTrans.java              # 字段级：dictKey + labelField（译文回填目标字段名）
│   ├── annotation/UserTrans.java              # 字段级：labelField（account→nickname）
│   ├── domain/DictItemEntry.java              # 共享 DTO：{value,label,sort}——缓存形态 + 消费端点出参复用
│   ├── domain/UserEntry.java                  # 共享 DTO：{id,account,nickname}（id 序列化为字符串）
│   ├── provider/DictSourceProvider.java       # 回源接口：List<DictItemEntry> listByDictKey(String dictKey)
│   ├── provider/UserSourceProvider.java       # 回源接口：List<UserEntry> listAll()
│   ├── core/TranslateAdvisor.java             # ResponseBodyAdvice：扫描收集→批量翻译→回填
│   ├── core/TransFieldScanner.java            # Class→翻译字段元数据缓存（反射一次）
│   ├── core/TranslationCacheService.java      # Redis 读写（纯 JSON）+ 回源编排 + TTL
│   ├── core/TranslateJson.java                # 静态 ObjectMapper（Long→String，双端一致）
│   └── config/CommonTranslateAutoConfiguration.java
├── src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
└── src/test/java/...                          # Advisor 回填/降级/扫描缓存/JSON 形态 单测
```

自动装配沿用 common 惯例（@AutoConfiguration + imports + @ConditionalOnMissingBean，用户自定义同名 bean 可覆盖）；服务引依赖即生效，不引则零影响。

### 3.2 数据流（一次用户分页请求）

```
GET /system/user/page
  → SysUserController.page → Service pageList → convert（不动，翻译字段为 null）→ R<PageResult<SysUserVo>>
  → TranslateAdvisor.beforeBodyWrite：
      1. supports 已判定返回类型含 @TranslateVO 类（泛型解析 R<PageResult<SysUserVo>>）
      2. 遍历 data→rows（深度上限 3 + visited 去环 + 仅 @TranslateVO 实例）
      3. 收集：@DictTrans("user_status") 的 status 值 {0,1}；@UserTrans 的 createBy/updateBy 值 {admin,...}
      4. 批量：TranslationCacheService.findDictLabels("user_status",{0,1})
               TranslationCacheService.findUserNames({admin,...})
         （Redis 单键读 → 未命中 Provider 回源 DB → 回写 TTL 30min → 构建 map）
      5. 回填：vo.setStatusLabel("正常") / vo.setCreateByName("管理员")（原生 setter）
  → Jackson 序列化（译文字段随出参；Long→String 等全局格式不受影响）
```

字典管理页/消费端点的数据流：`GET /system/dict/data/type/{dictKey}` → Service → TranslationCacheService.listDictItems(dictKey)（同缓存同回源，D6）。

### 3.3 Advisor 机制要点

- `TranslateAdvisor implements ResponseBodyAdvice<Object>` + @RestControllerAdvice：supports() 解析 MethodParameter 泛型链取最终 VO Class，标 @TranslateVO 才介入（解析结果按 Method 缓存）；R<Void>/R<Long>/springdoc 等未标注响应零成本跳过
- beforeBodyWrite 只处理 R 的 data 分支；PageResult.rows / Collection / 数组展开；递归深度上限 3、IdentitySet 防环、只下钻 @TranslateVO 类实例（普通嵌套对象跳过）
- **设计规则 0（红线，用户明示）：翻译绝不丢弃/覆盖数据库原始值**——userId/status/account 等原字段必须原样返回（前端用它们做编辑回填再提交、行内逻辑判断、tag 颜色映射、筛选等业务处理）。处理器**只写配对译文字段（labelField），绝不写原字段**；原字段与译文字段在 VO/契约中**并存**。防呆：labelField 目标字段缺失或不可写时**降级跳过 + log.warn，不允许抛错影响业务响应**
- **降级总纲：翻译绝不拖垮业务响应**——Advisor 全程 try/catch Throwable → log.error → 原样返回 body；单字段翻译未命中（字典无此项/用户已删）→ labelField 保持 null（前端降级链展示，契约 §3）
- **手翻逃生门**：labelField 已非 null 时跳过回填——个别复杂场景 Service 手翻优先（拍板结论 1）
- 回填用原生 setter（Field#get 后 MethodHandles 或直接 Field#set accessible）——禁三方拷贝工具规范同源；TransFieldScanner 按 Class 缓存元数据，反射成本每类一次

### 3.4 注解用法（样板 = SysUserVo）

```java
@TranslateVO
public class SysUserVo implements Serializable {
    ...
    private Integer status;
    @DictTrans(dictKey = "user_status", labelField = "statusLabel")
    // 注解标注在被翻译字段上；译文落 labelField 声明的同 VO String 字段
    private String statusLabel;   // —— 实际写法：注解在 status 上，见契约 §3 示例
    ...
}
```

（准确形态见契约 §3.2 示例：`@DictTrans(dictKey="user_status", labelField="statusLabel")` 标在 `status` 字段；`@UserTrans(labelField="createByName")` 标在 `createBy` 字段；`updateBy` 同理。）

## 4. 缓存设计（Redis 契约）

### 4.1 键与值

| 键 | 值（纯 JSON 字符串，无 @class） | 写入者 | 失效者 |
|---|---|---|---|
| `trans:dict:{dictKey}`（如 trans:dict:user_status） | `[{"value":"0","label":"正常","sort":1},{"value":"1","label":"停用","sort":2}]`（**消费口径**：类型启用+未删 ∧ 项启用+未删，sort ASC,id ASC） | TranslationCacheService 回源回写 | system 字典写操作 |
| `trans:user`（全量单键） | `[{"id":"1","account":"admin","nickname":"管理员"},...]`（全量 deleted=0；id 字符串化） | 同上 | system 用户写操作 |

- 序列化：值 = **手写 JSON String**（common 内静态 ObjectMapper，配 Long→String 与全局约定一致），经 RedisTemplate opsForValue 存取——GenericJackson2JsonRedisSerializer 对 String 透明（B2 单测断言 raw 值无 `@class`，行为实现时验证）；投影解析用共享 DTO DictItemEntry/UserEntry（跨服务无类依赖问题）
- TTL：30 分钟（写操作 DEL 是主失效通道，TTL 兜底多实例/DEL 失败路径）
- 单键 String 而非 hash：无需 field 级更新（失效即整键 DEL+回源重建）；RedisUtil 不用扩展
- user 全量单键：企业内部用户量级（几百~几千）单键 JSON 几十 KB，读一次全量构建 account→nickname 索引，O(1) 命中——量级若涨到数万再改分键（移交备忘）

### 4.2 失效挂钩点（cloud-system 侧，事件式 DEL）

| Service 方法 | DEL 键 | 说明 |
|---|---|---|
| SysDictTypeManageService save | trans:dict:{新 dictKey} | 防御性（新键无缓存也可 DEL） |
| SysDictTypeManageService update | trans:dict:{旧 dictKey} + trans:dict:{新 dictKey}（改键时两键） | 改键/改状态都影响消费口径 |
| SysDictTypeManageService delete | trans:dict:{dictKey}（delete 前 requireType 已取到实体） | |
| SysDictDataManageService save/update/delete | trans:dict:{所属类型 dictKey} | typeId→dictKey 经 dictTypeMapper.findById（项操作已有 requireType 或可取） |
| SysUserManageService save/update/delete | trans:user | nickname 变更/新增/删除；resetPassword 不动显示名不挂 |

- **失败语义**：DEL 包 try/catch，失败仅 log.error 不抛——DB 已提交，缓存陈旧由 TTL 30 分钟兜底（宽松语义记档契约 §5）
- **事务边界（事务口径裁定）**：DEL 在 @Transactional 方法内 DB 写之后直接调用，**不用** TransactionSynchronization.afterCommit——事务回滚时误 DEL 的后果仅是下次回源重建（无害）；DEL 后事务才提交的窗口他读可能回源旧数据回写（陈旧上限=TTL）。两处竞态后果皆轻，复杂度不值——记档接受
- 本轮**零新增 @Transactional**：消费端点与回源均为只读单语句；失效 DEL 是 Redis 非事务资源

## 5. 错误处理与降级矩阵

| 场景 | 行为 | 前端表现（契约 §3 降级链） |
|---|---|---|
| dictKey 无对应类型/项（拼写错、被删光） | 翻译 map 空 → labelField 保持 null | statusLabel ?? 本地 STATUS_MAP label ?? 原值 |
| 字典项停用 | 消费口径过滤 → 该 value 无译文 → null | 同上 |
| 用户已删/不存在 | account 无匹配 → null | createByName ?? createBy ?? '-' |
| labelField 缺失/不可写（注解写错目标名） | 降级跳过 + log.warn，不抛错（设计规则 0 防呆） | 原字段原样返回，译文字段缺位走降级链 |
| Redis 不可用 | Advisor catch → 跳过翻译（log.error） | 同字段 null 降级链 |
| Provider 回源异常 | catch → 当次不回填（不回写缓存） | 同上 |
| 消费端点 dictKey 不存在 | **200 + 空数组**（D6，不报错） | 下拉空——表单场景容错优先 |

## 6. DDL 与索引审查（backend-spec：迭代新增查询路径必审）+ 事务口径

**本轮无新表、无 DDL 变更**（种子是纯 INSERT）。新增两条只读查询路径，EXPLAIN 审查结论：

| 新查询 | 语句形状 | 索引审查 |
|---|---|---|
| SysDictDataMapper.listEnabledByDictKey(dictKey) | sys_dict_type t JOIN sys_dict_data d ON d.dict_type_id=t.id WHERE t.dict_key=#{dictKey} AND t.status=0 AND t.deleted=0 AND d.status=0 AND d.deleted=0 ORDER BY d.sort,d.id | 驱动表 t 按 dict_key 等值 → **uk_dict_key 命中**；d 按 dict_type_id 关联 → **uk_type_value 最左前缀命中**（dict 设计 §3.1 已裁"不另建 idx"）。**无需增量索引** |
| SysUserMapper.listTransAll() | SELECT id,account,nickname FROM sys_user WHERE deleted=0 | 全表扫描（小表，量级几百~几千行，无 WHERE 选择列）——**主键隐含、无建索引空间**；若未来量大改分页或增量同步（移交备忘） |

EXPLAIN 抽查进 B3 验收（java 单文件通路回查 EXPLAIN 输出 key 列命中 uk_dict_key/uk_type_value）。

事务口径：本轮全部单表单语句只读或单写+Redis DEL——**零新增 @Transactional**（backend-spec 事务口径：只挂多写语句方法）。

## 7. 测试策略

### 7.1 common starter 单测（构建期，无 Redis 依赖——mock RedisTemplate/opsForValue）

- TranslateAdvisorTest：PageResult<SysUserVo> rows 回填 statusLabel/createByName；**守护性验证（设计规则 0）：翻译前后原字段值逐字段一致**（回填前快照对比 status/createBy/updateBy/userId 等，防未来实现走样）；嵌套/深度上限/环防护；未标注类与 R<Void> 跳过；labelField 已有值跳过（手翻优先）；**labelField 目标名写错（字段缺失/非 String/不可写）→ 跳过 + 不抛错**；翻译异常返回原 body（降级）；R.data=null 安全
- TransFieldScannerTest：元数据按类缓存（二次获取同实例）；无翻译字段类返回空元数据
- TranslationCacheServiceTest（mock Redis）：未命中→回源→回写 TTL；命中零回源；回源异常不回写；deleteDict/deleteUsers 调用转发
- TranslateJsonTest：DictItemEntry/UserEntry 往返序列化；UserEntry.id 为字符串；**raw JSON 断言不含 "@class"**

### 7.2 cloud-system 单测（Mockito mock mapper，沿既有范式）

- 消费端点 Service：listByDictKey 委托缓存服务 verify；停用过滤语义在 mapper XML（MapperXmlBindingTest 绑定绿）+ curl 实测
- 失效挂钩：DictType save/update（改键 DEL 两键）/delete、DictData save/update/delete、User save/update/delete → verify TranslationCacheService.deleteXxx 调用；DEL 异常不影响主流程（不抛）
- Provider 实现：listByDictKey/listTransAll 转发 mapper

### 7.3 后端 curl 验收（经网关，B7）

- 翻译链路：`GET /system/user/page` 断言 rows[0] statusLabel="正常"、createByName/updateByName="管理员"；Redis 查 `trans:dict:user_status`/`trans:user` 键存在且值无 @class（Docker Redis 实现时验证通路）
- 失效链路：改 user_status 项 label → 再分页 → 新译文（缓存已 DEL）；停用一个项 → 该值译文 null
- 消费端点：`GET /system/dict/data/type/user_status` 数组形态+排序；停用类型 → 空数组；未知 dictKey → 200 空数组；无 token → 401
- 审计回归：既有 user CRUD/字典 8 端点零变化

### 7.4 黑盒 e2e（计划 e2e 章）

- run-e2e.mjs 新增 S13：用户页创建人列显示"管理员"（admin 种子 nickname）、状态列"正常"（经 statusLabel——文案与本地映射一致，断言升级为验证翻译链路）
- run-dict-e2e.mjs 新增 D5：消费端点（页内 fetch）：造类型+项 → 断言数组/排序/停用项过滤 → 未知 dictKey 空数组 → 清理
- 全量六脚本回归：状态列文案断言因种子 label 同文案而天然兼容（回归验证而非维护，spec §2 已核实断言点）

## 8. 设计决策（D 系列）

### D1 翻译发生层 = ResponseBodyAdvice 批量回填（拍板）

- 弃 Jackson 序列化期注解（每字段触发一次缓存读，列表页 N 次；序列化器内 IO 隐藏且降级语义别扭）；弃 SQL JOIN（跨服务不可用，bpmn 无法 JOIN system 表）；弃纯 Service 手翻（非声明式，与注解诉求不符）——**Service 手翻保留为个别复杂场景兜底逃生门**（Advisor 跳过已非空 labelField，拍板结论 1）
- 代价接受：对象树遍历自实现（深度上限+visited+@TranslateVO 类白名单收敛成本）；springdoc 等响应经 supports 泛型判定零成本排除

### D2 组件落点 = 新建 cloud-common-translate-starter

- 塞 core-starter 会让全服务被迫带 redis/web 依赖，边界劣化；独立 starter 服务按需引入，自动装配沿用 common 惯例
- web 依赖 provided（security-starter 先例）+ redis-starter compile（缓存是组件本体能力）
- 跨服务取数（bpmn/sso 未来）：Provider SPI 接口即回源抽象——system 以 DB 实现，他服务以 Feign 实现（SystemUserClient 先例）；本轮只落 system 实现，bpmn 记档阶段 4

### D3 注解语义：标注被翻译字段 + 译文落 labelField 新字段（**原字段绝不触碰——用户红线**）

- `@DictTrans(dictKey, labelField)` 标 status（值字符串化匹配 value）；`@UserTrans(labelField)` 标 createBy/updateBy（account→nickname）；类级 @TranslateVO 收敛扫描面（supports 零反射判定 + 遍历白名单）
- 译文**落新字段、原字段原样保留**（设计规则 0）：userId/status/account 等原值是前端业务处理的依据（编辑回填、行内判断、tag 颜色映射、筛选）——**处理器只写 labelField 一个字段，绝不写原字段**；守护单测锁死"翻译前后原字段一致"（§7.1）
- 未命中 = null（不是空串）：与"字段未翻译"同语义，前端降级链统一；labelField 显式声明优于命名约定派生（可 grep、可守护），目标字段缺失/不可写时跳过 + log.warn（不抛错）

### D4 用户翻译形态 = account→nickname 本轮落地；id→名称仅预留

- 拍板结论 3：本轮翻审计字段（createBy/updateBy 存 account），显示名 = nickname
- id→名称暂不做注解形态（YAGNI——首个 id 消费者在 bpmn 阶段 4）；预留方式记档：UserEntry 已含 id，届时加 `@UserTrans(byId=true)` 或独立注解 + 缓存补 id 索引，additive 演进不动现有形态

### D5 Redis 策略：按 dictKey 单键 + 用户全量单键，事件式 DEL + TTL 30 分钟兜底

- dict 键失效粒度 = 类型级写操作（一个类型一个键）；user 全量单键（量级小，读一次全量建索引）；不引入 hash（无 field 级更新需求，RedisUtil 零扩展）
- 失效挂字典/用户写操作尾部（§4.2）；DEL 失败与事务竞态由 TTL 兜底（宽松语义记档）
- 纯 JSON + 共享 DTO 投影（禁 @class 类型头）——跨服务共享的硬约束，学 sso:online 契约

### D6 消费端点：登录即可 + 未知 dictKey 返回 200 空数组（零新错误码）

- 权限：**无 @PreAuthorize**（user-nav 先例口径——表单下拉是登录用户的基础能力，不该挂管理权限 system:dict:list）
- dictKey 不存在/停用类型/无启用项 → **R.ok(空列表)**，不设业务码：表单场景容错优先（下拉空是可接受 UI 态，报错炸表单）；与翻译降级静默语义一致；**本轮错误码零新增，3xxx 账本 3001-3012 不变、3013+ 预留声明不变**（契约 §4 显式声明）
- 排序 sort ASC, id ASC（项分页同款）；出参复用 DictItemEntry（非 DB 实体，同 LoginUserDTO/MenuTreeNode 的守护豁免先例）

### D7 种子：user_status 内置字典（dict 设计 D7"零字典种子"的**记档例外**）

- 样板依赖（用户拍板）：增量 SQL + 基线同步种 1 类型（id=1，user_status）+ 2 项（id=1/2，"正常"/"停用"），create_by='system'（内置标记，区别人工 admin）
- **label 文案锁定 = 现有前端硬编码文案**（正常/停用）——run-e2e.mjs 状态列断言零改动（兼容性是设计出来的，spec §2）
- 张力记档：违背 dict 设计 D7"不种任何字典业务数据"——例外理由：翻译样板需要真实字典数据且该字典属系统内置语义（非运营数据）；**防删防改并入「内置角色/菜单/字典保护」backlog（3013+ 预留）本轮不做**——当前删/停 user_status 的后果 = 翻译降级 null + 消费端点空数组（优雅降级不崩溃），可接受
- 执行通路：java 单文件源码（B1 惯例）；执行前核对 sys_dict_type/sys_dict_data 零行、dict id 1/1/2 空闲

### D8 审计字段翻译范围 = 本轮 createByName/updateByName 两字段（用户页样板）

- SysUserVo additive 三字段：statusLabel/createByName/updateByName（string|null，契约 §3）；role/menu/dict 页审计字段不铺（推迟记档）
- convert 层零改动——翻译字段由 Advisor 运行时回填，初始 null（手翻逃生门语义的自然基础）

### D9 tagType 颜色语义保留前端

- 翻译只供文本 label；值→颜色（success/danger）映射是前端展示策略，保留 STATUS_MAP 的 tagType 分支——契约降级链 `statusLabel ?? STATUS_MAP[status].label ?? status`

### D10 sso/网关零感知

- 翻译发生在 system 服务内（Advice 只在引依赖的服务装配）；网关 WebFlux 不引；sso 本轮不引——Redis 新键 trans:* 与 sso:online/sso:refresh 无冲突；网关路由零改动（消费端点在既有 /system/** 下）

## 9. 已知取舍与移交备忘（下一阶段规划前必读）

1. **id→用户名注解形态未做**（D4）：bpmn 业务表出现时按需 additive（UserEntry 已含 id）
2. **bpmn 接入**：需补 translate-starter 依赖 + Feign 版 Provider（或直读共享缓存 + 未命中 Feign 回源）；共享缓存纯 JSON 形态已为跨服务备好
3. **内置字典保护未做**（D7）：user_status 可被管理页删/停（降级优雅）；「内置角色/菜单/字典保护」backlog 从 3013 接续
4. **4 页铺开未做**：role/menu/dict 三页 status 翻译 + 审计字段翻译——机制就绪，逐页 additive 改 VO+契约即可
5. **缓存陈旧窗口**：DEL 失败/事务竞态路径下最长 30 分钟（TTL）；管理端即时失效是主通道
6. **user 全量单键量级上限**：数万用户以上改分键/增量（D5）；dict 单类型项数无虞（uk 约束 + 运营治理）
7. **GenericJackson2JsonRedisSerializer 存 String 行为**：B2 单测断言 raw 无 @class；若实测异常（如带引号转义问题）改用 StringRedisTemplate 直写（备选通路，不动契约）
8. **dictKey 拼写防呆**：注解内 dictKey 是字符串字面量，拼错静默降级——启动期校验（扫描 @DictTrans 与缓存键比对）留待有真实多字典后再议
