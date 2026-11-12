# 实施计划：inner 面共享密钥生命周期管理（定时轮换 + 管理界面）

- 日期：2026-11-09
- 关联：设计 `docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md`；契约 `docs/superpowers/contracts/2026-11-09-inner-access-secret-api.md`（v2，运行时分发）+ `docs/superpowers/contracts/2026-11-09-inner-secret-admin-api.md`（管理面）——三文档已定稿，本计划是唯一实施依据
- 轨道判定：**全栈**（cloud-system + inner-starter + cloud-web 页面 + e2e）；架构分级 A
- 本仓 agent 不做任何 git 提交——工作树改动留给主控处置。

## 0. 任务总览与执行顺序

```
后端章                                        前端章（与后端并行，契约即对齐物）
T1 表+种子 SQL ──┬─▶ T2 starter 抽象 ──▶ T3 管理面+任务 ──▶ T4 Nacos ──▶ T5 冒烟+回归 ──▶ T6 文档    F1 api+页面 ──▶ F2 e2e
```

- T1 可先行（SQL 独立）；T2/T3 有依赖（T3 发布方引用 T2 的键常量与来源类）；F1/F2 零后端依赖（e2e 全 route-mock）。
- 前置状态：三服务运行 v1 jar（静态密钥准入已生效）；本计划全部改动在 Redis 键生成前对外零行为变化（§3 runbook）。

## 1. 后端章

### T1 表与种子 SQL

| 文件 | 内容 |
|---|---|
| `cloud-base/scripts/sql/2026-11-09-inner-secret-rotation.sql`（新建） | 四段：① `sys_shared_secret` DDL（设计 §7 原文，含 uk_scene_seq / idx_scene_status 与逐列 COMMENT）② 站内信内置模板 id 4（code `inner_secret_alert`，标题「inner 密钥轮换告警」，内容 `密钥轮换异常：${content}，请及时处理`，type 2 / level 1 / is_builtin 1）③ 菜单种子：C id 22 挂 parent 10（path `/system/inner-secret`，perms `system:innerSecret:list`，icon `Key`，sort 9）+ F id 200（`system:innerSecret:rotate`）/ id 201（`system:innerSecret:revoke`）+ admin(1) 绑定三行 ④ xxl_job id 12（组 3，CRON `0 * * * * ?`，handler `innerSecretRotateJobHandler`，SERIAL，retry 0）。头部前置断言四条（表不存在；sys_menu 22/200/201、模板 id 4、xxl_job id 12 空闲——含墓碑物理判断）与不可重放声明，沿 `2026-11-08-notify-center.sql` 形态 |
| `cloud-base/scripts/sql/cloud_system.sql`（基线同步） | 追加 sys_shared_secret DDL + 菜单/模板/绑定种子段 |
| `cloud-base/scripts/sql/2026-10-09-xxl-job-init.sql`（基线同步） | 追加 id 12 种子段 |

**执行**：经 pymysql 落库（中文种子参数化 SQL，charset utf8mb4；前置断言不满足停下回报，不盲跑）。

**验收**：四段落库成功；`SELECT` 核对菜单 22/200/201 与模板 4、`xxl_job_info` id 12 存在；admin 重新登录后权限快照含三枚 perms。

### T2 inner-starter 密钥来源抽象

