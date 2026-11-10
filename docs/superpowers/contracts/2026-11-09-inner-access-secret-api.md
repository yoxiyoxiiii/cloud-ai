# 服务间 /inner 面准入契约（共享密钥）

- 日期：2026-11-09
- 状态：**inner 准入现行版（v2）**——由架构-agent 定稿（设计 `docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md`；v1 原文见本文 §8 沿革附录）
- **域性质（先读）**：本契约是**后端服务间横切关注点**——`/inner/**` 面的准入与密钥分发语义，横跨 system 域（`2026-10-07-inner-api.md`）与 bpmn 域（`2026-10-08-approval-platform-api.md` §4）两份既有 inner 契约，**不是前端对齐物**：前端不得调用任何 /inner/** 端点（网关 403 是设计行为）。**密钥管理界面端点**是独立的前后端契约：`2026-11-09-inner-secret-admin-api.md`
- 约定：发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单

**v2（同日晚，密钥生命周期轮）**：

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | 准入语义 | 校验目标从「单把静态密钥」改为「**活跃密钥集**」——header 值命中集合任一即放行；集合空=恒 401 | 行为泛化（v1 是集合={静态值} 的特例） |
| 2 | 分发通道 | 新增 **Redis 活跃集**为运行时来源（键 `inner:secret:active`）；静态 Nacos 键降级为**引导/兜底值**（Redis 确认键不存在时回落） | 分发机制变更（调用方/被调方协议零变化——仍是 `X-Inner-Secret` 头） |
| 3 | 组件 | `cloud-common-inner-starter` 新增密钥来源抽象（§2）；Redis 依赖 provided 条件化，无 Redis 宿主行为同 v1 | 组件增强 |
| 4 | 失稳语义 | 相对 v1 **唯一软化**：Redis 不可达但本地粘性缓存有最近密钥集 → 剔除过期项后继续服务（轮换阻塞+告警，准入不停）；冷启动且 Redis 不可达且无静态键仍恒 401 | 可用性语义（理由：登录链 sso→system 在 inner 面上，粘性缓存让准入不因 Redis 抖动中断；「曾拿到可信密钥」与「从未配置」是不同的信任态） |
| 5 | 生命周期 | 密钥有过期时间与定时轮换（xxl-job id 12，cloud-system）；重叠窗内新旧两把同时有效 | 新机制（管理面见 admin 契约） |

v1 变更点清单（静态准入落地）见 §8。

## 1. 准入语义（v2）

- **请求头**：`X-Inner-Secret`（不变），值为密钥原文
- **校验**：被调方维护**活跃密钥集**（通常 1 把；轮换重叠窗内 2 把），header 值与集合内**每把**做 `MessageDigest.isEqual` 常量时间比对（UTF-8 字节），命中任一即放行。三态一律拒绝：**未携带 / 值不在集合内 / 集合为空**（空集 = fail-closed，拒绝全部，不阻断启动）
- **拒绝响应**：真实 HTTP 401 + R JSON 统一报文（网关 AuthGlobalFilter / MCP McpAuthFilter 先例）：

```json
{ "code": 401, "msg": "inner 认证失败", "data": null }
```

- 统一报文**不区分**缺头/错值/空集（不给校验进度 oracle）；服务端日志内部记录原因 + uri + remoteAddr
- **通过后的行为零变化**：请求进入既有 spring security 链，主体匿名；inner 端点无 @PreAuthorize 的口径不变；密钥认证的是「服务」主体
- **鉴权边界不变**：网关 `inner-block-*` 三路由 SetStatus 403 保留为外层；MQ/xxl-job 不在本契约范围

## 2. 密钥来源与组件装配（cloud-common-inner-starter）

- **来源抽象** `InnerSecretSource`：`acceptedSecrets()`（入站接受集，已按本地时钟剔除过期项）/ `injectSecret()`（出站注入的最新钥）。`InnerAccessFilter` 与 `InnerSecretInterceptor` 依赖该接口而非配置直读
- **装配**：`StaticInnerSecretSource`（包装 `cloud.inner.shared-secret`，v1 行为）恒注册；classpath 有 `StringRedisTemplate` 时追加 `RedisInnerSecretSource` 并以 @Primary 生效——**无 Redis 宿主自动回落 static-only，行为与 v1 一致**（引 jar 即生效、无 enabled 开关的语义不变）
- **解析优先级（每服务本地，缓存 TTL 默认 30s，键 `cloud.inner.cache-ttl-seconds` 可调）**：
  1. Redis 键存在 → 集合内剔除 `expireAtMs <= now` 项后采用（**过期密钥即使 Redis 不可达也被本地时钟剔除——绝不悄悄续期**）
  2. Redis 可达但键不存在 → 回落静态 Nacos 值（引导/兜底：首轮部署前、Redis 键被误删的应急通路）
  3. Redis 访问报错 → 有粘性缓存（最近一次成功结果）则剔除过期项后续用；从未成功过 → 空集（fail-closed）
- **Redis 活跃集结构**（发布方 cloud-system 单键全量幂等重写；值经 StringRedisTemplate 原始 JSON）：

```json
{ "version": 7, "secrets": [ { "value": "…", "expireAtMs": 1763400000000 } ] }
```

  version = DB `sys_shared_secret` 场景 inner 的 MAX(seq)；secrets 按 seq 降序（首位 = 出站应注入的最新钥）；`expireAtMs` = 该行 `min(expire_time, retire_time)` 的 epoch 毫秒（同时承载过期与重叠期退场两语义）；**空集显式发布** `{"version":N,"secrets":[]}` = 全体 401（区别于键不存在 → 静态兜底）；**静态过渡项**——仅当本次发布是「键不存在 → 键存在」的切换（首轮轮换或自愈重建）时，静态 Nacos 值并入集尾、`expireAtMs = 发布时刻 + overlap + 2×cacheTtl`，吸收切换瞬间收发双方缓存不同步的缝隙，过渡期耗尽后自然剔除；键无 TTL，生命周期由轮换任务显式管理
- **出站拦截器**：`injectSecret()` 非空则注头，空则跳过（不加空头）；全部声明式 Feign client 自动覆盖（v1 验证结论：FeignClientBuilder 程序式 client 亦继承，translate-remote-starter 零特判）

## 3. 配置与 Nacos 放置（v2）

| 项 | 值 |
|---|---|
| 静态兜底键 `cloud.inner.shared-secret` | 三服务 per-service yaml 既有 6 份**不动**（双租户同内容纪律不变）；语义 = 引导/兜底值，长期保留 |
| Redis 活跃集 | 键 `inner:secret:active`，Java 侧 6379 共享实例；三服务 classpath 全有 RedisTemplate（sso 直依赖；system/bpmn 经 translate-starter 传递），**引 starter 即生效无需新增服务配置**（system 侧连接显式化见下） |
| 轮换参数 `cloud.inner.rotation.{validity-hours=168, overlap-seconds=180, lead-hours=24}` | 仅 Nacos `cloud-system.yaml`（双租户整文件干净重发）——任务与管理面只在 cloud-system |
| `spring.data.redis.{host,port}` | `cloud-system.yaml` 显式化（发布方连接不再依赖 localhost 默认值）；bpmn 已显式、sso 沿用默认值（移交备忘） |
| 真值纪律 | 密钥真值仅存 DB `sys_shared_secret`、Redis 活跃集、静态 Nacos 键三处；不入仓库、不入文档、不入日志、任何接口不返回明文 |

**零停机轮换交接（可验证断言）**：连续性由**旧侧**承担——常规轮换重叠窗（默认 180s）≥ 接收方缓存 TTL(30s) + 发送方缓存 TTL(30s) + 任务调度周期(60s)，旧钥在整个重叠窗内被所有收方接受，任何仍持旧缓存的收发组合互通**无断**；新钥在各收方本地缓存刷新后（≤30s）被接受。**边界（2026-11-09 冒烟实证修正）**：发送方先于接收方完成缓存刷新的瞬间，新钥请求会被仍持旧快照的收方 401（≤30s 收敛窗口内的暂态），由既有降级链吸收（登录 2002 重试恢复 / MQ 消费重试 / 对账下轮补拉）——「新钥即刻可用」不在承诺范围；首轮切换（静态→活跃集）由静态过渡项承担同款旧侧连续性（见 §2）。冒烟脚本 `smoke_inner_secret_rotation.sh` 实证：过渡项/旧钥即刻通 + 新钥 ≤40s 轮询收敛。

**发布 runbook（v2 部署序）**：① 落种子 SQL（表/菜单/模板/xxl-job id 12）→ ② Nacos `cloud-system.yaml` 双租户发轮换参数与显式 Redis → ③ 构建重启（无特殊顺序——Redis 键尚不存在，全体回落静态键，行为=v1）→ ④ 管理界面手动轮换一次（或等任务判定）：Redis 键生成，全体服务 ≤30s 切入活跃集模式。**全程无 401 窗口，无消费方先行约束**。

## 4. 消费方调用点对照表（现状全集，v1 验证沿用，零改动覆盖）

| 调用链 | 客户端 | 线程形态 | 无用户 token | 降级路径（既有语义） |
|---|---|---|---|---|
| sso→system 登录/refresh 取用户 | SystemUserClient | 登录请求线程 | 是 | 登录报 2002，重试恢复 |
| bpmn→system 审批人校验 | SystemUserClient | MQ 消费线程 | 是 | 消费重试（超窗 DLQ 人工） |
| bpmn→system 数据权限求值 | DataPermClient | 请求线程 | 服务级调用 | 求值降级 |
| bpmn→system 翻译回源 | SystemTranslateClient（程序式，继承性已验证） | 请求/缓存线程 | 是 | 空译文 |
| system→bpmn 发起/撤销审批 | BpmnApprovalClient | 请求+MQ 事务线程 | 是 | 中性失败 R 转译 3022 |
| system→bpmn 投影对账 | BpmnApprovalClient | xxl-job 线程 | 是 | 本轮放弃，下轮补拉 |

## 5. 既有契约附记（v1 落地时已粘贴，继续有效）

- `2026-10-07-inner-api.md` §5.1「无认证」口径废止附记、`2026-10-08-approval-platform-api.md` §4 附记——均指向本文，附记文字无需变更（v2 准入对外表现仍是密钥头 + 401，签名/载荷零变化）。

## 6. 验证口径（v2）

- 组件单测：来源抽象解析五分支（键存在/键不存在回落/报错粘性/冷启动报错空集/过期剔除）+ 过滤器集合比对 + 拦截器最新钥 + 装配两态（有/无 Redis）
- 真连接冒烟 `cloud-base/scripts/smoke/smoke_inner_secret_rotation.sh`：过渡项/旧钥即刻通（在途连续性）/ 新钥 ≤收方缓存 TTL 轮询收敛（≤40s）/ 旧钥重叠窗内仍通 / 窗口耗尽后 401 / 作废后 ≤TTL 断 / 作废最后一把 3051 / 网关 `/system/inner/**` 仍 403
- 回归：cloud-e2e 全量（只认 `grep -c '[FAIL]'`）；xxl-job id 12 下一分钟轮次无错
- 管理面端点验收按 `2026-11-09-inner-secret-admin-api.md` §1-§4

## 7. 错误码声明

**零新增分段错误码**（沿用 v1 口径）：拒绝响应 body 中的 `401` 为 HTTP 状态码字面量镜像，不占 1xxx/2xxx/3xxx/5xxx 分段账本；消费方对 401 的处理复用既有 FeignException→fallbackFactory→`code!=SUCCESS` 转译链。管理面业务错误码 3050-3053 归 admin 契约 §5。

## 8. v1 沿革附录（静态单密钥准入，2026-11-09 上午定稿）

v1 要义（已被 §1-§3 泛化取代，原文要点存档）：

- 校验目标为单一配置值 `cloud.inner.shared-secret`（Nacos per-service 三服务 × 双租户 = 6 次发布，双租户须发完全相同干净内容）；未配置 = fail-closed 恒 401 不阻断启动，启动哨兵 WARN。
- 组件装配：`InnerAccessFilter` 以 `FilterRegistrationBean` 注册（URL 模式 `/inner/*` 固定，注册序 `HIGHEST_PRECEDENCE + 10`）+ `InnerSecretInterceptor` 上下文 bean 全局收集；无 enabled 开关是刻意语义（inner 端点非可选特性，没有「合法不保护」态）。
- 发布 runbook（v1 静态换钥时）：先发 Nacos 键 → 按 **sso → bpmn → system → 网关（最后）** 消费方先行顺序重启；窗口期瞬态 401 由既有降级链吸收。v2 后静态键仅兜底，换钥走管理面轮换，该 runbook 退役为应急参考。
- v1 遗留验证结论：FeignClientBuilder 程序式 client 继承全局 RequestInterceptor（translate-remote-starter 零改动）。
