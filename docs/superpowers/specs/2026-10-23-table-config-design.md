# 用户自定义表格配置技术方案（列顺序/显隐/列宽按用户按表格持久化）

- 日期：2026-10-23
- 状态：**定稿**——架构-agent 出品，配合拍板结论九项（2026-10-23，阶段一《需求分析》九问逐条拍板）；契约 `docs/superpowers/contracts/2026-10-23-table-config-api.md`（新契约文档，新资源域）、计划 `docs/superpowers/plans/2026-10-23-table-config.md`
- 拍板结论映射：Q1=A 配置维度=**顺序+显隐+列宽三项全含**，选择列/操作列为锁列；Q2=A **服务端新表**（每用户每表格一行，跨端漫游）；Q3=A **uk(user_id, table_key) + config JSON 串整存整取**；Q4=A **权限赢+交集过滤+存量配置不清洗**，配置面板只列权限允许的列，脱敏列标注「已脱敏」且允许隐藏；Q5=A 端点 **GET/PUT /system/table-config/{tableKey}**，免 @PreAuthorize 登录即可（对齐 my-scope 先例），userId 一律取登录态，错误码零新增；Q6=B **useTableConfig composable + 页面层配置驱动**（列定义数组 + v-for 渲染 el-table-column），ProTable 至多最小增量，不翻透传案；Q7=A **引入 sortablejs** 挂表头做列拖拽，计划排 POC 验证步，DOM 操作封装单点隔离；Q8=A 拖拽列序/列宽**即时保存（防抖）**，配置面板显隐**点确定保存**，PUT 整体覆盖幂等，失败 toast+回退内存态；Q9=B（用户改选）**user + dict 双页同批接入**，user 页顺迁 ProTable 一并做，两页都出 e2e 配置场景
- **与拍板的一处明示偏差**：拍板 Q5 只定了 GET/PUT 两端点；「恢复默认」交互需要清除语义（GET 无行=默认列序的闭环），本轮**补 DELETE 端点**（物理删行）——阶段一 Q5 候选清单本就列过「+可选 DELETE，或 PUT 空配置等价」，属补充采纳非翻案，见 D5

## 1. 需求与范围

### 1.1 目标

每个登录用户可以对自己的每张表格保存个人视图偏好：**列显示顺序（拖拽）、列显隐（自选）、列宽（拖拽后持久）**——服务端持久化、跨端漫游。系统统一分配的列级数据权限（隐藏/脱敏）是**硬边界**：权限隐藏的列用户配置中不可见不可选，权限脱敏的列用户可选择隐藏（值已是 `***`，隐藏纯展示偏好），用户可自由摆放权限允许的一切非锁列。

### 1.2 本轮落

| 项 | 内容 |
|---|---|
| DDL | 新表 `sys_table_config`（每用户每表格一行，config VARCHAR 存 JSON 串）+ 增量脚本 + 基线 `cloud_system.sql` 同步 |
| 后端 | `TableConfigController` 三端点（GET/PUT/DELETE，免 @PreAuthorize）+ `TableConfigService`（校验/upsert/物理删）+ mapper XML（手写 SQL）+ 单测 |
| 前端 | `useTableConfig` composable（合并函数单一落点+load/save/防抖/拖拽挂载）+ `TableConfigButton` 全局组件（列设置面板）+ ProTable 最小增量（expose el-table 实例）+ sortablejs 依赖 |
| 页面 | user 页顺迁 ProTable + 列定义数组化 + 权限/配置双清单合并；dict 页列定义数组化 + 接入配置（无权限面） |
| e2e | 新脚本 `run-tableconfig-e2e.mjs`（e2etc 前缀）+ 既有回归（dict/user/dataperm 家族/全量聚合） |

### 1.3 本轮不落（红线 + 移交）

**零变更红线**：数据权限求值链全部（求值器/列上下文/advice/MCP 通道——本轮只**读消费** my-scope 既有出参 `columns`，数据权限域零文件 diff）；`usePermColumns` composable 本体（按函数引用组合，见 D8）；ProTable 既有三能力（勾选/双击/分页）行为与 props 面；sys_menu 零新增（免权限注解，导航断言零维护）；错误码零新增；leave 页与其余列表页（role/menu/mqDlq）不动。