| 文件 | 变更 |
|---|---|
| `cloud-common/cloud-common-inner-starter/pom.xml` | 加 `org.springframework.data:spring-data-redis`（provided，版本归根 pom Boot BOM 免版本号） |
| `.../inner/core/InnerSecretSource.java`（新） | 接口：`List<String> acceptedSecrets()` / `String injectSecret()`（javadoc 写明空集语义=fail-closed/跳过注头） |
| `.../inner/core/StaticInnerSecretSource.java`（新） | 包装 InnerProperties（v1 行为；静态空配置 → 空集） |
| `.../inner/core/RedisInnerSecretSource.java`（新） | 设计 §4.2 算法：StringRedisTemplate GET `inner:secret:active`（**键名 public static final 常量，发布方 cloud-system 共用此类**）→ 解析 `{version, secrets:[{value, expireAtMs}]}` → 本地时钟剔除过期 → 缓存 TTL（InnerProperties.cacheTtlSeconds，默认 30）；键不存在回落静态 delegate；访问报错有粘性续用、无粘性空集；volatile 不可变快照 + synchronized 单飞刷新；JSON 损坏按报错分支处理且日志不记内容 |
| `.../inner/config/InnerProperties.java` | 加 `cacheTtlSeconds`（int，默认 30） |
| `.../inner/config/CloudInnerAutoConfiguration.java` | 装配来源抽象：Static 恒注册；嵌套配置 `@ConditionalOnClass(StringRedisTemplate.class)` + `@ConditionalOnBean(StringRedisTemplate.class)` 注册 RedisInnerSecretSource 并 `@Primary`；类级 `@AutoConfiguration(after = RedisAutoConfiguration.class)`（缺类安全）；过滤器/拦截器改为注入 InnerSecretSource |
| `.../inner/auth/InnerAccessFilter.java` | 比对改集合：`acceptedSecrets()` 逐把 `MessageDigest.isEqual`，命中任一放行；集合空/无头/错值统一 401（报文与日志不变） |
| `.../inner/client/InnerSecretInterceptor.java` | 注头改 `injectSecret()`，null/空跳过 |
| 单测 4 文件（改+新） | `RedisInnerSecretSourceTest` 新（六分支：缓存命中不访 Redis/键存在解析+过期剔除/键不存在回落/报错粘性/冷启动报错空集/JSON 损坏）；`InnerAccessFilterTest` 改（集合任一命中放行、空集 401、无头 401、错值 401——构造入参包 StaticInnerSecretSource）；`InnerSecretInterceptorTest` 改（最新钥注头/空集跳过）；`CloudInnerAutoConfigurationTest` 改（有 StringRedisTemplate→Redis 来源 @Primary 装配；无 redis classpath→static-only 且 v1 语义保留） |

