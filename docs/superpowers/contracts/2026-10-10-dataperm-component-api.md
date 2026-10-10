# 数据权限组件化 API 契约（服务间求值 + bpmn 审批语义变更）

- 日期：2026-10-10
- 状态：**定稿**——由架构-agent 出品，配合组件化需求（设计 `docs/superpowers/specs/2026-10-10-dataperm-component-design.md` D18-D28，计划 `docs/superpowers/plans/2026-10-10-dataperm-component.md`）
- **与既有契约的关系**：本契约含**一个新契约面**（§2 服务间求值 inner 端点——后端唯一对齐物，前端不消费）+ **三处既有端点语义变更**（§3 bpmn 审批分页/详情/图数据，取代 2026-10-08-approval-platform-api §3.1/§3.2/§3.4 的「恒按申请人」与「无归属校验」描述）+ **一处既有端点数据面扩展**（§4 my-scope 经 resource=bpmn_approval 直接可用，端点零变更）。数据权限配置台 9 端点（2026-10-10-data-permission-api §3）**零触碰**。通用约定（R 结构 / HTTP 恒 200 + body.code / Long→String / 时间 `yyyy-MM-dd HH:mm:ss` / 错误码分段）沿用 pilot §1
- 约定：前后端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | `POST /inner/data-perm/evaluate`（system，Feign 专用） | 新增：跨服务数据权限求值，恒留痕（D7）；网关屏蔽 /system/inner/** 不经网关 | **新契约面** |
| 2 | `POST /inner/data-perm/deny`（system，Feign 专用） | 新增：deny 安全审计补痕（D13） | **新契约面** |
| 3 | 审批分页 `GET /bpmn/approval/page` | 恒按申请人 → **按当前登录人数据权限规则求值**（无规则=仅自己，行为不变；admin 种子=全部）；`title` 可能 null/`***`（列规则） | **语义变更**（§3.1 取代 2026-10-08 §3.1） |
| 4 | 审批详情 `GET /bpmn/approval/{id}` | 无归属校验 → **行级判定**（不通过 → **4018** + deny 补痕）；`title` 列级同列表 | **语义变更 + IDOR 收口**（§3.2） |
| 5 | 审批图数据 `GET /bpmn/approval/{id}/diagram` | 同详情行级判定（**4018**） | **语义变更 + IDOR 收口**（§3.3） |
| 6 | my-scope `GET /system/data-perm/my-scope` | resource 取值域新增 `bpmn_approval`（注册表 registerRemote）——bpmn 审批页提示条直接调用，端点零变更 | 数据面扩展 |
| 7 | resources `GET /system/data-perm/resources` | 下拉数据自然多出 `{resource:"bpmn_approval", columns:["title"]}` | 数据面扩展（零代码变更） |
| 8 | 错误码段 | bpmn 段**占用 4018**（现用至 4017）；system 段仍至 3034 | 接续声明 |
| 9 | 种子数据 | admin 角色行规则种子 `bpmn_approval/ALL`——admin 审批列表「仅自己」→「全部」 | 数据扩张（D27） |

## 1. 域语义增量（跨服务求值特有，承接 data-permission-api §1）

- **资源 `bpmn_approval`**：新注册跨服务资源（provider 注册表 registerRemote 纯字符串注册，D22），可配列 `title`；行级过滤列 = `bpmn_approval.apply_user`（与 leave 的 apply_user 同构——DataScope 按账号列过滤的既定事实延续）
- **求值链路**：bpmn 读路径每次列表/详情**经 Feign 实时求值**（system 侧 4-5 次索引查询 + 留痕），规则/部门/挂载变更**即时生效**（无缓存无快照——与 leave 同语义）
- **降级语义 fail-closed**：system 不可用/熔断打开期，bpmn 列表/详情**拒绝访问**（1002 + 「数据权限服务不可用，请稍后重试」toast），不降级为无过滤或空集冒充正常（设计 D21）
- **留痕集中**：bpmn 读路径的决策留痕/deny 留痕全部落 system 库 sys_data_perm_log（数据权限页「决策留痕」tab 按 resource=bpmn_approval 可查）
- **详情弹窗双留痕记档**：详情与图数据各自独立求值各留一条 detail 留痕（打开含图的详情 = 最多两条），属各自真实决策

## 2. 服务间求值端点（/inner/data-perm，cloud-system，Feign 专用——前端不消费）

网关已屏蔽 `/system/inner/**`；无 @PreAuthorize（沿 inner 惯例，网格内信任模型——同 getUserByAccount）。消费方：cloud-bpmn（`@EnableFeignClients` 显式列表 + DataPermClient）。

### 2.1 求值 `POST /inner/data-perm/evaluate`

- 入参（JSON body，DataPermEvaluateRequest）：

| 字段 | 类型 | 必填 | 说明 | 示例 |
|---|---|---|---|---|
| account | string | 是（空白 → 1002） | 决策对象账号（消费方从自己 SecurityContext 显式传入，D20） | `"zhang3"` |
| resource | string | 是（空白或未注册 → 3034） | 资源标识（注册表管辖，含远程资源；assertResource 一站式——isRegistered(空白)=false 天然覆盖，无特判分支） | `"bpmn_approval"` |
| operation | string | 是（`list`/`detail`，空白 → 1002） | 操作类型（留痕 operation 列） | `"list"` |
| businessKey | string | 否 | 业务键（detail 时为目标单据 id；list 为 null） | `"1234567890"` |

- 行为：account→用户解析（不存在/停用不报错，按无规则默认 SELF={account} 收敛——方向安全不越权）→ 完整求值（D3 多规则并集宽松者胜 + D10 实时）→ **恒留痕一条**（list/detail，D7；留痕失败 catch 不抛）
- 返回 `R<DataPermScopeVo>`（§5.1）
- 错误码：1002（account/operation 空白）/ 3034（resource 空白或未注册）；401/403 不适用（inner）

### 2.2 deny 补痕 `POST /inner/data-perm/deny`

- 入参（JSON body，DataPermDenyRequest）：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| account | string | 是 | 越权尝试者账号 |
| resource | string | 是（空白或未注册 → 3034） | 资源标识 |
| businessKey | string | 是 | 目标单据 id |

- 行为：补插一条 `operation=deny` 留痕（scopeSummary 固定 `deny`，D7/D13 同款）+ 服务日志 log.warn；**不返回任何范围数据**
- 返回 `R<Void>`
- 错误码：3034；消费方对降级 R（1002）仅记日志不阻断已发生的拒绝（设计 D21）

### 2.3 Feign 客户端契约（cloud-system-api `client/DataPermClient`）

- `@FeignClient(name="cloud-system", contextId="dataPermClient", path="/inner/data-perm", fallbackFactory=DataPermClientFallbackFactory.class)`
- fallback：两端点均 `R.fail(1002, "数据权限服务不可用...")`（fail-closed）；消费方统一 `!r.isSuccess() → throw BusinessException(r.getMsg())`，**任何分支不得在求值失败时返回数据**
- 消费方接入三件套（CLAUDE.md Feign 规范）：api jar 已在依赖树（bpmn 现状）；`@EnableFeignClients` 显式列表加 `DataPermClient.class`；yml circuitbreaker 已开（零配置增量）

## 3. bpmn 审批端点语义变更（/bpmn/approval，网关前缀 /bpmn）

### 3.1 分页 `GET /bpmn/approval/page`（行级 + 列级）

- 权限：`bpmn:approval:list`（不变）；入参 PageQuery（不变）
- **行为变更**：恒按申请人 → 按当前登录人数据权限求值：
  - 命中全部档（或 admin 种子规则）→ 全量行集
  - 无规则 → 默认仅自己（**普通用户行为与旧版逐字一致**，向后兼容）
  - 展开为空集（如未挂部门用户仅有本部门档规则）→ 返回空页（不查库）
  - **system 不可用 → 1002「数据权限服务不可用，请稍后重试」拒绝**（fail-closed，不返回数据）
- **出参变更**：行内 `title` 可能 null（列隐藏）或 `"***"`（列脱敏）——前端原样展示；其余字段零变化
- 返回 `R<PageResult<ApprovalVo>>`
- 错误码：1002（降级）/ 401/403

### 3.2 详情 `GET /bpmn/approval/{id}`（行级判定 + IDOR 收口）

- 权限：`bpmn:approval:list`（不变）
- **行为变更**：无归属校验 → 读出行后按求值结果判定 `apply_user` 可见性：
  - 单据不存在 → 4010（不变，真不存在）
  - 归属账号不在范围 → **4018 无权访问该审批单**（同时 deny 远程补痕一条）
  - 可见 → `title` 列级同列表应用（null/`***`）；steps 时间线/译文逻辑不变
- 返回 `R<ApprovalDetailVo>`（结构不变）
- 错误码：4010 / **4018** / 1002（降级）/ 401/403
- 前端消费：**是**（4018 由拦截器统一 toast，与 leave 页 3026 同款处理——详情弹窗打不开即为无权，不做二次提示）

### 3.3 图数据 `GET /bpmn/approval/{id}/diagram`（行级判定）

- 权限与入参不变；**行为变更**：同详情行级判定（单据不存在 4010 → 不在范围 **4018** + deny 补痕 → 可见返回图数据）
- 返回 `R<ApprovalDiagramVo>`（结构不变，历史缺失防御态不变）
- 错误码：4010 / 4018 / 1002 / 401/403

### 3.4 撤销 `PUT /bpmn/approval/cancel/{id}`（**零变更**）

本人校验（4012）语义与数据权限正交，不在本轮收口面——明确记档。

## 4. my-scope 复用（/system/data-perm/my-scope，端点零变更）

- bpmn 审批页提示条直接调用既有免注解端点：`GET /system/data-perm/my-scope?resource=bpmn_approval`（经网关正常登录态）——返回 MyScopeVo（scopeLabel 五态判定序 / columnSummary 沿 data-permission-api §6.8 逐字不变）
- 前端消费：**是**（审批列表页顶部 el-alert，复用 leave 页 L136-145/L223-228 范式：`当前数据范围：${scopeLabel}${columnSummary ? （...） : ''}`）
- 错误码：3034（resource 非法）/ 401

## 5. VO 字段表

### 5.1 DataPermScopeVo（§2.1 出参——服务间窄契约，前端不消费）

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| rowAll | boolean | 是 | 行范围=全部（过滤豁免） | `false` |
| accounts | string[] | 是 | 账号白名单（rowAll=true 时 `[]`；空数组+rowAll=false=空集短路） | `["zhang3","lisi4"]` |
| hiddenColumns | string[] | 是 | 隐藏列清单（无则 `[]`） | `["title"]` |
| maskedColumns | string[] | 是 | 脱敏列清单（无则 `[]`） | `[]` |

（命中规则明细/中文解释不在契约面——设计 D19；explain/my-scope 是 system 自有端点已覆盖。）

### 5.2 DataPermEvaluateRequest / DataPermDenyRequest

字段集见 §2.1/§2.2 入参表（DTO，消费方 @RequestBody 传入）。

## 6. 错误码总表（bpmn 段本轮占用 4018）

| 码 | 文案 | 触发点 |
|---|---|---|
| 4018 | 无权访问该审批单 | 审批详情/图数据行级拒绝（§3.2/§3.3；deny 同时补痕） |

1002 复用文案（本轮新增语义）：数据权限服务不可用，请稍后重试（fail-closed 降级，§3.1/§3.2/§3.3；evaluate/deny inner 的 account/operation 空白同 1002 通用）。3034 沿用（resource 空白或未注册，§2.1/§2.2/§4）。

## 7. 种子与权限快照（对前端/e2e 的可观察影响）

1. 种子：`sys_data_perm_rule` 增 admin 角色 `bpmn_approval/ALL` 行（增量脚本 + 基线 cloud_system.sql 同步）——无菜单/perms 种子（复用 `bpmn:approval:list` 既有）
2. admin 行为变化：审批列表由「仅自己发起」→ **全部**（D27，与 leave 轮同款语义变更）；无规则普通用户不变（默认仅自己）
3. e2e 断言维护：bpmn 既有 e2e 若有 admin 视角「仅自己」审批行集断言须按上条维护；dataperm 既有 e2e 的规则分页若断言全表总数需 +1（resource 筛选场景不受影响）——计划 E 章核对

## 8. 验收口径（联调与 e2e 共同遵守）

- 三账号矩阵（同一 /bpmn/approval/page 三态）：admin=全部（种子）/ 主管角色+部门档=部门成员发起的单 / 无规则用户=仅自己（与旧版一致）
- 列级：给主管角色配 bpmn_approval title=脱敏 → 列表与详情 title=`***`；用户直绑 title=隐藏 → title 空
- 排查链（system 数据权限页）：resource=bpmn_approval 留痕（list/detail 各一条要素齐）→ 越权详情 4018 + deny 留痕落库 → explain 复算一致
- my-scope 三账号标签与实际行集一致（审批页提示条）
- fail-closed：停 system（或熔断打开）→ bpmn 审批列表/详情 toast「数据权限服务不可用」，不返回任何数据行
- 既有回归：e2e:dataperm（leave 零变化）+ e2e:bpmn（§7.3 维护点外零变化）