移交项见 §11。

## 2. 现状核实（2026-10-23 逐项复核）

1. **ProTable 形态**（`cloud-web/src/components/ProTable.vue`）：透传式包装——列由页面写 `el-table-column` 进默认插槽，组件只收编表格壳/勾选列/分页条；`inheritAttrs: false` + `v-bind="$attrs"` 透传——**页面直接写 `@header-dragend` 已可透传给 el-table**（列宽捕获零改）；expose 面现仅 `clearSelection()`，页面拿不到 el-table 实例（拖拽挂载需要，见 D9）
2. **消费页现状**：dict 页已迁 ProTable（4 数据列 + 操作锁列，`row-key="id"` 防 E3 渲染竞态）；**user 页未迁**（裸 el-table + el-pagination，10 列：账号/昵称/手机号/邮箱/状态/创建人/创建时间/更新人/更新时间/操作；mobile/email 列已走 `usePermColumns` 的 v-if 动态列）
3. **登录态取值**：`SecurityUtils.currentUser()` 返回 `LoginUser`（含 userId/account）——配置端点取 userId 的现成通道；网关鉴权保证登录态（未登录 401 前置拦截）
4. **工程 JSON 存储先例**：`sys_data_perm_rule.custom_accounts/predicates` 均 VARCHAR(n) 存 JSON 字符串（不用 MySQL JSON 类型）——本表沿先例
5. **配置态表先例**：`sys_data_perm_rule/column` 注释明记「配置态数据物理删除无 deleted 列」+ upsert 依据即 uk——本表同款口径
6. **sys_user.id 类型**：BIGINT AUTO_INCREMENT——user_id 列同型
7. **my-scope 免 @PreAuthorize 先例**：`DataPermController.myScope`（语义=用户自查本人范围非管理能力；挂权限注解会把普通用户的基本功能变成管理入口）——个人配置端点同款语义
8. **前端常量声明点惯例**：`types/api.ts` 内 `DATAPERM_RESOURCE_USER = 'user'` 同款——tableKey 常量同落点
9. **前端无单测设施**：cloud-web 无 vitest（验证靠 build 类型检查 + cloud-e2e 黑盒）——合并函数正确性走 e2e 断言，本轮不引单测框架
10. **拖拽依赖空白**：cloud-web dependencies 无任何拖拽库；el-table 无原生列顺序拖拽（列宽 resizable 原生但不持久）
11. **错误码账本**：system 段现用至 3042（2026-10-22 列权限轮声明）；本轮零新增，3043 继续留给下轮
12. **e2e 组织**：cloud-e2e/package.json scripts 逐脚本挂（e2e:dict/e2e:user/e2e:dataperm-usercolumn 等），聚合 `e2e` 不含 dataperm 家族与 AI 栈——新脚本同款独立挂 + 是否进聚合在计划定

## 3. 数据设计

### 3.1 新表（`cloud-base/scripts/sql/2026-10-23-table-config.sql` + 基线 `cloud_system.sql` 同步）

```sql
CREATE TABLE sys_table_config (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    user_id     BIGINT        NOT NULL COMMENT '用户ID（sys_user.id；服务端从登录态取，不收前端传参——端点只能读写本人配置）',
    table_key   VARCHAR(64)   NOT NULL COMMENT '表格标识（页面级唯一，命名 <模块>:<页面> 如 system:user / system:dict；字符集限字母/数字/冒号/连字符）',
    config      VARCHAR(4000) NOT NULL COMMENT '列配置 JSON 串（{"columns":[{"key":"account","visible":true,"width":160}]}；数组顺序即列序，width 可选；服务端盲存——只校验格式与上限，不校验列含义）',
    create_time DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME      DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_table (user_id, table_key)
) ENGINE = InnoDB COMMENT = '用户表格列配置（个人视图偏好：列顺序/显隐/列宽；权限是硬边界，隐藏列由列级数据权限在前端合并时另行过滤，本表只存偏好；GET 无行=页面默认列序；PUT 整体覆盖 upsert；DELETE 物理删行=恢复默认——沿 sys_data_perm_rule 配置态物理删先例无 deleted 列；无 create_by/update_by 冗余——操作人恒为 user_id 本人）';
```

