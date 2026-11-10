# inner 面共享密钥生命周期管理（定时轮换 + 管理界面）——技术方案

- 日期：2026-11-09
- 状态：已定稿（需求分析七问经用户拍板，结论落档本文 §3）
- 关联文档：契约修订 `docs/superpowers/contracts/2026-11-09-inner-access-secret-api.md`（v2，运行时分发语义）；管理面契约 `docs/superpowers/contracts/2026-11-09-inner-secret-admin-api.md`（前后端唯一对齐物）；实施计划 `docs/superpowers/plans/2026-11-09-inner-secret-rotation.md`
- 前置阅读：`docs/superpowers/specs/2026-11-09-inner-shared-secret-design.md`（v1 静态密钥准入方案，本文是其演进）
- 阅读对象：第一次接触本项目的实现者。本文自带全部背景。

## 1. 背景：静态密钥撑不起「定时轮换」

上一轮（2026-11-09 上午）落地的 inner 面准入是**静态共享密钥**：一把密钥写死在 Nacos per-service yaml（3 服务 × 双租户 6 份副本），入站过滤器与出站 Feign 拦截器每请求读配置值比对/注头。该方案没有生命周期概念——密钥永不过期、轮换 = 人工改 6 份 Nacos 配置并按序重启服务。

本需求把它升级为**受管生命周期的实体**：密钥有生效/过期时间、xxl-job 定时自动轮换（新钥提前生成、新旧重叠过渡、旧钥到点退场）、系统管理界面可视可操作。静态单键槽在结构上放不下「新旧两把同时有效」的重叠窗，因此核心是引入**运行时分发通道**。

## 2. 方案总览与数据流

三块组成（模型 A，拍板采纳）：

1. **DB 真相源**：新表 `sys_shared_secret`（cloud_system 库）承载密钥全生命周期记录与审计；
2. **Redis 运行时分发**：cloud-system 把「活跃密钥集」发布到 Redis 单键（全量幂等重写），全部服务的 inner-starter 读该键并本地短缓存；
3. **静态 Nacos 键兜底**：Redis 键不存在时回落 v1 静态配置值——存量部署零迁移零重启，Redis 键被误删时的应急通路。

轮换数据流（一次定时轮换）：

```
xxl-job（admin 任务 id 12，每分钟）──触发──▶ cloud-system InnerSecretService
  ① DB 事务：insert 新密钥行（status=1 生效中，expire_time=now+有效期）
             update 前任行 status=2 重叠期，retire_time=now+重叠窗
  ② 事务提交后：活跃集重算（status∈{1,2} 且 now < 有效退场时刻）→ Redis 单键全量重写
  ③ 各服务 inner-starter：本地缓存过期（≤30s）后拉到新集
       出站拦截器改注最新钥；入站过滤器集合内任一命中即放行
  ④ 前任钥自然退场：重叠窗耗尽后，下一轮任务把它移出活跃集（此时全体发送方早已换注新钥）
```

管理数据流：cloud-web 密钥管理页 → cloud-system 管理端点（分页/状态卡/立即轮换/作废）→ 直接读写 DB 与 Redis 单键，与任务共用同一条轮换代码路径。

## 3. 关键决策（拍板结论落档）

