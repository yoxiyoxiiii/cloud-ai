# MCP 工具契约：cloud-system ↔ cloud-base-ai（Java↔Python 唯一对齐物）

> 状态：随 MCP Server 整合轮（设计 `docs/superpowers/specs/2026-10-17-mcp-server-integration-design.md`）定稿。
> 变更入口：契约问题由架构 agent 修订，Java 侧（cloud-system）与 Python 侧（cloud-base-ai）不得单方改。
> 术语：MCP = Model Context Protocol（AI 工具调用协议，JSON-RPC 2.0）；tool = 服务端暴露的可调用工具；perms = `sys_menu.perms` 权限标识快照。

## 1. 通道与认证

### 1.1 端点与传输

| 项 | 值 |
|---|---|
| 服务端 | cloud-system（:9202） |
| MCP 端点路径 | `/mcp`（streamable HTTP 单端点；**若 POC 判定 1.1.8 需降级 SSE，则改为 `/sse` + `/mcp/message` 双端点，本契约随 POC 结论修订**——设计文档 3.3-R2） |
| AI 侧直连地址 | `CLOUD_BASE_AI_MCP__BASE_URL`（.env，默认 `http://127.0.0.1:9202/mcp`） |
| 网关可达性 | **不可达**——网关屏蔽 `/system/mcp/**`（SetStatus 403，与 `/system/inner/**` 同款规则）；AI 基座直连服务端口 |
| 协议 | MCP over streamable HTTP（POST，JSON-RPC 2.0） |

### 1.2 请求头（每次 MCP 请求必带）

| Header | 值 | 缺失/错误后果 |
|---|---|---|
| `Authorization` | `Bearer <对话用户 access JWT>`（网关登录颁发，AI 侧从对话请求中取得并**原样转发**） | HTTP 401 + R JSON `{code:401, msg:"..."}` |
| `X-MCP-Client-Secret` | 服务密钥（cloud-system 取 Nacos `cloud.mcp.client-secret`；AI 侧取 .env `CLOUD_BASE_AI_MCP__CLIENT_SECRET`；**真值不入库不入档**） | 同上 401 |

服务端校验顺序（McpAuthFilter）：密钥 → JWT 验签 → Redis `sso:online:{jti}` 在线检查。任一不过即 401，不进入 MCP 协议层。用户注销/强退后 JWT 立即失效（在线检查拦截），与会话语义一致。

### 1.3 认证错误 body 形态

```json
{ "code": 401, "msg": "MCP 认证失败", "data": null }
```

真实 HTTP 状态码 401（先例：网关 AuthGlobalFilter 的 401 形态），非 MCP 协议帧。

## 2. 工具清单总览

| 工具名 | 用途 | 权限档位 | Java 承载 |
|---|---|---|---|
| `system_query_users` | 分页查询系统用户（供 AI 回答「有哪些用户/某用户状态」类问题） | perms `system:user:list`（服务端强制） | `SystemMcpTools.queryUsers` → `SysUserManageService.pageList` |
| `system_query_dict_items` | 按字典键取启用字典项（供 AI 解析状态码/下拉选项） | 无（登录即可，对齐 REST 面 `listByDictKey` 语义） | `SystemMcpTools.queryDictItems` → `SysDictDataManageService.listByDictKey` |

两个工具均为只读。工具名规则：`<服务域>_<动作>_<对象>`，小写下划线；后续 bpmn 等服务的工具同规则扩容。

## 3. 工具一：system_query_users

### 3.1 定义

```json
{
  "name": "system_query_users",
  "description": "分页查询系统用户列表（只读）。返回账号、昵称、部门、状态等；不含密码。需要权限 system:user:list。"
}
```

### 3.2 入参（JSON Schema）

```json
{
  "type": "object",
  "properties": {
    "pageNum":  { "type": "integer", "minimum": 1, "default": 1,  "description": "页码，从 1 开始" },
    "pageSize": { "type": "integer", "minimum": 1, "maximum": 200, "default": 10, "description": "每页条数，上限 200（对齐分页插件 maxLimit）" }
  },
  "additionalProperties": false
}
```

示例入参：`{"pageNum": 1, "pageSize": 10}`

### 3.3 出参（工具正常结果，MCP content 内 JSON 文本）

结构 = 既有 `PageResult<SysUserVo>`（Long 型 id 由 MCP 层按文本序列化，无前端精度问题）：

```json
{
  "total": 2,
  "rows": [
    {
      "id": "1",
      "account": "admin",
      "nickname": "管理员",
      "deptId": "1",
      "deptName": "总部",
      "status": 0,
      "statusLabel": "正常",
      "createBy": "admin",
      "createTime": "2026-10-01 10:00:00",
      "updateBy": "admin",
      "updateTime": "2026-10-01 10:00:00",
      "createByName": "管理员",
      "updateByName": "管理员",
      "builtin": true
    }
  ]
}
```