**索引审查（规范强制）**：`uk_user_table (user_id, table_key)`——命中全部三端点的唯一查询路径 `WHERE user_id = ? AND table_key = ?`（等值定位），同时兼查重（upsert 判存依据，沿 sys_data_perm_rule 先例）；**零二级索引**——不存在按 table_key 跨用户、按时间、按用户全量的查询路径（无管理面，§11 记档）。零 ALTER（新表）。

**列取舍记档**：无 `deleted`（配置态物理删先例）；无 `create_by/update_by`（本人自治数据，操作人恒等于 user_id，冗余无审计价值）；审计时间两列保留且写入一律 SQL `NOW()`（同语句 create=update 同值口径）。

### 3.2 种子影响面

零种子数据（配置 lazy：GET 无行=默认，首次保存才落行）；sys_menu 零新增。

## 4. 设计决策（D1-D13）

### D1 域定位：个人视图偏好，与权限两层正交

三层模型：行级权限（系统，哪些行可见）→ 列级权限（系统，哪些列可见/值脱敏）→ **表格配置（个人，可见列怎么摆）**。本轮只新增第三层，对第二层**只读消费**：前端读 my-scope 既有出参 `columns`（数据权限域零文件 diff），后端零参与权限。合并语义（拍板 Q4）：**权限赢 + 交集过滤 + 存量配置不清洗**——渲染时对「页面列全集 ∩ 权限允许列」应用用户配置；配置 JSON 里存了权限已收回的列 key 渲染时自然忽略，权限恢复后用户旧配置自动生效，后端零清洗逻辑。

### D2 存储表：每用户每表格一行，config VARCHAR 存 JSON 串

沿工程 JSON 存储先例（现状核实 #4）与配置态表先例（#5），DDL 见 §3.1。整存整取——读写永远是「一表格一配置」整体操作，无按列/跨用户查询诉求，行转列徒增复杂度（拍板 Q3）。

### D3 tableKey 命名约定与声明点

- 命名 `<模块>:<页面>`，如 `system:user`、`system:dict`；字符集 `[A-Za-z0-9:-]`，长度 1-64
- 前端常量唯一声明点：`types/api.ts`（`TABLE_KEY_USER = 'system:user'` / `TABLE_KEY_DICT = 'system:dict'`，页面一律引常量——沿 DATAPERM_RESOURCE_USER 惯例）
- **后端不维护 tableKey 注册表**：校验字符集+长度即收（理由：配置是无 schema 偏好，页面演进自由；越权面为零——行按 (登录态 userId, tableKey) 定位，用户只能写自己的行；脏 key 只能污染自己的行，格式校验+长度上限兜底）。取舍记档：若未来要求服务端感知合法表格清单（如运维统计），再建注册表，属翻案须明示理由

### D4 config JSON 结构：盲存，键空间与数据权限 columnKey 同源

```json
{"columns": [{"key": "account", "visible": true, "width": 160}, {"key": "nickname", "visible": false}]}
```

- `key`：列标识。**约定 = VO 字段名**（account/mobile/dictName…）——与数据权限 columnKey 同一命名空间，user 页权限列与配置列天然同键，交集过滤零映射
- `visible`：boolean 必填（false=用户自选隐藏）
- `width`：可选，正整数 px（用户拖宽后持久；缺省=页面列定义的默认宽）
- 数组顺序即列序；**锁列（选择列/操作列）不进配置**（保存时由前端过滤）
- 服务端校验（**盲存**——不理解列含义，只管格式与上限）：JSON 可解析、结构符 `{"columns":[…]}`、每项 key 长度 ≤32 字符集 `[A-Za-z0-9_]`、visible 是 boolean、width 缺省或 40-1000 整数、列数 ≤50、序列化总长 ≤4000；任一不过 → 1002 + 文案（错误码零新增，D13）
- 盲存理由：后端无需感知页面列集合（页面演进零契约联动）；合法性兜底由格式与上限承担。否决备选：后端维护合法列清单校验——引入 tableKey→列集注册表，违 D3 开放口径

### D5 端点：GET/PUT/DELETE 三端点，免 @PreAuthorize，userId 取登录态