| # | 决策 | 结论 | 要点 |
|---|---|---|---|
| D1 | 分发模型 | DB 真相源 + Redis 单键活跃集 + starter 来源抽象 + 静态键兜底 | 否决 Nacos 热加载路线：单键槽放不下重叠窗、自动化写 Nacos 踩双租户整文件重发坑、管理页状态展示依赖 Nacos API（论证见需求分析，本文档不重复） |
| D2 | 失稳语义 | 粘性缓存续服务 | Redis 不可达但本地曾拿到过密钥集 → 剔除其中已过期项后**继续服务**（轮换阻塞+告警）；冷启动且 Redis 不可达且无静态键 → 恒 401（fail-closed 原则不破）。静态兜底**仅在「Redis 可达且确认键不存在」时启用**——Redis 报错不等于键不存在，不回落静态 |
| D3 | Redis 键 | `inner:secret:active` 单键，原始 JSON 字符串 | 结构见 §6；无键 TTL（生命周期由任务显式管理，设 TTL 会在任务停摆时误触发静态兜底复活旧钥） |
| D4 | 缓存与零掉落断言 | 本地缓存 TTL 默认 30s；重叠窗默认 180s | 重叠窗 ≥ 收方 TTL + 发方 TTL + 任务调度周期(60s) 余量。发送方换注新钥 ≤TTL、接收方接受集更新 ≤TTL，窗口内新旧互通，**可证明无 401 缝隙**。冒烟脚本按此断言实证 |
| D5 | 轮换参数 | Nacos `cloud.inner.rotation.*`，界面只读展示 | validity-hours 默认 168（7 天）/ overlap-seconds 默认 180 / lead-hours 默认 24（提前轮换预算——任务失败时的重试窗口）。每把密钥的 expire_time 在创建时快照落行，改参数不追溯既有密钥 |
| D6 | 强制轮换语义 | 到期绝不悄悄续期 | 任务在「剩余寿命 < lead」时轮换；若轮换连续失败直至唯一密钥过期，任务发布**空活跃集** → 全体服务 401（宁断不旧），同时告警。过期密钥即使 Redis 不可达也被本地时钟剔除（粘性缓存里同样过期） |
| D7 | 告警 | 日志 ERROR + 站内信通知 admin 角色 | 复用站内信中心（模板按 code 取用——`findByCode` 与 `NotifySendService.saveNotify(req, operator)` 双参重载是 MQ 消费域既有先例）；任务域无登录态，operator 传 handler 名。去重：同一告警类别每小时至多一条（Redis 标记键 TTL 1h） |
| D8 | 管理操作 | 列表（掩码）/ 立即轮换 / 作废 | 作废立即把目标移出活跃集，**生效时延 ≤ 缓存 TTL**（各服务缓存内旧值最迟 30s 后失效，非瞬时——契约与 UI 文案如实标注）；作废最后一把生效密钥被拒（防自断，先轮换再作废两步可达应急语义）；轮换频控：上一轮重叠窗未结束拒绝 |
| D9 | 真值策略 | DB 明文 + 接口永不返回明文 | 分发链路（Redis 集、Feign 头）必须持明文，DB 加密只防单点且防不了 Redis，不值；列表掩码 `****` + 尾 4 字符（仅运维辨识用）。沿 MCP 凭据「真值不出 API」先例 |
| D10 | 表设计 | `sys_shared_secret`，`scene` 列留通用性 | scene 默认 `'inner'`，结构与唯一键按 (scene, seq) 设计——未来 MCP client-secret 等纳管加行即可，不写死 inner 专用；追加式流水（不可编辑、不可物理删、状态单调推进、无 deleted 列，沿 mq_tx_log 先例） |
| D11 | 定时任务 | xxl-job admin 任务 **id 12**，cloud-system 执行器组 3 | CRON 每分钟 `0 * * * * ?`，SERIAL_EXECUTION，retry 0（下一分钟自然重试）。@Scheduled 全仓清零纪律沿用 |
| D12 | 范围边界 | MCP client-secret 纳管记演进 | 本轮机制通用性预留（D10），不实现；每服务独立密钥/mTLS/Nacos auth 维持既有演进项 |

## 4. 组件改造：cloud-common-inner-starter 密钥来源抽象

改造原则：**引 jar 即生效、宿主零业务改动、依赖纪律不破**——Redis 相关依赖 provided + 条件化装配，宿主无 Redis 时行为与 v1 完全一致。

### 4.1 类清单（改造后）

