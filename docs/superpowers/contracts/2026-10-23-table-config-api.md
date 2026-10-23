# 用户表格列配置 API 契约（新资源域 v1）

- 日期：2026-10-23
- 状态：**定稿**——架构-agent 出品；设计 `docs/superpowers/specs/2026-10-23-table-config-design.md`（决策 D1-D13）、计划 `docs/superpowers/plans/2026-10-23-table-config.md`
- **契约形态说明**：新契约文档（非增量修订）——表格配置是新资源域，不挂任何既有契约体系；与数据权限域的唯一交点是**只读消费** my-scope 既有出参 `columns`（契约 `2026-10-22-dataperm-column-visibility-api.md` §1.1，零改动）
- 约定：前端实现与本文档冲突时，以本文档为准；发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 域说明

每个登录用户对自己的每张表格保存**个人视图偏好**：列顺序（拖拽）、列显隐（自选）、列宽（拖拽后持久）。系统统一分配的列级数据权限是硬边界（权限隐藏列不进用户配置面；权限脱敏列允许用户隐藏）；行级数据权限与本域无关。服务端**盲存**：只校验格式与上限，不理解列含义，不维护 tableKey 注册表（设计 D3/D4）。

- 服务：cloud-system（网关前缀 `/system`）
- 鉴权：登录即可（网关 JWT 链前置）——三端点**均免 @PreAuthorize**（语义=用户自理本人偏好，非管理能力，沿 my-scope 先例）；`userId` 一律由服务端从登录态取，**任何端点不收前端传 userId**
- 权限标识：**零新增**（无 sys_menu 种子、无 perms 快照变化、导航断言零维护）
- 错误码：**零新增**（校验失败 1002 + 两条新文案，§5）；system 段现值仍至 3042

## 1. tableKey 命名约定（域级约定）

| 项 | 约定 |
|---|---|
| 形态 | `<模块>:<页面>`，如 `system:user`、`system:dict` |
| 字符集 | `[A-Za-z0-9:-]` |
| 长度 | 1-64 |
| 前端声明点 | `cloud-web/src/types/api.ts` 常量唯一声明（`TABLE_KEY_USER = 'system:user'` / `TABLE_KEY_DICT = 'system:dict'`），页面一律引常量，禁字面量 |
| 服务端口径 | 开放字符串：只按 §1 校验字符集与长度，不维护合法 key 清单 |

配置 JSON 内 `key`（列标识）约定 = **VO 字段名**（如 `account`/`mobile`/`dictName`）——与数据权限 columnKey 同一命名空间，user 页权限列与配置列天然同键（设计 D4）。

## 2. 端点

### 2.1 查询本人配置 `GET /system/table-config/{tableKey}`

- 登录即可（免 @PreAuthorize）；查登录人自己的配置
- **返回 `R<TableConfigVo>`**：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| columns | TableColumnConfigVo[] | 是 | 列配置有序数组（顺序即列序）；**无配置时 data=null**（非空数组）——前端按页面默认列序渲染 | 见下 |

`TableColumnConfigVo`：

| 字段 | 类型 | 必返 | 说明 | 示例 |
|---|---|---|---|---|
| key | string | 是 | 列标识（VO 字段名约定，§1）；字符集 `[A-Za-z0-9_]`，长度 ≤32 | `"account"` |
| visible | boolean | 是 | true=显示 / false=用户自选隐藏 | `true` |
| width | number \| null | 是 | 列宽 px（拖拽后持久）；null=未自定义（用页面列定义默认宽） | `160` / `null` |

- 示例：`{"code":200,"msg":"ok","data":{"columns":[{"key":"account","visible":true,"width":160},{"key":"nickname","visible":false,"width":null}]}}`
- 无配置示例：`{"code":200,"msg":"ok","data":null}`
- 幂等（GET）；锁列（选择列/操作列）**永不出现**在 columns 中（保存时前端已过滤）

### 2.2 保存本人配置 `PUT /system/table-config/{tableKey}`

