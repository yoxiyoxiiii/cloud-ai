# inner 面共享密钥管理 API 契约（inner-secret 管理面）

- 日期：2026-11-09
- 状态：定稿（前后端唯一对齐物；任何变更须经架构修订本文，两端不得单方改）
- 设计：`docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md`
- 服务：cloud-system（:9202），网关前缀 `/system`（StripPrefix 1）
- 运行时轮换语义（活跃集/重叠窗/兜底）是横切关注点，归 `2026-11-09-inner-access-secret-api.md`（v2）——本文只管管理面端点

> 前置沿用：`R<T>` 结构与 HTTP 恒 200 + body.code、Jackson 口径（**Long → String**、日期 `yyyy-MM-dd HH:mm:ss` GMT+8）、PageResult `{ total: string, rows: T[] }`——均沿 `2026-11-08-notify-api.md` §0，本文不重复。鉴权：网关 JWT + 服务层 `@PreAuthorize`，拒绝 = HTTP 200 + body code 403。

## 0. 端点总览与权限（三枚，`system:innerSecret:*`）

| 方法+路径 | 说明 | perms |
|---|---|---|
| GET /system/inner-secret/page | 密钥分页列表 | system:innerSecret:list |
| GET /system/inner-secret/status | 轮换状态卡（参数只读回显） | system:innerSecret:list |
| POST /system/inner-secret/rotate | 立即轮换（无请求体） | system:innerSecret:rotate |
| PUT /system/inner-secret/revoke | 作废（body JSON） | system:innerSecret:revoke |

枚举口径（前端映射展示，接口只给数值）：

- status：`1` 生效中（success） / `2` 重叠期——已有继任者，仍被接受（warning） / `3` 已过期（info） / `4` 已作废（danger）
- source：`1` 定时任务 / `2` 手动操作
- **真值红线**：任何接口任何字段**永不返回密钥明文**；列表仅掩码 `secretMask`（`****` + 尾 4 字符）

## 1. GET /system/inner-secret/page —— 分页

**入参**（query）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| pageNum | int | 是 | 页码从 1 起 |
| pageSize | int | 是 | 1..200（分页插件钳制） |
| status | int | 否 | 状态精确过滤：1/2/3/4 |

**返回** `R<PageResult<InnerSecretVo>>`（id 倒序，追加式流水全量可见——含已过期/已作废行）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | string | 记录ID |
| seq | int | 轮换代数（场景内单调递增） |
| status | int | §0 枚举 |
| secretMask | string | 掩码，如 `****ab3f` |
| source | int | §0 枚举 |
| activateTime | string | 生效时间 |
| expireTime | string | 过期时间 |
| retireTime | string\|null | 退场时间（未被顶替为 null） |
| createBy | string | 创建人（定时任务=handler 名） |
| createTime | string | 创建时间 |

**示例**：
```json
{ "code": 200, "msg": "操作成功", "data": { "total": "3", "rows": [ { "id": "12", "seq": 7, "status": 1, "secretMask": "****ab3f", "source": 1, "activateTime": "2026-11-09 16:00:00", "expireTime": "2026-11-16 16:00:00", "retireTime": null, "createBy": "innerSecretRotateJobHandler", "createTime": "2026-11-09 16:00:00" } ] } }
```

## 2. GET /system/inner-secret/status —— 状态卡（只读）

