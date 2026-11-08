# 站内消息通知中心 API 契约（notify 域）

- 日期：2026-11-08
- 状态：定稿（前后端唯一对齐物；任何变更须经架构修订本文，两端不得单方改）
- 设计：`docs/superpowers/specs/2026-11-08-notify-center-design.md`
- 服务：cloud-system（:9202），网关前缀 `/system`（StripPrefix 1）

> 前置契约：`2026-10-12-cloud-base-ai-nacos-registration-api.md` §2 的 `R<T>` 结构**沿用不变**（HTTP 恒 200 + body.code，`data` 为负荷），本文不重复字段表。Jackson 全局口径沿用：**Long → String**（防前端精度丢失），Integer 不转；日期 `yyyy-MM-dd HH:mm:ss`（GMT+8）。

## 0. 鉴权两档

| 档位 | 端点 | 机制 |
|---|---|---|
| 管理端 | §1 / §2 全部 | 网关 JWT+在线 → 服务层 `@PreAuthorize`，perms 见各端点；拒绝=HTTP 200 + body code 403（全局既有语义） |
| 用户侧 | §3 全部 | **登录即可，无 perms**（不加 @PreAuthorize）；服务层以 `X-User-Id`（网关注入）隔离数据，跨用户访问一律按"不存在"处理（3048） |

## 1. 管理端：模板 CRUD

### 1.1 GET /system/notify/template/page（分页列表，perms `system:notify:list`）

**入参**（query）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| pageNum | int | 是 | 页码，从 1 起 |
| pageSize | int | 是 | 每页条数（分页插件 maxLimit 200） |
| name | string | 否 | 模板名称模糊匹配 |
| code | string | 否 | 模板编码模糊匹配 |
| type | int | 否 | 消息类型精确匹配：1公告 2提醒 |

**返回** `R<PageResult<TemplateVo>>`，PageResult 沿用既有 `{ total: string, rows: T[] }`。

TemplateVo（列表与详情共用，详情=单行同构）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | string | 模板ID |
| code | string | 模板编码（如 `welcome_notice`） |
| name | string | 模板名称 |
| titleTemplate | string | 标题模板（含 `${var}` 占位符） |
| contentTemplate | string | 内容模板（含 `${var}` 占位符） |
| type | int | 消息类型：1公告 2提醒 |
| level | int | 消息级别：0普通 1重要 2紧急 |
| variablesDesc | string | 变量说明（人工维护文档文本，可空串） |
| isBuiltin | int | 1=内置模板（禁删）0=用户创建 |
| createBy | string | 创建人账号 |
| createTime | string | 创建时间 |
| updateTime | string | 更新时间 |

**示例**：`GET /system/notify/template/page?pageNum=1&pageSize=10&name=欢迎`
```json
{ "code": 200, "msg": "操作成功", "data": { "total": "2", "rows": [ { "id": "1", "code": "welcome_notice", "name": "欢迎公告", "titleTemplate": "欢迎加入", "contentTemplate": "欢迎 ${nickname} 使用本平台", "type": 1, "level": 0, "variablesDesc": "nickname=用户昵称", "isBuiltin": 1, "createBy": "admin", "createTime": "2026-11-08 10:00:00", "updateTime": "2026-11-08 10:00:00" } ] } }
```

### 1.2 GET /system/notify/template/{id}（详情，perms `system:notify:list`）

**入参**：`@PathVariable("id") Long id`（string 传参）。**返回** `R<TemplateVo>`；id 无效 → 3044。

### 1.3 POST /system/notify/template（新增，perms `system:notify:add`）

**入参** TemplateAddRequest（body JSON）：

| 字段 | 类型 | 必填 | 校验 |
|---|---|---|---|
| code | string | 是 | 2-50 字符，`^[a-z][a-z0-9_]*$`（小写下划线编码） |
| name | string | 是 | 2-50 字符 |
| titleTemplate | string | 是 | 1-100 字符 |
| contentTemplate | string | 是 | 1-1000 字符 |
| type | int | 是 | 1 或 2 |
| level | int | 是 | 0、1 或 2 |
| variablesDesc | string | 否 | ≤500 字符，缺省空串 |

**返回** `R<null>`。失败：参数不合法 1001；code 重复（含与墓碑行撞键）3043。

### 1.4 PUT /system/notify/template（修改，perms `system:notify:edit`）