- 登录即可；**整体覆盖**（upsert：无行 insert、有行 update config+update_time；并发首次保存撞唯一键由服务端幂等收敛为 update，不报错）
- **入参 body（TableConfigSaveRequest）**：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| columns | TableColumnConfigDto[] | 是 | 有序列配置数组（顺序即列序）；元素字段同 §2.1 TableColumnConfigVo（key/visible/width，width 可缺省） |

- 示例：`{"columns":[{"key":"account","visible":true,"width":160},{"key":"mobile","visible":true,"width":null},{"key":"nickname","visible":false,"width":null}]}`
- 返回 `R<Void>`（data=null）
- **服务端校验链（1002，§5 文案）**：tableKey 字符集/长度；body 可解析且结构符 `{"columns":[…]}`；每项 key 字符集 `[A-Za-z0-9_]` 长度 ≤32、visible 为 boolean、width 缺省或 40-1000 整数；列数 ≤50；序列化总长 ≤4000。**盲存**：不校验 key 是否为某页面的真实列（页面演进自由）
- 幂等：同一 body 重复 PUT 结果一致

### 2.3 恢复默认（删本人配置）`DELETE /system/table-config/{tableKey}`

- 登录即可；物理删行（配置态无逻辑删除，沿 sys_data_perm_rule 先例）
- 返回 `R<Void>`（data=null）；**行不存在同样成功**（幂等）
- 语义：删除后 GET 回 data=null，前端回页面默认列序

## 3. wire 冻结点

- 配置结构 `{"columns":[{key,visible,width}]}` 三字段——width 可选性、数组顺序语义、锁列不进配置
- GET 无行 = `data:null`（不是空数组、不是 404）
- 三端点均不收 userId 传参（服务端登录态取）——前端不得造任何携带 userId 的调用形态

## 4. DDL 与种子

见设计文档 §3.1（`sys_table_config`，uk_user_table，零二级索引，零种子）——增量脚本 `cloud-base/scripts/sql/2026-10-23-table-config.sql` + 基线 `cloud_system.sql` 同步。

## 5. 错误码（零新增声明）

| 场景 | code | msg |
|---|---|---|
| tableKey 字符集/长度不符 | 1002 | `表格标识格式无效` |
| body 结构/逐项/上限校验不过 | 1002 | `列配置格式无效`（后端可在 msg 内附具体原因，前端只 toast 不分流） |

本轮**零新增错误码**；system 段现值仍至 **3042**，下轮 3043 接续。

## 6. 前端类型增量（types/api.ts + api/tableConfig.ts）

- 类型：`TableConfigVo { columns: TableColumnConfig[] }`、`TableColumnConfig { key: string; visible: boolean; width: number | null }`、保存 payload `TableConfigSavePayload { columns: TableColumnConfig[] }`
- 常量：`TABLE_KEY_USER = 'system:user'`、`TABLE_KEY_DICT = 'system:dict'`（§1 声明点）；防抖常量在 composable 内部
- api 模块（新建 `cloud-web/src/api/tableConfig.ts`）三函数：
  - `getTableConfig(tableKey: string): Promise<TableConfigVo | null>`（GET；data=null 解包即 null）
  - `saveTableConfig(tableKey: string, payload: TableConfigSavePayload): Promise<null>`（PUT）
  - `resetTableConfig(tableKey: string): Promise<null>`（DELETE）
- composable `useTableConfig` 与组件 `TableConfigButton` 非契约面（工程内部封装，形态见设计 D7-D11）

## 7. e2e 影响面

- 新脚本 `cloud-e2e/run-tableconfig-e2e.mjs`（e2etc 前缀造数）：双页配置持久化（顺序/显隐/列宽→reload 断言）+ 权限交互（配 email 隐藏规则后面板无该列且列不渲染、存量配置含权限列无害）+ 恢复默认；场景清单见计划前端章 T5
- 既有回归零维护预期：e2e:dict / e2e:user（列数组化后既有 DOM 断言不破——列头文案不变）、e2e:dataperm-usercolumn（动态列链路零改）、e2e:nav（无新菜单）