```
com.cloudai.common.inner
├── auth/InnerAccessFilter        改：比对目标从 InnerProperties 单值改为 InnerSecretSource 集合
├── client/InnerSecretInterceptor 改：注头值改为 InnerSecretSource 最新钥
├── core/InnerSecretSource        新：来源接口——acceptedSecrets() 入站接受集 / injectSecret() 出站注头值
├── core/StaticInnerSecretSource  新：包装 InnerProperties（v1 行为：静态单值）
├── core/RedisInnerSecretSource   新：读 Redis 活跃集 + 本地缓存 + 粘性降级 + 本地过期剔除
└── config/
    ├── InnerProperties           改：新增 cacheTtlSeconds（默认 30，键 cloud.inner.cache-ttl-seconds，默认值即最优，无 Nacos 变更）
    └── CloudInnerAutoConfiguration 改：装配来源抽象（见 4.3）
```

### 4.2 解析算法（RedisInnerSecretSource，每次请求走缓存不直连 Redis）

```
resolve()：
  缓存未过期（now - lastLoad < ttl 且已有结果）→ 返回缓存（其中已剔除过期项）
  访问 Redis GET inner:secret:active：
    键存在   → 解析 JSON → 本地时钟剔除 expireAtMs <= now 的项 → 存缓存 → 返回
    键不存在 → 回落静态值（StaticInnerSecretSource；静态也未配置 → 空集）→ 存缓存 → 返回
    访问报错 → 有粘性缓存（上次成功结果）→ 剔除过期项后返回（D2 续服务）
               无粘性缓存 → 空集（fail-closed：出站跳过注头、入站恒 401）

acceptedSecrets() = resolve() 的集合；injectSecret() = 集合第一项（发布时最新钥排首位）
```

- 解析失败（JSON 损坏）按「访问报错」分支处理（粘性或空集），log.warn 记原始长度不记内容；
- 线程模型：不可变快照对象 + volatile 引用，刷新单线程执行（synchronized 单飞，本规模无惊群问题）；
- 拒绝/放行语义与 v1 逐条一致：集合空 = 服务无可用密钥 = 恒 401；报文统一不区分缺头/错值/空集（不给校验进度 oracle）。

### 4.3 装配与依赖

- pom 新增 `org.springframework.data:spring-data-redis`（**provided**，版本随根 pom Boot BOM）——仅编译期引用 `StringRedisTemplate`；Boot 的 RedisAutoConfiguration 自带 stringRedisTemplate bean，本仓 common-redis-starter 只覆盖 redisTemplate 不碰它，三服务 classpath 全有（sso 直依赖、system/bpmn 经 translate-starter 传递）。
- 装配：`StaticInnerSecretSource` 恒注册；`RedisInnerSecretSource` 在 `@ConditionalOnClass(StringRedisTemplate.class)` + `@ConditionalOnBean(StringRedisTemplate.class)` 且类加载于 Redis 自动装配之后（`@AutoConfiguration(after = RedisAutoConfiguration.class)`，缺类时 after 按名安全忽略）时注册，并以 `@Primary` 作为过滤器/拦截器的注入来源；无 Redis 宿主回落 static-only。
- 既有四分支过滤器单测、拦截器两态单测**语义保留**，仅构造入参从 InnerProperties 改为包 StaticInnerSecretSource 的来源对象——v1 行为是本方案的真子集，这是回归安全的结构性保证。

## 5. cloud-system 管理面与轮换任务

### 5.1 类清单（新增）

```
com.cloudai.system
├── controller/manage/InnerSecretManageController  四端点（契约 2026-11-09-inner-secret-admin-api）
├── service/InnerSecretService                     轮换/作废/分页/状态卡 + 活跃集发布 + 任务编排逻辑
├── job/InnerSecretRotateJobHandler                @XxlJob("innerSecretRotateJobHandler") 薄壳（调 service）
├── entity/SysSharedSecret                         嵌套 StatusEnum{ACTIVE(1)/OVERLAP(2)/EXPIRED(3)/REVOKED(4)}
│                                                  与 SourceEnum{JOB(1)/MANUAL(2)}，字段类型 Integer（规范第 8 条）
├── mapper/SysSharedSecretMapper + resources/.../SysSharedSecretMapper.xml
├── dto/InnerSecretPageQuery、RevokeRequest        （分页查询/作废入参对象）
├── vo/InnerSecretVo、InnerSecretStatusVo           （掩码出参）
└── config/InnerRotationProperties                 @ConfigurationProperties("cloud.inner.rotation")：
                                                   validityHours=168 / overlapSeconds=180 / leadHours=24
```

