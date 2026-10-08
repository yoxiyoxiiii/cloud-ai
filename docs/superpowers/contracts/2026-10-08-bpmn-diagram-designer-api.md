# bpmn 流程图渲染 + 在线设计器 + 工作台 API 契约（cloud-bpmn：定义 XML / 部署 / 请假单图数据）

- 日期：2026-10-08
- 状态：现行（Round H）——配合设计 `docs/superpowers/specs/2026-10-08-bpmn-diagram-designer-workbench-design.md`，计划 `docs/superpowers/plans/2026-10-08-bpmn-diagram-designer-workbench.md`
- **与既有契约的关系**：对 bpmn-leave-api v1（2026-10-07）做 **additive 声明**——§4 定义端点扩容（xml/deploy）、§5 错误码账本 4xxx 接续（4008/4009）、§9 权限全集 7→8（+bpmn:definition:deploy）、§10 类型 additive。既有 8 端点、入参、出参、错误语义零变化；通用约定沿用 bpmn-leave-api §0（R<T>、HTTP 恒 200、Long→String、yyyy-MM-dd HH:mm:ss）
- 拍板口径（用户已确认）：npm 例外 bpmn-js（渲染 NavigatedViewer + 设计器 Modeler）；设计器 API 面仅「看 XML + 部署」（不做挂起/激活，删除永不做）；工作台两卡（待办 + 我的申请最近列表，纯前端复用既有端点，零新端点）
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单（相对 bpmn-leave-api v1）

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 0.1 | §4 定义端点 | 新增 GET /bpmn/definition/{id}/xml（本文 §2.1）与 POST /bpmn/definition/deploy（本文 §2.2）——v1「只读——无部署/删除/挂起端点」改写为「有 xml/deploy 两端点；无删除/挂起/激活端点（拍板：删除永不做）」 | additive + 边界改写 |
| 0.2 | §5 错误码 | 4xxx 账本接续 4008/4009（本文 §4 为增量权威；4001-4007 零变化） | additive |
| 0.3 | §9 菜单种子 | sys_menu 新增 331（F，挂 33 流程定义下，perms bpmn:definition:deploy，is_builtin=1；admin 绑定增量）——权限全集 7→8 | additive |
| 0.4 | 工作台 | 零新端点、零契约变更——纯前端消费既有 GET /bpmn/task/todo 与 GET /bpmn/leave/page（本文 §6 为消费声明，非端点定义） | 声明性 |
| 0.5 | npm 例外 | 前端新增 bpmn-js + bpmn-js-properties-panel 两依赖——属 spec D1 记档项（范围限定本轮渲染/设计器），契约不约束依赖面，此处仅登记 | 记档 |

## 1. 域语义增量

- **图渲染两段式数据流**：请假单详情图 = `GET /bpmn/leave/{id}/diagram`（定 definitionId + 高亮数据）→ `GET /bpmn/definition/{definitionId}/xml`（取原始 XML）→ 前端 bpmn-js NavigatedViewer 渲染；定义页查看图 = 仅 §2.1（无高亮）。diagram 端点**不内嵌 XML**（单一职责，xml 端点两消费位共用）
- **businessKey 锚点**：diagram 经 `HistoricProcessInstanceQuery.processInstanceBusinessKey(leaveId)` 定位实例——历史表对运行中/已结束/已撤销实例均有痕（TaskAppService 既有依赖同款），不依赖 bpmn_leave.process_instance_id（撤销后已置 null）
- **版本语义（防呆口径，UI 须明示）**：引擎原生行为——在途实例继续走其发起时的定义版本；新部署仅对新发起实例生效（同 key → version+1）
- **部署安全模型**：部署的 BPMN 中 `${...}` 表达式在流程运行时被执行——本端点等同高权限操作。缓解面四条：①权限 bpmn:definition:deploy 种子仅绑定 admin；②文件大小上限 2MB + 非空校验；③Flowable 部署期 XML 解析/schema 校验（失败统一 4009，引擎栈只进日志不透 body）；④XXE 防护**已验证闭合**（spec D6 实证记档：flowable 7.2.0 加固 StAX 三属性 false + DOCTYPE/外部实体探针实测 4009 拒绝且 ACT_RE 零残留；结论绑定 7.2.0，升级须重跑探针）
- **definitionId 形态**：`key:version:generated`（如 `leave_approval:1:4`）——冒号为合法路径字符，URL 无需编码，@PathVariable 直收

## 2. 流程定义端点扩容（网关前缀 /bpmn/definition）

### 2.1 定义 XML `GET /bpmn/definition/{id}/xml`（perms `bpmn:definition:list`）