无入参。**返回** `R<InnerSecretStatusVo>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| validityHours | int | 密钥有效期（小时，Nacos 参数回显） |
| overlapSeconds | int | 重叠窗（秒，Nacos 参数回显） |
| leadHours | int | 提前轮换预算（小时，Nacos 参数回显） |
| currentSeq | int | 最新生效代数（无生效密钥=0） |
| activeCount | int | 当前活跃集行数（status∈{1,2} 且未到退场时刻，通常 1-2） |
| nextRotationTime | string\|null | 预计下次轮换时刻（=最新生效行 expireTime − lead；无生效行 null） |
| redisVersion | int\|null | Redis 活跃集 version（键缺失 null） |
| redisConsistent | boolean | Redis version == DB 最新 seq 且键存在 |

**语义**：轮换参数在 Nacos `cloud.inner.rotation.*` 维护（运维改，双租户整文件重发），界面**只读展示**——这是拍板口径，不做参数编辑端点。`redisConsistent=false` 表示发布落后（任务一分钟内自愈），状态卡可提示「等待自动重发」。

## 3. POST /system/inner-secret/rotate —— 立即轮换

无入参。与定时任务**同一条代码路径**：生成新钥即刻生效、前任进入重叠期并于重叠窗耗尽后退场（运行时交接语义见 inner-access 契约 v2 §1-§3；首轮轮换的静态过渡语义亦见该契约 §2）。

**返回** `R<InnerSecretVo>`（新密钥行，字段同 §1）。

**失败码**：**3053** 轮换过于频繁——上一轮重叠窗口未结束（msg 带剩余秒数）；**3052** 活跃集发布失败——新钥**已落库生效**，Redis 集将由定时任务在一分钟内自动重试发布（可重查 status 确认 redisConsistent，属可等待自愈的软失败）。

## 4. PUT /system/inner-secret/revoke —— 作废

**入参** RevokeRequest（body JSON）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| id | string(Long) | 是 | 目标密钥记录ID（列表行取） |

**行为**：目标立即移出活跃集并标记已作废；**生效时延 ≤ 30 秒**（各服务本地缓存过期后才拒收——前端确认弹窗必须明示「将立即断开重叠窗内的在途服务间调用，最迟约 30 秒内生效」，这是刻意的非瞬时语义）。追加式流水无删除操作，作废即终态。

**返回** `R<null>`。

**失败码**：**3050** 密钥记录不存在或已退场（status 已是 3/4）；**3051** 禁止作废最后一把生效密钥——作废后 inner 面将全体拒绝（含登录链），须先轮换出新钥再作废旧钥；**3052** 同 §3（作废已落库，发布自愈中）。

## 5. 错误码声明（system 域 3xxx 接续 3049）

| 码 | 语义 |
|---|---|
| 3050 | 密钥记录不存在或已退场 |
| 3051 | 禁止作废最后一把生效密钥（请先轮换） |
| 3052 | 活跃集发布失败（变更已落库，定时任务一分钟内自愈重发） |
| 3053 | 轮换请求过于频繁（上一轮重叠窗口未结束） |

入站 401（密钥拒绝）是 HTTP 状态码镜像，不占本段账本（沿 v2 契约 §7 口径）。

## 6. 菜单与种子（SQL `cloud-base/scripts/sql/2026-11-09-inner-secret-rotation.sql`）

- 菜单：C 段 **id 22** 挂系统管理(parent 10) 下「密钥管理」，path `/system/inner-secret`，perms `system:innerSecret:list`，icon `Key`，sort 9；F 按钮 **id 200** 立即轮换 `system:innerSecret:rotate` / **id 201** 作废 `system:innerSecret:revoke`。admin 角色(id 1) 绑定三行。
- 站内信内置模板 **id 4** code `inner_secret_alert`（type 2 提醒 / level 1 重要 / 变量 `content`）——轮换任务告警用，非本契约端点面。
- xxl-job：admin 任务 **id 12**（组 3 = cloud-system，CRON 每分钟，handler `innerSecretRotateJobHandler`）。
- 种子前置断言：`sys_shared_secret` 表不存在；sys_menu id 22/200/201、模板 id 4、xxl_job id 12 均空闲（含墓碑物理判断）；不可重放（固定主键）。
- 权限快照时效：落库后 admin 须重新登录（或 refresh）权限才进快照。

## 7. 告警行为（前端须知，非端点）

轮换任务失败或活跃集为空时，系统自动向 admin 角色发站内信（模板 `inner_secret_alert`，linkUrl 指向本页）+ 服务日志 ERROR。管理页无需做告警端点；消息经站内信中心既有铃铛/消息中心呈现。
