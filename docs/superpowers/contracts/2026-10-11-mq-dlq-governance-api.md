# MQ 死信治理 API 契约（2026-10-11）——前后端唯一对齐物

> 设计：`docs/superpowers/specs/2026-10-11-mq-dlq-governance-design.md`。
> 本轮全新增端点，零既有端点改动（e2e 零破坏基线）。
> 结构约定：所有接口经网关 `/system/**` 外部可达；HTTP 恒 200，业务语义在 `body.code`（R<T>）；Long 一律 string 形态（全局 Jackson 口径）。

## 1. 端点总览

| # | 方法+路径 | 用途 | 权限标识 |
|---|---|---|---|
| 1 | GET `/system/mq-dlq/page` | 死信台账分页 | system:mqDlq:list |
| 2 | GET `/system/mq-dlq/{id}` | 死信详情（含 body 原文） | system:mqDlq:list |
| 3 | POST `/system/mq-dlq/{id}/resend` | 人工重投（高危） | system:mqDlq:resend |
| 4 | POST `/system/mq-dlq/{id}/ignore` | 人工忽略 | system:mqDlq:ignore |
| 5 | POST `/system/mq-dlq/{id}/reset` | 解除终态（admin） | system:mqDlq:reset |

## 2. 字典种子 `mq_dlq_status`（is_builtin=1）

| value | label |
|---|---|
| 0 | 待处理 |
| 1 | 已重投 |
| 2 | 已忽略 |
| 3 | 已放弃 |

页面状态标签全字典驱动；列表「同 msg_key 多行」为重投代际链（设计 D5/D6），前端按 msg_key 分组视觉提示（同键行数>1 时标 warning 色），不新增接口。

## 3. 端点明细

### 3.1 死信分页 `GET /system/mq-dlq/page`（perms `system:mqDlq:list`）

- 入参（query 参数）：

| 参数 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| pageNum | int | 否（默认 1） | 页码 | `1` |
| pageSize | int | 否（默认 10） | 每页（上限 200，分页插件 maxLimit） | `10` |
| status | string | 否 | 处置状态过滤（"0"-"3"；缺省=全部） | `"0"` |
| bornGroup | string | 否 | 死信来源消费组（精确） | `"g_bpmn_approval_create"` |
| msgKey | string | 否 | 消息键模糊（LIKE %x%，前后模糊） | `"e2e-dlq"` |
| dlqTimeBegin | string | 否 | 死信时间起（yyyy-MM-dd HH:mm:ss） | `"2026-10-11 00:00:00"` |
| dlqTimeEnd | string | 否 | 死信时间止（同格式） | `"2026-10-11 23:59:59"` |

- 返回 `R<PageResult<MqDlqVo>>`：按 dlq_time 倒序；**列表不返 body**（行多体大，详情取）。

`MqDlqVo`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 台账行 id | `"1"` |
| msgId | string | 是 | 死信原消息 msgId | `"7F000001..."` |
| txNo | string \| null | 是 | 事务流水号（可空） | `"a1b2..."` |
| bornTopic | string | 是 | 原 topic | `"TX_APPROVAL_CREATE"` |
| bornGroup | string | 是 | 死信来源消费组 | `"g_bpmn_approval_create"` |
| watchGroup | string | 是 | watch 消费组 | `"g_system_dlq_watch_create"` |
| tag | string \| null | 是 | 消息 tag（TX_APPROVAL_CREATE 无 tag） | `"CREATE_RESULT"` |
| msgKey | string | 是 | 消息幂等键（KEYS 优先/退化 msgId） | `"leave:224"` |
| bodyPreview | string \| null | 是 | body 前 120 字符预览（辅助列表辨识，超长省略号） | `"{\"businessType\"..."` |
| reconsumeTimes | int | 是 | 死信时重试投递计数（broker 透传；maxReconsumeTimes=3 时死信恒 4=1 首发+3 重试） | `4` |
| bornTime | string | 是 | 消息产生时间 | `"2026-10-11 10:00:00"` |
| dlqTime | string | 是 | 进死信时间（主排序） | `"2026-10-11 10:01:00"` |
| status | string | 是 | 处置状态（"0"-"3"，字典 mq_dlq_status） | `"0"` |
| resendCount | int | 是 | 重投次数 | `0` |
| lastResendTime | string \| null | 是 | 最近重投时间 | `null` |
| operateAccount | string \| null | 是 | 最近处置操作人 | `"admin"` |
| operateTime | string \| null | 是 | 最近处置时间 | `null` |

- 错误码：1001（参数校验）/ 1002（兜底）。

### 3.2 死信详情 `GET /system/mq-dlq/{id}`（perms `system:mqDlq:list`）