- `GET /system/table-config/{tableKey}` → `R<TableConfigVo | null>`：无行 `data=null`（=页面默认列序，不报错不建默认行）
- `PUT /system/table-config/{tableKey}`，body `TableConfigSaveRequest` → `R<Void>`：upsert 整体覆盖（幂等，last write wins）
- `DELETE /system/table-config/{tableKey}` → `R<Void>`：物理删行=恢复默认；**删不存在的行同样成功**（幂等友好）
- 免 @PreAuthorize、登录即可——完全对齐 my-scope 先例口径（现状核实 #7：用户自理本人数据非管理能力）；`userId = SecurityUtils.currentUser().getUserId()`，**不收前端传参**（防改他人配置）；未登录由网关鉴权链前置 401
- DELETE 为拍板外补充（见文档头部明示偏差），GET/PUT 语义与拍板逐字一致

### D6 upsert 并发口径：先查后写 + DuplicateKeyException 兜底转 update

按 uk 判存：有行 update（config/审计时间两值）、无行 insert；并发首次保存撞 uk 时 catch `DuplicateKeyException` **转 update 重试一次**（不报错）——配置整体覆盖语义下并发双写同值无害，last write wins（拍板 Q8 口径），无合并诉求。TOCTOU 防御沿用「唯一性查重 + DuplicateKeyException 兜底」规范形态，但兜底动作是幂等收敛而非业务报错（语义差异记档：本域并发冲突不是错误）。

### D7 前端合并函数单一落点：composable 内 pure 函数，页面列显隐收敛此点

`useTableConfig` composable 内合并函数 `mergeColumns(defs, isColumnHidden, savedConfig)`：

1. `defs`（页面列定义数组，锁列外）先过滤权限隐藏列（`isColumnHidden(key)` 为 true 剔除——**权限赢**）
2. savedConfig 中出现的 key（∩ 步骤 1 结果）按**存序**排列、应用 visible；width 覆盖默认宽
3. 未出现在 savedConfig 的剩余列（新列/配置未覆盖列）按 defs 原序**追加**在已配置列之后（新列不丢）
4. 返回有序列清单（含 visible/width），页面 `v-for` 直接渲染

- 输入响应式（权限集合/已存配置都是 ref）：函数包在 `computed` 内，任一来源变化自动重算——权限变更下一次加载即同步（与列权限即时生效语义对齐），存量配置永不清洗（D1）
- **锁列不进合并**：选择列由 ProTable 注入在首、操作列由页面固定 `fixed="right"` 恒在末尾模板直写（拍板 Q1）
- user 页现状散落的 mobile/email 列 v-if（usePermColumns 直驱）**收敛进合并函数**——改造后页面不再写列级 v-if，列显隐单点（红线：合并函数是唯一落点，禁页面散写）

### D8 useTableConfig API：与 usePermColumns 按函数引用组合，零改既有 composable

```ts
// cloud-web/src/composables/useTableConfig.ts
const tc = useTableConfig(TABLE_KEY_USER, {
  defs,                        // 页面列定义数组（锁列外）：{ key, label, minWidth?, align?, fixed? } 
  isColumnHidden?,             // 可选，(key) => boolean——传 usePermColumns 的 isHidden（dict 页不传=无权限面）
  isColumnMasked?,             // 可选，同上（面板「已脱敏」标注用）
})
// 暴露：columns（合并后有序列清单，computed）、load()、saveNow()、saveDebounced()、
//       reset()（DELETE+清内存态）、applyOrder(orderedKeys)（拖拽回调）、applyWidth(key, width)（列宽回调）
```

- 与 `usePermColumns` 的组合方式：**函数引用传参**——`isHidden` 内部读响应式 Set，`computed` 内调用时依赖收集成立，权限清单拉取完成自动重算列集；`usePermColumns` 本体零 diff（红线）
- 拉取时机：页面 `onMounted` 调 `load()`（与列权限 load、分页请求并行，不互相阻塞）；失败降级=按页面默认列渲染（个人偏好非安全面，fail-open 无害——与列权限的降级论证口径不同，此处降级仅丢偏好）
- 防抖：800ms（常量），拖拽/列宽回调走 `saveDebounced`，面板确定走 `saveNow`

### D9 拖拽机制：sortablejs 挂表头，DOM 操作封装单文件，POC 前置验证

