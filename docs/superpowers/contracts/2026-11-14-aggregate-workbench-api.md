# API 契约：聚合服务工作台端点（2026-11-14）

> 前后端唯一对齐物。服务 cloud-aggregate :9205，网关前缀 `/aggregate`。
> 通用返回格式沿项目 `R<T>` 约定：HTTP 恒 200，`body.code` 分流（网关鉴权失败例外：真实 HTTP 401 + R JSON body）。
> 字段序列化沿全局 Jackson 口径：GMT+8、日期 `yyyy-MM-dd HH:mm:ss`、Long/total 一律字符串（防前端精度丢失）。
> 术语：「段」= 聚合响应里一个下游数据源的分区。设计细节见 `docs/superpowers/specs/2026-11-14-aggregate-service-design.md`。

---

## 0. 端点总表

| # | 端点 | 方法 | 权限 | 说明 |
|---|---|---|---|---|
| 1 | `/aggregate/workbench` | GET | 登录即可（无 perms） | 工作台三段聚合：我的待办 top5 + 我的审批 top5 + 未读通知摘要 |
| 2 | `/aggregate/demo/ping` | GET | 免鉴权（网关白名单） | 连通性验证（三胞胎惯例） |

---

## 1. 工作台聚合 `GET /aggregate/workbench`

### 1.1 入参

无。三段取数参数固定（聚合服务内写死，不暴露给前端）：待办与审批均为 `pageNum=1&pageSize=5`；通知摘要无参数。

### 1.2 出参 `R<WorkbenchVo>`

`data` = WorkbenchVo 三段并置，**每段自带状态**：

| 字段 | 类型 | 说明 |
|---|---|---|
| `todo` | TodoSectionVo | 我的待办段（cloud-bpmn） |
| `approval` | ApprovalSectionVo | 我的审批段（cloud-bpmn） |
| `notify` | NotifySectionVo | 未读通知摘要段（cloud-system） |

段对象统一结构（三段同构，仅 data 类型不同）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `status` | number | 段状态码：`200` 正常 / `7001` 无权限 / `7002` 下游失败（枚举见 §2） |
| `msg` | string | 段状态说明文案（正常为「操作成功」；失败为可读原因，见 §2 转译表） |
| `data` | object \| null | 段数据；**status ≠ 200 时恒为 null** |

### 1.3 段 data 形状

**todo.data** = `PageResult<TaskVo>`（TaskVo 字段冻结，镜像自 cloud-bpmn `/bpmn/task/todo` 出参）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `total` | string | 引擎待办总数 |
| `rows` | TaskVo[] | 本页行（聚合场景固定 ≤5 条） |
| rows[].taskId | string | 引擎任务 id |
| rows[].approvalId | string | 审批单 id |
| rows[].businessType | string | 业务类型编码 |
| rows[].businessTypeName | string | 业务类型名（配置表渲染，必返非空） |
| rows[].title | string | 单据标题快照 |
| rows[].detailPath | string \| null | 详情跳转路径（未配/渲染失败 null → 前端隐藏跳转） |
| rows[].applyUser | string | 申请人 account |
| rows[].applyUserName | string \| null | 申请人昵称译文（未命中 null → 前端降级显示 applyUser） |
| rows[].createTime | string | 任务创建时间（yyyy-MM-dd HH:mm:ss） |

**approval.data** = `PageResult<ApprovalVo>`（镜像自 cloud-bpmn `/bpmn/approval/page` 出参）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `total` | string | 审批单总数 |
| `rows` | ApprovalVo[] | 本页行（≤5 条） |
| rows[].id | string | 审批单 id（即流程实例 businessKey） |
| rows[].businessType | string | 业务类型编码 |
| rows[].businessTypeName | string | 业务类型名（必返非空） |
| rows[].businessKey | string | 业务单据标识 |
| rows[].title | string | 标题快照 |
| rows[].status | string | 状态值 "0"-"3"（0 审批中/1 已通过/2 已拒绝/3 已撤销，字典 bpmn_approval_status） |
| rows[].statusLabel | string \| null | 状态译文（null → 前端降级本地映射） |
| rows[].applyUser / applyUserName | string / string \| null | 申请人 account / 昵称译文 |
| rows[].approver / approverName | string / string \| null | 审批人 account / 昵称译文 |
| rows[].detailPath | string \| null | 详情跳转路径 |
| rows[].processInstanceId | string \| null | 流程实例 id（撤销后 null） |
| rows[].createTime | string | 发起时间 |
| rows[].updateTime | string \| null | 最近状态变更时间（未变更过 null） |