### 5.2 轮换编排（任务与手动共用，service 内 `synchronized`——单实例部署现实下最简防并发，uk 兜底）

`rotate(operator)`（@Transactional，DB 部分）：
1. 频控：最新生效行 `activate_time + overlap > now` → 3053（上一轮重叠窗未结束）；
2. insert 新行：seq = max+1，status=1，activate=now，expire=now+validity，source 按 operator（job 名=1 / 账号=2）；
3. update 前任：status=1→2，retire_time = now + overlap；更早的 status=2 行 → 3；
4. seq 取 `MAX(seq)+1` 且 uk(scene,seq) 兜底并发。

`publishActiveSet(bridgeFromStatic)`（**事务外**，Redis 单 SET 幂等全量重写）：集 = status∈{1,2} 且 `now < min(expire_time, retire_time ?? ∞)` 的行，按 seq 降序；**静态过渡项**——当本次发布是「键不存在 → 键存在」的切换（首轮轮换、或自愈重建误删的键）且静态 Nacos 键非空时，把静态值并入集尾、`expireAtMs = 发布时刻 + overlap + 2×cacheTtl`（吸收切换瞬间「接收方已刷新只认新集、发送方还在发静态旧钥」的 ≤TTL 缝隙——无此项则首轮轮换存在最长 30s 的 401 窗口，与零停机承诺矛盾）；连续失败 3 次抛 3052（DB 已落，任务一分钟内自愈重发）。

Controller/Job 调用序：`rotate()` 提交 → `publishActiveSet()` →（job 侧失败则告警 + 上抛任务失败）。

### 5.3 任务每分钟单趟逻辑（InnerSecretRotateJobHandler → service）

1. **清扫**：status∈{1,2} 且 `now >= min(expire_time, retire_time ?? ∞)` → status=3；非最新且无 retire_time 的 status=1 行（并发轮换乱序残留）→ 补 status=2 + retire_time=now+overlap；
2. **自愈**：Redis 键缺失或 version 落后 DB max(seq) → 重发布（Redis 误删/发布失败的收敛路径）；
3. **轮换判定**：最新生效行剩余寿命 `expire_time - now < lead` → rotate + publish；
4. **告警判定**（去重后站内信 + log.error）：活跃集为空 →「inner 面已全体拒绝，请立即在管理界面轮换」；轮换/发布动作抛异常 →「失败原因摘要」（不含密钥真值）。任务整体停摆的检测不在本轮（xxl-job admin 告警邮箱是部署侧待办，移交备忘记档）。

### 5.4 作废 `revoke(id, operator)`

目标须存在且 status∈{1,2}（否则 3050）；若它是活跃集内最后一行 → 3051（先轮换再作废）；否则 status=4 + retire_time=now，事务提交后重发布（失败 3052）。生效时延 ≤ 缓存 TTL（30s）——UI 确认弹窗明示。

## 6. Redis 活跃集键与结构

| 项 | 值 |
|---|---|
| 键 | `inner:secret:active`（starter 常量，发布方 cloud-system 与消费方共用同一常量类，单一事实源） |
| 值 | StringRedisTemplate 原始 JSON 字符串（不经 common-redis-starter 的 JSON value 序列化器——独立于宿主模板口径） |
| 结构 | `{"version":7,"secrets":[{"value":"…","expireAtMs":1763400000000},{"value":"…","expireAtMs":1763000000000}]}` —— version=DB max(seq)；secrets 按 seq 降序（首位=出站应注入的最新钥）；expireAtMs=该行 `min(expire_time, retire_time)` 的 epoch 毫秒快照（**一个字段同时承载过期与退场两语义**，消费方本地时钟统一剔除）；空集 = `{"version":N,"secrets":[]}` 显式发布（区别于键不存在——空集=全体 401，键不存在=回落静态）；**静态过渡项**只在「键不存在→键存在」的切换发布中出现一次（§5.2），过渡期耗尽后自然被本地时钟剔除 |