- 依赖：`sortablejs`（运行时）+ `@types/sortablejs`（dev）；安装时取当前稳定版，体积记档（min 约 41KB/gzip 约 13KB，实现时以构建产物实测为准）
- 挂载点：ProTable 内 el-table 的 `thead tr`。**ProTable 唯一增量**：expose 补 el-table 实例透传（`ProTableExposed` 增 `getTable(): TableInstance | undefined`）——拿实例后 DOM 查询（`$el → .el-table__header thead tr`）全部收敛在 composable 单文件，EP 版本升级的 DOM 结构风险单点隔离（拍板 Q7 要求）；ProTable props/emits/三能力零改
- onEnd 口径：**先还原 DOM 再更新数据源**——sortable 的 onEnd 里把被移动的 th 放回原位，再调 `applyOrder` 更新列序数据，由 Vue v-for keyed 重排渲染（Vue 与 Sortable 双操作同一 DOM 是已知冲突源，先还原能规避；实现时以 POC 验证）
- 锁列禁拖：sortable `filter` 选项排除选择列/操作列的 th（按列头文案或 class 定位，POC 定）
- 列宽捕获：页面 `@header-dragend`（经 ProTable `$attrs` 透传，已支持零改）→ `applyWidth(key, width)` → 防抖保存
- **POC 验证步（计划前端章 T1，强制前置）**：最小验证「expose 透传→挂载→onEnd 还原+数据重排→v-for 协同无闪烁→锁列 filter」，POC 通过才铺开双页改造；POC 以临时文件做，验证完删除，不留在工程

### D10 TableConfigButton 组件：全局组件，设置入口 + 列配置面板

- 落位 `cloud-web/src/components/TableConfigButton.vue`（unplugin-vue-components 自动注册，页面免 import）
- 形态：工具栏齿轮按钮（页面 header 区，与「新增」按钮并排）+ el-dialog 面板：列清单（勾选=visible、**面板内拖拽排序**（sortablejs 同依赖）、脱敏列标注「已脱敏」tag、「恢复默认」按钮带 confirm）
- props：`columns`（合并后可选列清单：key/label/visible/masked）+ 当前配置；「确定」emit `save(payload)` 由页面调 `saveNow`，「取消/关闭」不落库；「恢复默认」emit `reset`
- 面板只列**权限允许的列**（合并函数已过滤，拍板 Q4）；锁列不出现

### D11 保存时机与失败回退（拍板 Q8）

- 拖拽列序/列宽变更 → `saveDebounced()`（800ms）；面板显隐 → 确定 `saveNow()`
- 保存失败：toast（拦截器统一）+ **回退内存态**（保存前快照 snapshot，catch 恢复列序/显隐/宽）——用户界面回到保存前，不出现「看起来保存了其实没存」
- PUT 整体覆盖幂等；多端/多标签并发 last write wins，无合并

### D12 双页接入差异（拍板 Q9=B）

| 维度 | user 页 | dict 页 |
|---|---|---|
| 前置 | **顺迁 ProTable**（裸 el-table/el-pagination → ProTable；不加勾选不加双击——保持现能力，row-key="id" 保留） | 已迁，零前置 |
| 列定义数组化 | 10 列 → defs 数组（操作列锁列模板直写） | 4 数据列 → defs 数组（操作列锁列） |
| 权限面 | `usePermColumns`（已有）isHidden/isMasked 传入 composable（mobile/email 动态列由合并函数统一接管，页面删散落 v-if） | 无（不传，权限过滤空过） |
| e2e | 配置场景 + 权限交互场景（面板无权限隐藏列） | 配置场景（顺序/显隐/列宽持久化） |

- 列定义数组元素：`{ key, label, minWidth?, width?, align?, slot? }`——`slot` 标记富内容列，模板 v-for 内按 `col.key` 用 v-if 分支写自定义格（账号内置徽标/状态 tag/mobile 的 `-` 降级）；简单文本列 `prop` 直出或 formatter。**不引入动态插槽抽象**（第二个复杂消费页出现再议，沿先薄后厚取向）
- 列宽语义：列定义默认 `minWidth`（弹性）；用户拖宽后转固定 `width` 存配置——已配置列渲染 `width`，未配置列渲染默认 `minWidth`

### D13 错误码与权限标识：双双零新增