**notify.data** = `NotifySummaryVo`（镜像自 cloud-system `/system/notify/summary` 出参）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `unreadCount` | string | 未读数（前端 Number() 消费） |
| `latestId` | string | 最新收件行 id 水位（无任何收件 = "0"） |

### 1.4 响应示例（三段全正常）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "todo": {
      "status": 200,
      "msg": "操作成功",
      "data": {
        "total": "12",
        "rows": [
          {
            "taskId": "task-101",
            "approvalId": "88",
            "businessType": "leave",
            "businessTypeName": "请假申请",
            "title": "张三的请假申请",
            "detailPath": "/system/leave?approval=88",
            "applyUser": "zhangsan",
            "applyUserName": "张三",
            "createTime": "2026-11-14 10:30:00"
          }
        ]
      }
    },
    "approval": {
      "status": 200,
      "msg": "操作成功",
      "data": {
        "total": "31",
        "rows": [
          {
            "id": "92",
            "businessType": "leave",
            "businessTypeName": "请假申请",
            "businessKey": "456",
            "title": "李四的请假申请",
            "status": "0",
            "statusLabel": "审批中",
            "applyUser": "lisi",
            "applyUserName": "李四",
            "approver": "admin",
            "approverName": "管理员",
            "detailPath": "/system/leave?approval=92",
            "processInstanceId": "7f3a...",
            "createTime": "2026-11-14 09:12:00",
            "updateTime": null
          }
        ]
      }
    },
    "notify": {
      "status": 200,
      "msg": "操作成功",
      "data": { "unreadCount": "3", "latestId": "128" }
    }
  }
}
```

### 1.5 响应示例（部分失败：用户无 bpmn 权限）

```json
{
  "code": 200,
  "msg": "操作成功",
  "data": {
    "todo":     { "status": 7001, "msg": "无操作权限", "data": null },
    "approval": { "status": 7001, "msg": "无操作权限", "data": null },
    "notify":   { "status": 200,  "msg": "操作成功", "data": { "unreadCount": "0", "latestId": "0" } }
  }
}
```

### 1.6 整体失败（非段级）

| 场景 | HTTP | body |
|---|---|---|
| 未登录 / 会话失效（网关层） | 401 | `{"code":401,"msg":"认证失败或未登录","data":null}` |
| 绕过网关直连 9205 且无身份头（服务层防御） | 200 | `{"code":401,"msg":"认证失败或未登录","data":null}` |
| 聚合服务自身异常 | 200 | `{"code":500,"msg":"系统异常，请稍后重试","data":null}` |

---

## 2. 段状态枚举与转译规则

段状态码与整体 `code` 同语义空间，前端判断口径统一为 `section.status === 200`。

| 段 status | 含义 | 触发条件（下游实际表现） | msg 文案 |
|---|---|---|---|
| 200 | 正常 | 下游返回 code=200 | `操作成功` |
| 7001 | 无权限 | 聚合层段准入预判不通过（未发下游）；或下游 `@PreAuthorize` 拒绝返回 code=403 | `无操作权限` |
| 7002 | 下游失败 | 下游降级（fallbackFactory code=1002，如「cloud-bpmn 服务不可用」）；或下游其它非 200/403 code（含 500）；或 Feign 调用异常 | 优先取下游 msg；Feign 异常时 `下游调用异常` |

转译规则补充：

- 段间完全隔离：任一段失败不改变其它段与整体 `code=200`。
- 熔断打开期该段持续 7002，属预期行为（半开恢复后自动回到 200）。
- `msg` 是给人看的文案，前端可直接展示在空态区；**不得**用 msg 做逻辑判断（判断只认 status）。

---

## 3. 段数据形状冻结（镜像声明）

| 段 | 下游端点（真源契约） | 聚合镜像类（字段冻结处） |
|---|---|---|
| todo | `GET /bpmn/task/todo`（契约 2026-10-08-approval-platform-api §2.1 + 2026-11-09 分页轮） | `com.cloudai.bpmn.api.domain.TaskVo`（cloud-bpmn-api） |
| approval | `GET /bpmn/approval/page`（同上 §3.1） | `com.cloudai.bpmn.api.domain.ApprovalVo`（cloud-bpmn-api） |
| notify | `GET /system/notify/summary`（契约 2026-11-08-notify-api §3.1） | `com.cloudai.system.api.domain.NotifySummaryVo`（cloud-system-api） |

提供方修改上述端点出参时，必须同步本契约 §1.3 与镜像类——本契约是字段漂移的检查基准。聚合层对段数据零加工（纯透传），字段语义解释权归下游真源契约。

---

## 4. 错误码

- **新分段 7xxx 归 cloud-aggregate**，接续规则：聚合域新增错误码从 7003 起接续分配。
- 本轮分配：7001 段无权限 / 7002 段下游失败（均为段状态值，不作为整体 code 出现）。
- 整体维度沿用通用段：200 成功 / 401 未认证 / 500 系统异常。

---

## 5. 权限

- 聚合端点本身**登录即可**（无 perms，不新增菜单种子）——权限把关在下游段。
- 段与权限对应关系（冻结，聚合层段准入预判与下游 `@PreAuthorize` 同源同判）：

| 段 | 下游 perms | 无该权限时的段表现 |
|---|---|---|
| todo | `bpmn:task:list` | 7001 无操作权限 |
| approval | `bpmn:approval:list` | 7001 无操作权限 |
| notify | 登录即可（无 perms） | 无 7001 路径 |

- 段准入预判依据 = 聚合层收到的 `X-User-Perms`（网关注入的登录快照），与下游 authorities 同源，预判与下游判定等价；误放行由下游 403 兜底（安全无损），权限快照透传语义（权限变更需重新登录生效）与前端直调一致。

---

## 6. 网关路由与白名单

- 新增 lb 路由：`cloud-aggregate` / `lb://cloud-aggregate` / `Path=/aggregate/**` / `StripPrefix=1`。
- 鉴权白名单追加 `/aggregate/demo`（连通性冒烟）；`/aggregate/workbench` 走默认 JWT 鉴权 + 五头注入。
- 不新增屏蔽段（聚合层无 `/inner` 面）。