时间用 epoch 毫秒而非日期串：消费方比较无需时区/格式解析，规避 GMT+8 口径歧义。

## 7. DDL 与索引设计

表落 cloud_system 库（种子 `cloud-base/scripts/sql/2026-11-09-inner-secret-rotation.sql`，基线 `cloud_system.sql` 同步）：

```sql
CREATE TABLE sys_shared_secret (
  id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '密钥ID',
  scene         VARCHAR(30)  NOT NULL DEFAULT 'inner' COMMENT '密钥场景：inner=服务间inner面共享密钥（预留未来其他场景如MCP client-secret 复用本表）',
  seq           INT          NOT NULL COMMENT '轮换代数（场景内单调递增，活跃集 version 由此取 MAX）',
  secret_value  VARCHAR(128) NOT NULL COMMENT '密钥真值（32字节随机base64约44字符；仅运行时分发，任何接口永不返回）',
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态：1生效中 2重叠期（已有继任者） 3已过期 4已作废',
  source        TINYINT      NOT NULL DEFAULT 1 COMMENT '来源：1定时任务 2手动操作',
  activate_time DATETIME     NOT NULL COMMENT '生效时间（创建即生效）',
  expire_time   DATETIME     NOT NULL COMMENT '过期时间（=生效时间+有效期，创建时快照）',
  retire_time   DATETIME     DEFAULT NULL COMMENT '退场时间（被继任者顶替时=轮换时刻+重叠窗；NULL=未被顶替；与expire_time取小者为实际退场时刻）',
  create_by     VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '创建人（定时任务=handler名，手动=操作账号）',
  create_time   DATETIME     DEFAULT NULL COMMENT '创建时间（SQL NOW()）',
  update_by     VARCHAR(30)  DEFAULT NULL COMMENT '更新人（状态推进时=handler名或账号）',
  update_time   DATETIME     DEFAULT NULL COMMENT '更新时间（SQL NOW()）',
  PRIMARY KEY (id),
  UNIQUE KEY uk_scene_seq (scene, seq),
  KEY idx_scene_status (scene, status)
) ENGINE = InnoDB COMMENT = '共享密钥生命周期表（追加式流水：不可编辑不可物理删，状态单调推进）';
```

索引取舍（每索引注明命中查询）：
- `uk_scene_seq`：①轮换取 `MAX(seq)+1`；②并发轮换/重复发布的幂等兜底（DuplicateKeyException 吸收）；③「最新生效行」按 scene 取 seq 最大行（最左前缀 scene 等值 + seq 取 max）。
- `idx_scene_status`：①分页列表默认场景按 status 过滤；②任务清扫「status∈{1,2} 且退场时刻已过」的高频每分钟扫描。表极小（每场景每次轮换 1 行，年增约 500 行），两索引已足，不为掩码/审计列建索引（无查询路径）。

## 8. 错误处理与错误码

- 管理面错误码（system 域 3xxx 段接续站内信轮 3049）：**3050** 密钥记录不存在或已退场 / **3051** 禁止作废最后一把生效密钥（先轮换）/ **3052** 活跃集发布失败（变更已落库，任务将于一分钟内自动重试）/ **3053** 轮换过于频繁（上一轮重叠窗口未结束）。
- 401 语义零变化：入站拒绝仍是 HTTP 401 + R JSON 统一报文（v2 契约沿用），不占分段账本；出站 401 消费方处理复用既有 FeignException→fallback 链。
- Service 层 catch 异常 log.error 记根因后转业务异常（规范第 4 条）；密钥真值不得进任何日志与异常消息。
- 任务失败不阻断下一分钟重试；xxl-job retry 0（自然重试即下一调度轮）。