- 错误码：校验失败全走 1002 + 文案（「表格标识格式无效」「列配置格式无效」两条新文案）；system 段现值仍至 **3042**，下轮 3043 接续（沿列权限轮零新增先例——前端无需按 code 分流，toast 文案足够）
- 权限标识：零新增——端点免 @PreAuthorize，sys_menu 零新增（导航断言零维护、perms 快照零变化）

## 5. 数据流

### 5.1 读路径（user 页，权限+配置双清单合并）

```
页面 onMounted（三路并行，互不阻塞）：
  pageUser(分页)                        → 行数据（值层列权限由后端 advice 已应用：隐藏 null/脱敏 ***）
  usePermColumns('user').load()         → my-scope columns → hidden/masked 两个 Set（既有链，零改）
  useTableConfig(TABLE_KEY_USER).load() → GET /system/table-config/system:user → data（null 或 {columns}）
      ↓ 三源就绪（columns=computed 自动重算）
  mergeColumns(defs, isHidden, saved)   → 权限赢过滤 → 存序重排+visible → 新列按原序追加
      ↓ v-for 渲染 el-table-column（宽=已存 width ?? 默认 minWidth；visible=false 不渲染）
```

### 5.2 写路径（拖拽/列宽/面板）

```
拖拽列序 onEnd → DOM 还原 → applyOrder(keys) → columns 重排 → saveDebounced() ─┐
列宽 header-dragend → applyWidth(key,w) → saveDebounced()                    ─┤(800ms 合流)
面板确定 → saveNow()                                                          ─┘
  → PUT /system/table-config/{tableKey}  body {columns:[{key,visible,width}]}
      （锁列已过滤；失败 catch → toast + 快照回退内存态）
  → TableConfigService：userId=登录态 → 校验（D4）→ uk 判存 update / insert（撞 uk 转 update，D6）
```

### 5.3 恢复默认

```
面板「恢复默认」confirm → DELETE /system/table-config/{tableKey}（物理删行，不存在也成功）
  → composable 清内存配置 → columns 回 defs 默认序
```

## 6. 错误处理

| 层 | 语义 | 失败形态 |
|---|---|---|
| tableKey 校验 | 字符集/长度（D3） | 1002「表格标识格式无效」 |
| config 校验 | 结构/逐项/上限（D4 盲存校验链） | 1002「列配置格式无效」（文案含具体原因段） |
| GET 无行 | 首次访问未配置 | `data=null`，非错误（前端按默认列序渲染） |
| PUT 并发撞 uk | 双端同时首次保存 | catch DuplicateKeyException 转 update（D6，不报错） |
| DELETE 不存在 | 重复恢复默认 | 成功 R<Void>（幂等） |
| 前端 load 失败 | 拉取配置失败 | 降级页面默认列序（个人偏好 fail-open 无害，toast 拦截器统一） |
| 前端 save 失败 | 网络/校验拒 | toast + 快照回退内存态（D11） |
| 登录态缺失 | 未登录调端点 | 网关 401 前置拦截（既有链，非本域新增路径） |

## 7. 测试策略

### 7.1 后端单测（cloud-system）

- **TableConfigServiceTest**：盲存校验链逐项（tableKey 字符集/长度、JSON 结构、key 字符集、visible 类型、width 范围、列数上限、总长上限——各一条红例）；getMine 无行返回 null / 有行返回反序列化结构；saveMine upsert（无行 insert、有行 update、并发撞 uk 转 update——mock 或真实库）；deleteMine 物理删 + 不存在行幂等成功；userId 恒取登录态（入参无 userId 字段——编译期即防）
- **TableConfigControllerTest**（如有 web 切片惯例则随工程）：三端点两行式返回、免 @PreAuthorize（匿名 401 由网关层，服务层仅登录态）
- ArchitectureGuardTest 既有规则自然覆盖（javadoc/DTO 入参/两行式/无 Map 接参），零新守护规则

### 7.2 冒烟（真链路，起 system + 网关）

pymysql 建表 → curl GET（无行 data=null）→ PUT 存一份配置 → GET 回读一致 → 再 PUT 覆盖 → GET 验证覆盖 → DELETE → GET 回 null → DELETE 再删仍成功 → 校验红例（非法 tableKey / 超长 config → 1002）→ 用另一账号 token 验证隔离（读不到对方配置、写不进对方行——userId 取登录态）。