---

## 7. 下游依赖与超时预算

| 段 | 下游服务 | 下游端点 | Feign 超时 | 失败段表现 |
|---|---|---|---|---|
| todo | cloud-bpmn | GET /task/todo?pageNum=1&pageSize=5 | connect 1s / read 3s | 7002 |
| approval | cloud-bpmn | GET /approval/page?pageNum=1&pageSize=5 | connect 1s / read 3s | 7002 |
| notify | cloud-system | GET /notify/summary | connect 1s / read 3s | 7002 |

三段并行执行，端到端 P99 ≤ 3.2s；前端 axios 全局超时 15s 不变。下游不可用由各 client fallbackFactory 降级（`R.fail("cloud-bpmn 服务不可用")` 形态，code=1002）→ 转译 7002。

---

## 8. 前端消费约定（dashboard 改造点）

1. 新增 `api/aggregate.ts`：`getWorkbench(): Promise<WorkbenchVo>` → `GET /aggregate/workbench`；types 增量三段接口与段数据类型（复用既有 `TaskVo / ApprovalVo / NotifySummaryVo` 类型定义，字段名逐字一致）。
2. dashboard 页：`onMounted` 改为单请求 `getWorkbench()`；卡片显隐由 `段.status` 驱动——200 渲染数据、7001 整卡隐藏、7002 渲染空态 + `段.msg` 提示；`status !== 200` 时 `data` 恒 null，**不得**解引用。
3. 视觉与交互不变：卡片标题/结构/加载态/空态文案（「暂无待办任务」「暂无审批记录」）/「查看全部」跳转（`/bpmn/task`、`/bpmn/approval`）/ 状态 tag 颜色映射全部保留。
4. 退役项：页内 `hasPerm('bpmn:task:list')` / `hasPerm('bpmn:approval:list')` 前端判断与双请求并行编排逻辑删除（显隐职责移交段状态）；前端不再发 `/bpmn/task/todo` 与 `/bpmn/approval/page` 请求（这两个端点本身保留，供各自列表页使用）。
5. 顶栏通知轮询（30s summary）不在本轮范围，保持现状。

---

## 9. e2e 影响面

- `run-bpmn-e2e.mjs` BP12（工作台两卡真链路断言）与聚合七脚本的侧边栏形态（20 项，无新菜单）应原样绿——前端改造不得破坏其断言。
- 新增 `cloud-e2e/run-aggregate-e2e.mjs` 专线（route mock 三态：全成功 / 7001 段 / 7002 段），不进聚合回归脚本（沿 taskcenter/aichat mock 分层先例）。