## 9. 测试策略

| 层 | 内容 |
|---|---|
| 组件单测（改造后全量重跑） | RedisInnerSecretSource：缓存命中不访 Redis / 键存在解析+过期剔除 / 键不存在回落静态 / 访问报错有粘性续服务 / 冷启动报错空集 / JSON 损坏按报错分支；InnerAccessFilter：集合命中任一放行、空集 401、无头 401、错值 401；InnerSecretInterceptor：最新钥注头、空集跳过；自动装配：有 StringRedisTemplate → Redis 来源 @Primary 装配、无 Redis classpath → static-only |
| system 单测 | InnerSecretService：rotate 正常（前任转重叠期/更早转过期）/ 频控 3053 / 发布失败 3052 / revoke 正常 / 3050 / 3051 / 清扫 normalize（乱序残留收敛）；mapper XML 走单测容器或 mock（沿 notify 轮 service 单测形态） |
| 真连接冒烟 | 新脚本 `cloud-base/scripts/smoke/smoke_inner_secret_rotation.sh`（静态密钥经环境变量 `INNER_SECRET` 传入——首轮过渡断言用）：登录→status→手动轮换→新钥即刻 200 + 静态旧钥过渡期内 200→过渡期耗尽后静态旧钥 401 新钥 200→再轮换+作废前任→TTL 后被作废钥 401→作废最后一把 3051（断言清单见计划 T5） |
| e2e | cloud-e2e 新 runner（route-mock 零后端依赖，沿 run-notify-e2e 先例）：列表/状态卡/轮换/作废弹窗与 toast/掩码列/console 卫生 |
| 链路回归 | cloud-e2e 全量（只认 `grep -c '[FAIL]'`=0）——覆盖登录链（sso→system inner 调用在轮换后仍通，是本方案最重要的回归面）；xxl-job 任务 id 12 下一分钟轮次无错 |

## 10. 配置与 Nacos 变更清单（实施时逐项核对）

| 变更 | 位置 | 说明 |
|---|---|---|
| `cloud.inner.rotation.{validity-hours, overlap-seconds, lead-hours}` | Nacos `cloud-system.yaml`（双租户整文件干净重发 ×2） | 默认 168/180/24；仅 cloud-system 需要（任务与管理面在此） |
| `spring.data.redis.{host: 127.0.0.1, port: 6379}` | 同上（cloud-system.yaml 双租户） | system 现靠 localhost 默认值隐式连通（经 translate-starter 传递依赖），发布方连接显式化 |
| `cloud.inner.shared-secret` 静态键 | 三服务现有 6 份**不动** | 语义降级为引导/兜底值（Redis 键不存在时回落）；长期保留作应急通路 |
| `cloud.inner.cache-ttl-seconds` | 无需配置 | starter 默认 30 即定稿值 |
| bpmn/sso | 零改动 | bpmn 已显式配 Redis；sso 经 Redis 默认值连通（会话既如此，沿用；显式化记移交） |

## 11. 移交备忘（演进项）

1. **MCP client-secret 纳管**（拍板记演进）：表结构已留 scene 通用性；纳管时需扩展 cloud-base-ai 侧取值通道，单独立轮。
2. **xxl-job 任务停摆检测**：轮换任务自身失败有告警；executor/admin 整体停摆的检测属 xxl-job admin 告警邮箱（现为空）——部署侧待办。
3. **sso Redis 连接显式化**：现靠默认值，建议后续随任意 sso 配置变更顺带显式化。
4. 每服务独立密钥 / mTLS / Nacos auth：维持 v1 方案演进项不变。
5. 密钥表无清理任务：年增约 500 行，量级百年无虞；若未来纳管高频场景再议清理。