**入参** TemplateEditRequest：TemplateAddRequest 全字段 + `id`（Long，必填）置首。code 允许修改（同样查重 3043）。内置模板（isBuiltin=1）内容可改，仅禁删。

**返回** `R<null>`。失败：1001 / 3043（code 重复）/ 3044（模板不存在或已删）。

### 1.5 DELETE /system/notify/template/{id}（删除，perms `system:notify:remove`）

**入参**：`@PathVariable("id") Long id`。**行为**：逻辑删（deleted=1 + update 审计两值）。
**返回** `R<null>`。失败：3044（不存在）/ **3049（内置模板禁止删除）**。

## 2. 管理端：发送

### 2.1 POST /system/notify/send（perms `system:notify:send`）

**入参** NotifySendRequest（body JSON）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| templateId | Long | 是 | 模板ID（发送弹窗从列表选中行取） |
| receiverType | int | 是 | 接收人维度：1按用户 2按角色 3全员 |
| userIds | Long[] | 条件 | receiverType=1 时必填非空；元素去重后展开，无效用户（停用/已删）剔除 |
| roleIds | Long[] | 条件 | receiverType=2 时必填非空；按角色展开为有效用户集（多角色并集去重） |
| variables | map<string,string> | 否 | 模板变量值；键须匹配 `^[a-zA-Z][a-zA-Z0-9_]*$`；**缺失必填变量 → 3045；多余变量忽略**（前向兼容） |
| linkUrl | string | 否 | 前端相对路由（如 `/bpmn/task`），非空必须以 `/` 开头；缺省 null=纯展示 |

> `variables` 是 DTO 内嵌字段（模板变量键空间动态，由模板内容决定）——与"Controller 禁 Map 接参"规范不冲突（该规范约束方法签名整体接参），设计文档 §8 有裁量记档。