### 7.3 e2e（新脚本 + 回归，见计划 §3 前端章 T5）

新脚本 `run-tableconfig-e2e.mjs`（e2etc 前缀造数）场景清单：user 页拖拽重排→reload 顺序持久；隐藏 mobile→reload 列头消失；列宽拖→reload 宽度持久；配 email 隐藏列规则（数据权限造数口径沿 usercolumn 脚本）→面板无 email 选项+列不渲染+存量配置不清洗（配置里含 email 仍无害）；恢复默认→reload 回默认序；dict 页顺序+显隐+列宽持久化核心场景。回归：e2e:dict（ProTable expose 增量+列数组化不破既有断言）、e2e:user（顺迁+列改写不破既有流）、e2e:dataperm-usercolumn（动态列链路）、dataperm 家族其余、e2e:nav、全量聚合 `e2e`。判定只认 `grep -c '[FAIL]'` == 0。

## 8. 索引审查（规范强制章节）

本轮唯一 DDL 为新表 `sys_table_config`（§3.1）——`uk_user_table (user_id, table_key)` 覆盖三端点全部查询路径（等值定位 + upsert 判存查重）；零二级索引（无跨用户/按时间/管理面查询路径，取舍记档 §3.1）。既有表零 ALTER、零索引增量。

## 9. 红线核对清单（实现与验收共同遵守）

1. 数据权限域零文件 diff（求值器/列上下文/advice/MCP 通道/my-scope 出参——本轮只读消费既有 wire）
2. `usePermColumns.ts` 零 diff（组合经函数引用传参，D8）
3. ProTable 仅增 expose `getTable()`（D9）；props/emits/模板结构/三能力行为零改；`e2e:dict` 既有断言零维护通过
4. 前端列显隐单一落点：合并函数（D7）——user 页改造后禁残留列级 v-if 散写
5. 端点免 @PreAuthorize 且 userId 不收前端传参（D5）；sys_menu 零新增
6. 错误码零新增（1002 两条新文案）；system 段账本仍至 3042
7. 前端按需引入红线（el-* 模板自动解析；sortablejs 是普通 JS 库非 EP 组件，无按需问题）；TS strict 禁 any/裸 as（composable/组件内；页面沿用集中收窄函数断言）
8. config 盲存口径（D4）：后端不校验列含义、不维护 tableKey 注册表
9. DDL 每列 COMMENT、mapper XML `<if>` 换行、Service catch 先 log.error、方法命名动词集、VO/DTO 隔离、原生 setter 转换——既有编码规范全适用
10. 实现顺序：POC（前端 T1）通过后才进双页改造；后端章与前端章可并行（联调点在前端 T5 e2e）

## 10. 错误码账本

本轮**零新增**（复用 1002 + 两条新文案）；system 段现值仍至 **3042**，下轮 3043 接续。bpmn 段仍至 4018。

## 11. 已知取舍与移交备忘（下一阶段规划前必读）

1. **离职用户配置行无清理**：无管理面、无清理任务——量级=用户数×表格数（小），出现诉求（如 GDPR 删号联动）再做，届时物理删 by user_id 即可
2. **多端并发 last write wins**：无版本号无合并——个人偏好冲突无损场景，出现多端同时编辑诉求再评估乐观锁
3. **面板内排序与表头拖拽是两套交互**：都改同一份配置（D10/D9），行为一致；如未来要求面板内实时预览表格，再议联动
4. **其余列表页接入**：role/menu/mqDlq/approval 等未迁 ProTable 页面，逐页迁移时接入（能力就位后接入成本=列定义数组化+两行 composable 调用）；leave 页 usePermColumns 推广仍按原移交口径一页一议
5. **sortablejs 与 EP 版本耦合**：DOM 查询封装在 composable 单文件（D9），EP 大版本升级时跑 e2e 拖拽场景即可回归
6. **列定义 `slot` 富内容分支是页面级模板 v-if**：不引入动态插槽/render 抽象——第二个富列密集页面出现再抽象（先薄后厚）
7. **错误码账本**：system 段现用至 3042；下轮 3043 接续并在契约再声明