- 入参：`@PathVariable("id") String id`（definitionId 全形态）
- 数据源：`repositoryService.getProcessModel(id)`——返回**部署时原始资源**字符串（UTF-8），非 BpmnModel 往返重建（注释等细节零丢失）
- 返回 `R<DefinitionXmlVo>`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| id | string | 是 | 定义 id（回显入参） | `"leave_approval:1:4"` |
| key | string | 是 | 定义 key | `"leave_approval"` |
| name | string \| null | 是 | 定义名 | `"请假审批"` |
| version | string | 是 | 版本（int 字符串化） | `"1"` |
| xml | string | 是 | BPMN 2.0 XML 原文（含中文，UTF-8） | `"<?xml version=..."` |

- 错误码：4008（定义不存在——id 非法或已不在库）
- 消费位：定义页「查看流程图」弹窗、设计器「编辑」回填、设计器 XML 源码 tab

### 2.2 部署流程 `POST /bpmn/definition/deploy`（perms `bpmn:definition:deploy`）

- 入参：`multipart/form-data`（**非 JSON body**，定夺理由见 spec D5——文件字节按 UTF-8 原样传输，天然规避 Git Bash curl 中文 GBK 陷阱；前端 Modeler 导出 Blob 与本地 .bpmn 文件上传同构）：

| 字段 | 类型 | 必填 | 校验 | 示例 |
|---|---|---|---|---|
| file | file | 是 | 非空；大小 ≤ 2MB（业务上限，见下方三段式）；内容经 Flowable 部署期解析 + schema 校验（任一不过 → 4009） | `leave_approval.bpmn20.xml` |

- **大小上限三段式（4009 形态闭合，主控审查修订）**：①业务上限 2MB = Service 前置校验（覆盖 ≤3MB 全区间→4009）；②Spring multipart 解析层 `spring.servlet.multipart.max-file-size: 3MB` / `max-request-size: 4MB`——落 cloud-bpmn **本地 application.yml**（定夺：与业务上限强绑定跟代码走，不进 Nacos；Boot 默认 1MB 会使 1-2MB 合法文件死在解析层且非 4009 形态），解析层只兜 ≥3MB 极端值；③`MaxUploadSizeExceededException`（≥3MB）经 cloud-bpmn **本地** @RestControllerAdvice @ExceptionHandler 归口 `R.fail(4009, "流程文件无效或部署失败")`（HTTP 200 + body 4009；common GlobalExceptionHandler 零触碰）——**全尺寸域超限观测形态恒 4009**
- 语义：`repositoryService.createDeployment().addInputStream(文件名, 流).deploy()`；部署名取文件名；文件内每个 process 定义按 key 各自 version+1（同名原样重部署亦产生新版本——行为记档）
- 返回 `R<DeployResultVo>`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| deploymentId | string | 是 | 部署 id | `"5f3a..."` |
| definitions | DefinitionVo[] | 是 | 本次部署产生的定义（v1 §4.1 DefinitionVo 复用，**不做 latestVersion 过滤**） | `[{...version:"2"}]` |