**返回** `R<NotifySendResultVo>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| messageId | string | 消息主表ID |
| receiverCount | int | 实际展开收件人数（剔除无效用户后） |

**示例**：
```json
// 请求
{ "templateId": 1, "receiverType": 3, "variables": { "nickname": "张三" }, "linkUrl": null }
// 响应
{ "code": 200, "msg": "操作成功", "data": { "messageId": "1001", "receiverCount": 6 } }
```

**失败码**：1001（参数：receiverType 与 userIds/roleIds 不配套、linkUrl 非 `/` 开头、variables 键非法）/ 3044（模板不存在）/ 3045（变量缺失，msg 列缺失变量名）/ 3046（解析后标题或内容超长）/ 3047（展开后收件人为空）。

**语义**：同步事务发送（主表 1 行 + 收件 N 行同事务）；消息内容为**解析后快照**，与模板此后变更解耦；按角色/全员为**发送时点快照**（之后入离职不回溯）。

## 3. 用户侧（登录即可，X-User-Id 隔离）

### 3.1 GET /system/notify/summary（未读摘要——30s 轮询唯一接口）

**入参**：无。**返回** `R<NotifySummaryVo>`：

| 字段 | 类型 | 说明 |
|---|---|---|
| unreadCount | string | 未读数（未读=未处理，单状态口径；Long→String，前端 Number() 消费） |
| latestId | string | 该用户最新收件行 id（无任何收件="0"）——前端水位：latestId 变化才拉 §3.2 弹框 |

**示例**：`{ "code": 200, "msg": "操作成功", "data": { "unreadCount": "3", "latestId": "2048" } }`

### 3.2 GET /system/notify/page（我的消息分页）

**入参**（query）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| pageNum / pageSize | int | 是 | 同 §1.1 |
| type | int | 否 | 消息类型：1公告 2提醒 |
| readFlag | int | 否 | 已读标记：0未读 1已读 |

**返回** `R<PageResult<NotifyUserMessageVo>>`，按收件行 id 倒序（新消息在前）：

| 字段 | 类型 | 说明 |
|---|---|---|
| id | string | **收件行ID**（已读操作的目标标识，非 messageId） |
| messageId | string | 消息主表ID |
| title | string | 消息标题（发送时快照） |
| content | string | 消息内容（发送时快照） |
| type | int | 1公告 2提醒 |
| level | int | 0普通 1重要 2紧急（弹框样式映射：info 4.5s / warning 8s / error 常驻） |
| linkUrl | string\|null | 跳转相对路由，null=纯展示 |
| readFlag | int | 0未读 1已读 |
| readTime | string\|null | 已读时间 |
| sendTime | string | 发送时间（主表 createTime） |

### 3.3 PUT /system/notify/read/{id}（单条已读）

**入参**：`@PathVariable("id") Long id`（收件行 id）。**行为**：仅当目标行存在**且归属当前用户**时置 read_flag=1、read_time=NOW()；已读重复调用**幂等成功**（0 行更新不报错）。
**返回** `R<null>`。失败：**3048（收件记录不存在或非本人——同一文案，不泄露他人数据存在性）**。

### 3.4 PUT /system/notify/read-all（全部已读）

**入参**：无。**行为**：当前用户全部未读行置已读。
**返回** `R<Integer>`（data=本次影响行数，前端 toast「已读 N 条」）。

**示例**：`{ "code": 200, "msg": "操作成功", "data": 5 }`

## 4. 错误码（system 段 3xxx）

> 复核记档：3xxx 现值 3001-3042 连续全占（内联 + 常量两形态合计），本轮自 **3043** 接续。1xxx/2xxx 沿用全局语义（1001 参数校验失败 / 1002 业务失败 / 403 无权限）。

| 码 | 文案 | 触发场景 |
|---|---|---|
| 3043 | 模板编码已存在 | 模板新增/修改撞 uk_code（含墓碑占键，DuplicateKeyException 兜底同码） |
| 3044 | 模板不存在 | 按 id 取模板未命中（含已删） |
| 3045 | 模板变量缺失: {缺失变量名列表} | 发送时入参变量集未覆盖模板提取变量集（msg 动态列出，逗号分隔） |
| 3046 | 解析后标题或内容超出长度限制 | 变量值展开后超 title(100)/content(1000) 列宽 |
| 3047 | 接收人列表为空 | 三档展开后有效收件人为空（如按用户发但全部无效、按角色发但角色无有效成员） |
| 3048 | 收件记录不存在 | 已读操作目标不存在**或非本人**（同文案） |
| 3049 | 内置模板禁止删除 | 删除 isBuiltin=1 模板 |

**接续规则**：本轮后 system 段现值至 3049，后续新增自 3050 接续。

## 5. 权限标识与菜单种子

**权限标识**（sys_menu.perms，5 个）：

| perms | 用途 |
|---|---|
| system:notify:list | 通知管理页 + 模板分页/详情查询 |
| system:notify:add | 模板新增 |
| system:notify:edit | 模板修改 |
| system:notify:remove | 模板删除 |
| system:notify:send | 发送通知 |

**菜单种子**（id 段 19/196-199，经 cloud_system.sql 基线全量核对空闲；admin 角色绑定 + 基线同步沿 2026-10-13-ai-rag-chat.sql 头注口径；落库后 admin 须重新登录进权限快照）：

| id | parent | name | type | path | perms | 说明 |
|---|---|---|---|---|---|---|
| 19 | 10 | 通知管理 | C | /system/notify | system:notify:list | 系统管理第 8 项，sort 8，模板列表+发送入口页 |
| 196 | 19 | 新增模板 | F | | system:notify:add | |
| 197 | 19 | 修改模板 | F | | system:notify:edit | |
| 198 | 19 | 删除模板 | F | | system:notify:remove | |
| 199 | 19 | 发送通知 | F | | system:notify:send | |

**用户侧消息中心页 `/notify/center` 不挂 sys_menu**：sys_menu 按角色绑定分发，全员功能不能靠菜单树（无角色能看到全部角色）；前端走**静态路由**（Layout children 手写，登录即可见），入口=铃铛下拉「查看全部」。

**种子模板**（isBuiltin=1，随 DDL 同文件）：`welcome_notice`（公告，无变量）与 `approval_remind`（提醒，含 `${nickname}`，演示变量链路）。

## 6. inner Feign 预留（本轮不实现，仅形态记档）

未来业务事件接入（如审批待办）时，cloud-system-api 增 `client/NotifyClient`（Feign + fallbackFactory，沿 cloud-system-api 惯例）：

```
POST /inner/notify/send-by-code     （网关已屏蔽 /system/inner/**，既有规则无需变更）
  入参 SendByCodeRequest { templateCode: string, variables: map<string,string>,
                           receiverType: int, userIds?: Long[], roleIds?: Long[], linkUrl?: string }
  返回 R<NotifySendResultVo>（复用 §2.1 出参；service 门面 NotifySendService.send 与管理端共用）
```

差异点：业务方按 `templateCode` 引用模板（不感知 id）；错误码沿用 §4（3044 按 code 语义）。触发实现的条件与事务消息化评估见设计文档 §9。