- 入参：路径 `id`（string，台账行 id）。
- 返回 `R<MqDlqDetailVo>`：MqDlqVo 全字段 + 下列增列：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| body | string \| null | 是 | 消息体原文全文（TEXT 存量；截断行尾部有截断标记，前端 <pre> 展示） | `"{\"businessType\":\"leave\",...}"` |
| bodyTruncated | boolean | 是 | body 是否被 60000 字节截断（设计 R4，前端提示） | `false` |
| createTime | string | 是 | watch 落库时间 | `"2026-10-11 10:01:05"` |
| updateTime | string \| null | 是 | 最近更新 | `null` |

- 错误码：3038（死信记录不存在）/ 1002。

### 3.3 人工重投 `POST /system/mq-dlq/{id}/resend`（perms `system:mqDlq:resend`）

- 入参：路径 `id`；无 body。
- 语义（设计 D7）：清 mq_consume_dedup 残留行 (born_group, msg_key) → syncSend 原 topic（恢复 tag/KEYS/TX_NO/body）→ 行置 status=1、resend_count+1、last_resend_time、operate_account/time。**前端必须二次确认弹窗**（回显 msgId/msgKey/bornTopic，警示"重投将真实触发业务动作"）。
- 返回 `R<Void>`（code=200 即成功；前端重拉列表取新状态）。
- 错误码：

| code | msg | 场景 |
|---|---|---|
| 3038 | 死信记录不存在 | id 无效 |
| 3040 | 死信状态不允许该操作 | status∈{2,3}（终态不可重投） |
| 3041 | 重投次数已达上限，已置为已放弃 | resend_count≥5：行已自动置 status=3，前端重拉 |
| 3039 | 消息重投发送失败 | MQ 不可达（行状态不动，可重试） |

### 3.4 人工忽略 `POST /system/mq-dlq/{id}/ignore`（perms `system:mqDlq:ignore`）

- 入参：路径 `id`；无 body。
- 语义：status=0 → 2（仅待处理可忽略；已重投行不可忽略——设计 D6）。行更新 operate_account/time。**前端确认弹窗**（提示终态仅 admin reset 可解除）。
- 返回 `R<Void>`。
- 错误码：3038 / 3040（status≠0）。

### 3.5 解除终态 `POST /system/mq-dlq/{id}/reset`（perms `system:mqDlq:reset`）

- 入参：路径 `id`；无 body。
- 语义：status∈{2,3} → 0，resend_count 清零，operate_account/time 更新（设计 D8：admin 解除终态，覆盖误忽略恢复）。**前端确认弹窗**。
- 返回 `R<Void>`。
- 错误码：3038 / 3040（status∉{2,3}）。

## 4. 按钮可见性规则（前端实现基准，状态机设计 D6）

| 按钮 | 可见/可用条件 | 权限 |
|---|---|---|
| 重投 | status∈{0,1} 且 resendCount<5（=5 时后端亦拦 3041，前端 disabled） | system:mqDlq:resend |
| 忽略 | status=0 | system:mqDlq:ignore |
| 重置 | status∈{2,3} | system:mqDlq:reset |
| 详情 | 恒可见 | system:mqDlq:list |

## 5. 菜单种子（固定 id 显式 INSERT + 前置断言防 id 占用，非 NOT EXISTS 幂等；admin 绑定显式 id 列表）

```
sys_menu：17 死信治理（C，parent=10 系统管理，path=/system/mq-dlq，perms=system:mqDlq:list，icon=Message，sort=7，is_builtin=1）
         171 重投（F，parent=17，perms=system:mqDlq:resend，sort=1）
         172 忽略（F，parent=17，perms=system:mqDlq:ignore，sort=2）
         173 重置（F，parent=17，perms=system:mqDlq:reset，sort=3）
sys_role_menu：role 1（admin）× {17,171,172,173}
```

落库后 admin 须重新登录（或 refresh）进权限快照方可访问（沿既有种子时效口径）。路由 `/system/mq-dlq` 连字符风格；前端组件目录 `views/system/mqDlq/`（camel，沿 dataPerm 先例）。

## 6. 错误码台账增量（system 段 3038-3041，前账至 3037）

| code | msg | 端点 | 备注 |
|---|---|---|---|
| 3038 | 死信记录不存在 | 3.2/3.3/3.4/3.5 | msg 逐字含 id 上下文（"死信记录不存在: 5"形态由实现统一，断言以 3038 为准） |
| 3039 | 消息重投发送失败 | 3.3 | log.error 根因后转译 |
| 3040 | 死信状态不允许该操作 | 3.3/3.4/3.5 | 状态机拒绝统一码（分支语义在 msg 尾注，断言以 3040 为准） |
| 3041 | 重投次数已达上限，已置为已放弃 | 3.3 | 行已自动置 3 |

## 7. 非目标（本轮明确不做，防范围蔓延）

告警通道（webhook/邮件）、自动重投、%RETRY% 队列治理、消息轨迹查询、消息体编辑后重发、多 MQ 集群、bpmn 侧任何改动、消费端异常摘要透传（记设计移交备忘 3）。