- 错误码：4009（文件无效或部署失败——空文件/**任何尺寸超限**（2MB 业务校验与 ≥3MB 解析层拒绝统一归此，三段式见上）/解析失败/schema 校验失败/无 process 定义统一归此码；引擎根因 log.error 留档，msg 不透传引擎文案）
- 权限说明：`bpmn:definition:deploy` 种子仅绑 admin（§5）——普通用户得 HTTP 200 + body 403（@PreAuthorize 既有口径）；multipart 非 JSON body，Bean Validation 1001 不适用，校验在 Service 前置手写

## 3. 请假单图数据 `GET /bpmn/leave/{id}/diagram`（perms `bpmn:leave:list`）

- 入参：`@PathVariable("id") Long id`
- 返回 `R<LeaveDiagramVo>`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| definitionId | string \| null | 是 | 实例所用定义 id（历史实例锚点；历史缺失防御 null → 前端隐藏图区） | `"leave_approval:1:4"` |
| processInstanceId | string \| null | 是 | 实例 id | `"d7c1..."` |
| activeActivityIds | string[] | 是 | 当前活动节点（运行中取 `runtimeService.getActiveActivityIds`；终态/撤销恒空数组） | `["approval"]` |
| completedActivityIds | string[] | 是 | 已执行活动 id 集合（ACT_HI_ACTINST 时间升序去重；**含网关/事件节点与 sequenceFlow id**——E4 实测回流：ACT_HI_ACTINST 对 flow 亦有记录（如 flowStart/flowApprove），前端全量传 Viewer 即**连线着色白得**（addMarker 作用于 flow 元素即连线浅色高亮，F7 视觉已核）；多余 id 对 Viewer 无害） | `["start","flowStart","approval"]` |
| endActivityId | string \| null | 是 | 结束节点 id（endApprove/endReject）；审批中/撤销为 null | `"endApprove"` |

- **三态矩阵（高亮语义定夺，前端渲染依据）**：

| 请假单状态 | activeActivityIds | completedActivityIds | endActivityId | 前端主高亮 |
|---|---|---|---|---|
| 0 审批中 | 当前节点（MVP 恒 `["approval"]`） | start 等已执行 | null | active 节点 |
| 1 已通过 / 2 已拒绝 | `[]` | 全部已执行路径 | endApprove / endReject | end 节点 + 路径浅色 |
| 3 已撤销 | `[]` | 删除点前已执行 | null | 无主高亮，路径浅色 |

- 错误码：4001（请假单不存在，沿用）；**不设「无图」错误码**——历史实例缺失（理论防御态）返回 definitionId=null + 空数组，前端隐藏图区
- 撤销态说明（**实测定型，E4 回流取代「实现期验证」口径**）：deleteProcessInstance 后历史实例 endActivityId=null、completedActivityIds 截断至删除点（实测 `[start,flowStart,approval]`——实例于 approval 任务处被删）；契约可空语义定型不变

## 4. 错误码汇总（4xxx 增量权威；4001-4007 见 bpmn-leave-api §5 零变化）

| code | 含义（msg 逐字） | 出现端点 |
|---|---|---|
| 4008 | 流程定义不存在 | GET /bpmn/definition/{id}/xml |
| 4009 | 流程文件无效或部署失败 | POST /bpmn/definition/deploy |

## 5. 菜单种子增量（sys_menu 331，is_builtin=1；admin 绑定增量）

```sql
-- 增量脚本 2026-10-08-bpmn-deploy-menu.sql（存量库；基线 cloud_system.sql 同步同款行）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(331, 33, '部署流程', 'bpmn:definition:deploy', 'F', '', '', 1, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, 331, NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 331);
```

- 幂等核对（执行前，含墓碑——沿 30 段先例）：`SELECT id FROM sys_menu WHERE id = 331` 应 0 行，占用即停回报主控
- 权限全集（8）：v1 §9 七项 + `bpmn:definition:deploy`
- perms 快照时序同 v1 §9：落库后 admin 须重新登录（或 refresh）才生效
- e2e 迁移义务（备忘 13）：run-menu-e2e SEED_MENU_IDS 30→31、run-role-e2e R5a 同步 31 项精确；nav/dict 零迁移（F 节点不进导航、无新字典——核对结论见计划 E1）

## 6. 工作台消费声明（零新端点）

- **我的待办卡**：数据源 `GET /bpmn/task/todo`（v1 §3.1 原样），前端取前 5 条；可见性 = 快照含 `bpmn:task:list`，无权限**整卡隐藏**（v-perms 同向语义）；「查看全部」跳 `/bpmn/task`
- **我的申请卡**：数据源 `GET /bpmn/leave/page`（v1 §2.2 原样，`pageNum=1&pageSize=5`）；可见性 = 快照含 `bpmn:leave:list`，无权限整卡隐藏；「查看全部」跳 `/bpmn/leave`
- 两卡空态文案「暂无待办任务」/「暂无申请记录」；降级链沿 v1 §7（`statusLabel ?? 本地映射 ?? 原值`）
- 工作台本身恒可见（静态页不在 sys_menu）——两卡是其中按权限显隐的局部，不改「零菜单用户有落点」语义

## 7. TypeScript 类型字典（types/api.ts additive）

| 类型 | 字段 |
|---|---|
| DefinitionXmlVo | id/key/name(string\|null)/version/xml（全 string，§2.1 表） |
| DeployResultVo | deploymentId:string；definitions:DefinitionVo[]（§2.2 表） |
| LeaveDiagramVo | definitionId:string\|null；processInstanceId:string\|null；activeActivityIds:string[]；completedActivityIds:string[]；endActivityId:string\|null（§3 表） |

api/bpmn.ts 增量三函数：`getDefinitionXml(id)` / `deployDefinition(file: File)`（FormData，注释注明 multipart 非 JSON body）/ `getLeaveDiagram(id)`。

## 8. 测试与验收口径

- curl 卡点清单（计划 B5）：xml 端点（leave_approval 最新版）、diagram 三态各一单、deploy 原样重部署 → version+1、4008（乱 id）/4009 三路（空文件/超限/坏 XML）、无 deploy 权限 403 形态
- e2e（计划 E 章）：BP9-BP12 新增 + BP6/BP8 定义页断言迁移（操作列出现后既有「无操作列/无写按钮」断言反转）
- 部署产生的 v2+ 定义允许残留（latestVersion 过滤下 UI 恒显最新版，行为零变化——原样重部署语义）

## 给 backend-agent / frontend-agent / e2e 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-08-bpmn-diagram-designer-workbench.md`（后端章 B1→B5 ∥ 前端章 F1→F7 + e2e 章 E1→E4）。红线：契约定稿后两端不得单方改；既有 8 端点 additive-only；4001-4007 零变化；common 模块零触碰；不做删除/挂起/激活端点；EP 按需（bpmn-js 两包为拍板例外，spec D1 记档）；e2e 黑盒。