字段语义：`status` 0=正常 1=停用（`statusLabel` 为字典译文，null=未命中走降级）；`builtin`=内置账号标记（只读）；无 password/deleted 字段（VO 隔离）。rows 为空数组 + total=0 是合法结果。

### 3.4 错误

| 情形 | 表现 |
|---|---|
| 无 `system:user:list` perms | `isError: true`，content 文本恒为前缀格式：`PERMISSION_DENIED: system:user:list` |
| 分页参数越界（pageSize>200 等） | `isError: true`，content=业务异常 msg（如分页上限文案，域码 1xxx/3xxx 随 msg 文本透传） |

## 4. 工具二：system_query_dict_items

### 4.1 定义

```json
{
  "name": "system_query_dict_items",
  "description": "按字典键查询启用中的字典项（只读）。返回 value（存库值）/label（展示标签）/sort（排序）。登录即可调用，无需特定权限。"
}
```

### 4.2 入参（JSON Schema）

```json
{
  "type": "object",
  "properties": {
    "dictKey": { "type": "string", "minLength": 1, "description": "字典类型键，如 user_status" }
  },
  "required": ["dictKey"],
  "additionalProperties": false
}
```

示例入参：`{"dictKey": "user_status"}`

### 4.3 出参

结构 = 既有 `List<DictItemEntry>`：

```json
[
  { "value": "0", "label": "正常", "sort": 1 },
  { "value": "1", "label": "停用", "sort": 2 }
]
```

**容错语义（与 REST 面 `GET /dict/data/type/{dictKey}` 逐字一致，零业务错误码）**：dictKey 不存在 / 类型停用 / 无启用项 → 正常结果 `[]`（空数组），不是错误。

### 4.4 错误

仅协议级错误（入参缺 dictKey 会被 JSON Schema 校验拒绝）。无权限拒绝档位（登录即达）。

## 5. AI 侧可见性过滤（客户端体验层）

> 2026-10-30 二次修订（MCP server 配置化管理轮，修订全文见 `2026-10-30-mcp-server-mgmt-api.md` §7.1）：ai_tool 注册表新增 `server_id`（归属 `ai_mcp_server` 行）与 `status`（0=草稿 1=已发布 2=已失效）列，对 agent 可见 = 已发布且归属 server 启用未删；**本契约 §2-§4 继续作为平台自研工具（内置 SYSTEM 行）的 schema 真相源**，外部 server 工具以外务 server 为真相源不入本契约；管理面可编辑项扩展为 required_perms 与显示名（原「管理面不提供注册表编辑」的增量翻案，用户拍板 P2）。`visible_tools(tool_rows, user_perms)` 签名与过滤语义不变。

> 2026-10-29 修订（平台 Agent 管理轮，修订案见 `2026-10-29-agent-platform-api.md` §7）：真相源由本文件硬编码映射换轨为 `cloud_ai` 库 `ai_tool` 注册表。

- **真相源**：`cloud_ai` 库 `ai_tool` 注册表（`required_perms` 列，逗号分隔，空=登录即可）。种子值与本契约 §2 权限档位同步（`system_query_users` → `system:user:list`；`system_query_dict_items` → 空）；**工具名/权限档位变更须先修本契约再改种子**，管理面不提供注册表编辑。
- **实现**：`cloud_base_ai/mcp/perm.py` 的 `visible_tools(tool_rows, user_perms)` 按注册表行过滤（不再持有任何工具清单）；agent 运行链路每运行查询一次注册表（量级个位数，不设缓存）。`smoke_mcp_system.py` 同步改造。
- 过滤规则：用户 perms 快照（对话请求携带的 JWT 对应会话）⊇ 工具 `required_perms` → 工具对该用户可见；空集=登录即可见。
- 过滤只影响 tools/list 呈现给模型的清单，**不影响服务端判定**——直接调用无权限工具由服务端 `PERMISSION_DENIED` 拒绝（防御纵深）。

## 6. 错误语义总表（AI 侧消费口径）

| 层 | 情形 | AI 侧识别/转译 |
|---|---|---|
| HTTP 层 | 401（密钥/JWT/离线） | 抛业务异常转 AI 段错误码 **5009**（MCP 调用失败）——msg 不含密钥真值 |
| MCP 工具层 | `isError=true` 且 content 以 `PERMISSION_DENIED:` 开头 | 权限拒绝：向对话层返回「当前用户无权执行该操作」（本轮仅冒烟脚本打点，不接对话） |
| MCP 工具层 | `isError=true` 其他 | 业务失败：透传 content 文本 |
| 连接层 | 连接拒绝/超时 | **5009**；`.env` 未配 URL/密钥 = **5010**（MCP 未配置，不阻断启动） |
| 协议层 | JSON-RPC 错误 | 记日志 + 5009 |

错误码 5009/5010 为 AI 段 5xxx 接续（5001-5008 已占用），常量在 `cloud_base_ai/mcp/` 模块顶部成对声明。