**验收**：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-inner-starter -am` 绿；注释遵守可读性标准（无决策代号裸引用，引用设计文档写路径）。

### T3 cloud-system 管理面 + 轮换任务

| 文件 | 内容 |
|---|---|
| `cloud-system/.../entity/SysSharedSecret.java`（新） | 设计 §7 列映射；嵌套 `StatusEnum{ACTIVE(1)/OVERLAP(2)/EXPIRED(3)/REVOKED(4)}`、`SourceEnum{JOB(1)/MANUAL(2)}`（Integer 映射 + of(code)，规范第 8 条）；不继承 BaseEntity（无 deleted，追加式流水）——注：BaseEntity 若强绑 deleted 需按 mq_tx_log 实体先例处理 |
| `cloud-system/.../mapper/SysSharedSecretMapper.java` + `resources/mapper/SysSharedSecretMapper.xml`（新） | 手写 XML：`findLatestActive(scene)`（uk 最左前缀取 seq 最大生效行）/ `findMaxSeq(scene)` / `listBySceneStatus(scene,status)` 分页（IPage）/ `saveXxx` insert（自增主键，与 notify 一致）/ `updateStatus(id,status,retireTime,updateBy,updateTime)`；动态列 `<trim>/<set>` 且 `<if>` 体换行；审计时间 SQL `NOW()` |
| `cloud-system/.../config/InnerRotationProperties.java`（新） | `@ConfigurationProperties("cloud.inner.rotation")`：validityHours=168 / overlapSeconds=180 / leadHours=24 |
| `cloud-system/.../service/InnerSecretService.java`（新） | 设计 §5.2-§5.4：`pageListXxx` / `findRotationStatus()` / `rotate(operator)`（@Transactional + synchronized，频控 3053 → insert 新行 → 前任转重叠期 → 更早转过期，expire=now+validity、retire=now+overlap）/ `revoke(id,operator)`（3050/3051 校验 → status=4 + retire=now）/ `publishActiveSet(bridgeFromStatic)`（事务外，重算集= status∈{1,2} 且 now<min(expire,retire)，seq 降序 → JSON → StringRedisTemplate SET `inner:secret:active`（键常量 import starter）；**bridgeFromStatic=true（发布前 GET 发现键不存在且静态键非空）时并入静态过渡项**，expireAtMs=发布时刻+overlap+2×cacheTtl——首轮切换零缝隙，设计 §5.2/§6；连续失败 3 次 → 3052）/ `sweepAndRotate(operator)`（任务单趟：清扫→自愈重发→轮换判定→告警判定）/ `alertAdmin(content)`（`templateMapper.findByCode("inner_secret_alert")` + `NotifySendService.saveNotify(req, "innerSecretRotateJobHandler")`，receiverType=2 roleIds=[1]，variables={content}，linkUrl=`/system/inner-secret`；去重 Redis SET NX EX 3600 `inner:secret:alert:{tag}`；异常吞掉只 log.error——告警失败不阻断任务）。异常处理：catch log.error 后转 BusinessException；密钥真值不进日志 |
| `cloud-system/.../controller/manage/InnerSecretManageController.java`（新） | 四端点按 admin 契约 §0-§4：两行式返回 + javadoc + @PreAuthorize 三 perms + DTO 入参 VO 出参；rotate 返回新行 VO；revoke 入参 RevokeRequest |
| `cloud-system/.../dto/InnerSecretPageQuery.java`、`dto/RevokeRequest.java`、`vo/InnerSecretVo.java`、`vo/InnerSecretStatusVo.java`（新） | 字段按契约 §1/§2；**secretMask 掩码生成在 service 层**（`****`+尾4） |
| `cloud-system/.../job/InnerSecretRotateJobHandler.java`（新） | @XxlJob("innerSecretRotateJobHandler") 薄壳：调 `sweepAndRotate`，异常 log.error + 告警后上抛（任务失败态）；落 job/ 包（守护规则） |
| `cloud-system/src/test/java/.../service/InnerSecretServiceTest.java`（新） | mock mapper/StringRedisTemplate/NotifySendService：rotate 正常（断言前任 OVERLAP+retire、更早 EXPIRED）/ 3053 频控 / 3052 发布失败（DB 已写）/ revoke 正常 / 3050 / 3051 / sweep 乱序残留收敛 / 掩码生成 / 告警去重路径 |

**验收**：`mvn -f cloud-base/pom.xml clean install -pl cloud-system -am` 绿（含守护测试）；单测覆盖上列分支；Controller/Service/Mapper 分层与命名规范（findXxx/saveXxx/pageListXxx/updateXxx）符合 backend-spec。

### T4 Nacos 接线（运维步骤，4 次发布）

`cloud-system.yaml` 双租户（`''` 与 `public` 各一次，**整文件全量干净内容重发**）：① `cloud.inner.rotation.{validity-hours: 168, overlap-seconds: 180, lead-hours: 24}` ② `spring.data.redis.{host: 127.0.0.1, port: 6379}`（发布方连接显式化）。sso/bpmn 零改动。

**验收**：两租户同键同值、YAML 无重复键；旧 jar 运行中服务零异常（新键不被旧 jar 识别）。

### T5 重启、部署序与冒烟回归

1. **重启**：构建新 jar 后按任意序重启三服务+网关（v2 契约 §3 runbook：Redis 键未生成前全体回落静态键，行为=v1，无消费方先行约束）；启动日志无 inner WARN、无 Redis 连接错误。
2. **首轮轮换**：admin 登录（重新登录拿权限快照）→ 管理界面「立即轮换」（或等任务判定）→ Redis 键生成，系统切入活跃集模式。
3. **冒烟脚本**（新建 `cloud-base/scripts/smoke/smoke_inner_secret_rotation.sh`：静态密钥经环境变量 `INNER_SECRET` 传入（首轮过渡断言用，脚本不落真值）；轮换后各代密钥值从 Redis 现读——`docker ps` 找 6379 容器名 redis-cli GET `inner:secret:active`，python/jq 解析）：

| # | 断言 |
|---|---|
| 1 | 网关登录 admin → 200 拿 token |
| 2 | GET /system/inner-secret/status → code 200，redisConsistent=true，currentSeq≥1 |
| 3 | POST rotate → 200，seq+1；Redis 集两把：新钥 B（首位）+ **静态过渡项 $INNER_SECRET（尾位）** |
| 4 | 直连 9202 `GET /inner/user/all` 带 $INNER_SECRET → 200（**即刻**——首轮切换在途连续性断言，无过渡项则此处会 401）；带新钥 B → ≤40s 轮询内 200（收方 30s 本地缓存——「即刻」不在承诺范围，冒烟首轮实测修正） |
| 5 | 等待 overlap + 2×cacheTtl + 70s（过渡期+调度余量）→ $INNER_SECRET → 401；新钥 B → 200（零掉落断言收口） |
| 6 | 再 rotate（钥 C，此刻 B 转重叠期）→ 钥 B → 200（常规重叠窗断言）→ PUT revoke B 的 id → 200 → 等 cacheTtl+10s → 钥 B → 401、钥 C → 200（作废即断，≤TTL） |
| 7 | revoke 钥 C（最后一把）→ 3051 |
| 8 | 网关 18080 `/system/inner/user/all` → 403（外层屏蔽未动） |

4. **链路回归**：`cd cloud-e2e && npm run e2e`（有头）——**只认 `grep -c '[FAIL]'`=0**（exit 0 假绿陷阱）；xxl-job admin（18081）任务 id 12 下一分钟轮次调度日志无错；站内信告警链路抽查（临时停 Redis 容器触发 publish 失败 → admin 收到 inner_secret_alert 站内信 → 恢复容器验证自愈重发——可选，若时间紧记档移交）。
5. 通过后按主控惯例 kill 全部本轮起的进程交还。

**验收**：冒烟 8 断言全过；e2e `[FAIL]` 计数 0；任务轮次无错；F2 的 e2e:innersecret 亦绿（前端章产物）。

### T6 文档同步

| 文件 | 变更 |
|---|---|
| `CLAUDE.md` | ①「关键约定·服务间 inner 准入」条目改写：活跃密钥集语义 + Redis 键 `inner:secret:active` + 静态键兜底 + 粘性缓存 + 轮换任务 id 12 + 管理页/契约两份路径 ②「模块拓扑」inner-starter 描述行加「密钥生命周期来源抽象（Redis 活跃集+静态兜底）」 ③ xxl-job 约定段补任务 id 12 ④「Redis 会话契约」段补 inner:secret:active 键（单键 JSON、无 TTL） |
| `docs/superpowers/contracts/2026-10-07-inner-api.md` / `2026-10-08-approval-platform-api.md` | 无需新附记（v2 对外表现仍是密钥头+401，既有附记继续有效）——如实现中发现表述冲突，回报主控转架构处理，不自行修改 |

**验收**：三处与契约 v2 语义一致；无决策代号裸引用。

## 2. 前端章

### F1 API 封装 + 密钥管理页

| 文件 | 内容 |
|---|---|
| `cloud-web/src/api/innerSecret.ts`（新） | 四函数沿 `api/notify.ts` 形态：`pageInnerSecret(query)` / `getInnerSecretStatus()` / `rotateInnerSecret()` / `revokeInnerSecret({id})`；注释标 perms 与错误码 3050-3053 |
| `cloud-web/src/types/api.ts` | 加 InnerSecretVo / InnerSecretStatusVo / InnerSecretPageQuery / RevokePayload 类型（id 为 string，Long→String 口径） |
| `cloud-web/src/views/system/innerSecret/index.vue`（新） | 沿 notify 管理页结构：**状态卡**（el-descriptions 只读回显 validityHours/overlapSeconds/leadHours/currentSeq/activeCount/nextRotationTime + redisConsistent tag：true=「分发正常」success / false=「等待自动重发」warning）+ **列表**（列：seq/status el-tag 色板（1 success 生效中 / 2 warning 重叠期 / 3 info 已过期 / 4 danger 已作废）/secretMask/source 文案/activateTime/expireTime/retireTime/createBy；status 下拉过滤；el-pagination）+ **立即轮换**按钮（perms `system:innerSecret:rotate`，成功后刷新列表与状态卡并 toast 展示新 seq；3053/3052 toast 透出 msg）+ 行内**作废**按钮（仅 status∈{1,2} 行可见，perms `system:innerSecret:revoke`；ElMessageBox 确认文案含「将立即断开重叠窗内的在途服务间调用，最迟约 30 秒内生效」；3051/3050 toast）。无新增/编辑/删除——密钥不可编辑不可物理删 |
| `cloud-web/src/router/viewRegistry.ts` | 注册 `'/system/inner-secret'` → 新页面（import + 映射行） |

**验收**：`cd cloud-web && npm run build` 绿；dev 起后（后端未就绪时）路由可达、空态渲染不报错；Element Plus 组件按需引入（禁全量）；按钮权限控制沿 notify 页写法。

### F2 e2e 场景（route-mock，零后端依赖）

| 文件 | 内容 |
|---|---|
| `cloud-e2e/run-inner-secret-e2e.mjs`（新） | 沿 `run-notify-e2e.mjs` 先例（route mock 分层 + harness）：S1 登录+菜单「系统管理/密钥管理」→ 列表渲染（mock 四状态行 + 掩码列 + 表头）+ 状态卡回显；S2 立即轮换：点击 → mock 新行插入（seq+1、status=1，前任行变 2）→ toast；S3 频控 toast 3053 / 发布失败 toast 3052（mock code 分支）；S4 作废：确认弹窗文案含「30 秒」→ 行状态变已作废；最后一把 → 3051 toast；S5 掩码断言（`****` 开头，页面无明文形态字段）；S-VERIFY console/pageerror 卫生 |
| `cloud-e2e/package.json` | scripts 加 `"e2e:innersecret": "node run-inner-secret-e2e.mjs"`（单跑，不进 `e2e` 聚合——沿 aichat/notify 先例） |

**验收**：`cd cloud-e2e && npm run e2e:innersecret` 全绿（仅前端 dev 5173，后端零依赖）；`npm run e2e` 聚合回归不因新脚本引入而破。

## 3. 移交备忘（下一轮规划前必读）

1. **MCP client-secret 纳管**：表结构已留 scene 通用性（设计 D10/D12），纳管需扩 cloud-base-ai 取值通道，单独立轮。
2. **告警链路停容器实测**（T5 第 4 步可选项）：若本轮未执行，登记 `docs/handoff/` 欠账。
3. xxl-job 任务停摆检测（admin 告警邮箱为空）与 sso Redis 连接显式化：部署侧待办（设计 §11）。
4. 每服务独立密钥 / mTLS / Nacos auth：维持演进项。
5. **本轮验收欠账（2026-11-10 集成段记档，主控待办）**：
   - **全量 e2e 未取得绿判定**——两次尝试均被环境腰斩（11-09 深夜内存风暴绞杀服务；11-10 上午 Claude Code 后台任务内存压力收割器连杀四服务+e2e）。部分跑（11-10 上午，64 PASS/1 FAIL）已验证：R5 admin 绑定 68 封闭集、M1/D0/BP0 19 项串、N6 全绿；夹具六处漂移+nav path 串漏网（N1）均已修复但**未整链复验**；BP2 失败归因不明（恰逢内存临界窗口）；T6-T9 定性为内存压力假阳性（fill 卡顿+未关弹窗级联）未在健康环境复验。
   - **xxl-job 任务 12 自愈探针未跑**（DEL `inner:secret:active` → ≤65s 重发）。
   - **artifacts 失败截图未清**（两轮 S12+ 网关错误页等，待绿判定后删）。
   - **全部改动在工作区未提交**（含主控修的 AuthGlobalFilter 尾斜杠段感知、契约 v2 §3/§6 精确化、CLAUDE.md 三处+尾斜杠条目、e2e 六脚本夹具），待 e2e 绿判定后征用户确认提交。
   - 环境事故记档（非本仓问题）：Docker Desktop 升级失败粘滞恢复对话框阻塞引擎（AutoUpdate 已关仍弹，点 Continue 解锁）；引擎重启后 rmqnamesrv/rmqbroker（退出码 255）未被 restart 策略自动恢复，需手动 `docker start`。

**2026-11-12 收口结果：上述欠账全部清偿**——①全量 e2e 取得绿判定（[FAIL]=0，81 PASS，7 脚本全链；首跑被内存压力收割器腰斩遗留的孤儿角色 e2e1112121915 清理后复跑转绿，两处 CLEANUP 断言失败确认为该孤儿所致非产品回归）；②任务 12 自愈探针达标（DEL 活跃集键后 45s 重发，≤65s 要求内；期间发现宿主 MySQL 停机导致 xxl-admin 连接池死亡，`docker restart xxl-job-admin` 后恢复，属环境事故非代码问题）；③artifacts 截图 162 张全清；④改动随当日 commit 入库（notify 契约 §6 预留段转正经用户追认）。

## 4. 给 backend-agent 的任务清单（完整任务书）

**任务**：实现 inner 面共享密钥生命周期管理（后端全部）。三份文档已定稿：设计 `docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md`（组件改造 §4 / service 编排 §5 / Redis 集结构 §6 / DDL §7）、契约 `docs/superpowers/contracts/2026-11-09-inner-access-secret-api.md`（v2 运行时分发语义）+ `2026-11-09-inner-secret-admin-api.md`（管理端点）、本计划 §1（T1-T6 逐任务验收）。**开工前通读**；冲突时以契约为准，仍歧义回报主控问架构。

**执行序**：T1 种子（pymysql 落库+前置断言）→ T2 starter 抽象（六分支单测先行）→ T3 system 管理面+任务 → T4 Nacos 双租户发键 → T5 按任意序重启+首轮轮换+冒烟 8 断言+全量回归（只认 `[FAIL]`=0）→ T6 CLAUDE.md 四处。关键约束：密钥真值不进任何文件/日志/回报（冒烟中静态密钥经环境变量 `INNER_SECRET`、轮换后各代密钥从 Redis 现读）；starter Redis 依赖 provided 条件化（无 Redis 宿主必须保持 v1 行为——单测断言）；发布在事务外；401 报文与常量时间比对语义不得改动；xxl-job 类落 job/ 包；守护测试全绿后才算完成。红线：不做 git 提交；不动 fallbackFactory/超时配置；不改网关；契约文档不自改。

## 5. 给 frontend-agent 的任务清单（完整任务书）

**任务**：实现密钥管理页 + e2e。对齐物：`docs/superpowers/contracts/2026-11-09-inner-secret-admin-api.md`（端点/字段/枚举/错误码/种子全在）；结构先例 `cloud-web/src/views/system/notify/index.vue` + `cloud-web/src/api/notify.ts`（最新管理页样板，列表/分页/toast/按钮权限写法照此）；e2e 先例 `cloud-e2e/run-notify-e2e.mjs`（route-mock 分层、harness 用法、N-VERIFY 卫生段）。本计划 §2（F1/F2 文件清单与验收）。

**执行序**：F1 api+页面（types → api → 页面 → viewRegistry，build 绿）→ F2 e2e runner + package.json script，`npm run e2e:innersecret` 全绿。关键约束：组件按需引入铁律；真值永不显示（掩码列 `****` 尾 4，页面任何形态不出现明文字段——契约红线）；状态卡参数**只读**（无编辑控件）；作废确认弹窗必须含「立即断开…最迟约 30 秒内生效」文案；3050-3053 toast 透出后端 msg。红线：不做 git 提交；不 import 后端/cloud-base 内部代码；不改既有页面；e2e 不进聚合 script。
