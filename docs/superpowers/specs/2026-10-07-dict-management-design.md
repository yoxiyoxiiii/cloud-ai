# 数据字典管理技术方案（全栈 A 级：两表 DDL + cloud-system CRUD + cloud-web 主从管理页 + e2e）

- 日期：2026-10-07
- 状态：已定稿（主控已与用户完成需求定性，本文按定性结论设计）
- 需求原文：「实现系统的字段管理功能」；定性：**数据字典管理**——字典类型（如"用户状态"）+ 字典项两级 CRUD，为后续表单下拉统一取数打底
- 范围：**纯管理闭环**（两表 DDL + 权限种子 + 后端 CRUD + 前端管理页 + e2e D 系列）；**不含**既有表单/筛选接入字典取数（下轮，见 §11 移交备忘）
- 契约：`docs/superpowers/contracts/2026-10-07-dict-api.md`（字典域现行版，前后端唯一对齐物）
- 计划：`docs/superpowers/plans/2026-10-07-dict-management.md`

## 1. 需求与范围

| 项 | 结论 |
|---|---|
| 本轮做 | sys_dict_type / sys_dict_data 两表；sys_menu 权限种子（C 14 字典管理 + F 141-143）；cloud-system 8 端点 CRUD；cloud-web `/system/dict` 主从式管理页；e2e `run-dict-e2e.mjs`（D 系列）+ 既有断言维护 |
| 本轮不做 | 表单/筛选下拉接字典取数（消费端点 `GET /dict/data/type/{dictKey}` 留下轮 additive）；字典类型/项的批量删除、搜索、remark 备注、缓存（Redis）；type/data 分权（统一 perms，见 D3） |
| 改动面 | cloud-system（9202）重启；**网关 / sso / common 零改动零重启**；cloud-web 无路由表/侧边栏手改（动态路由自动生效） |

路线图对位：`2026-10-04-cloud-base-backend-design.md` §6/§9 早已规划 `sys_dict_type / sys_dict_data`，2026-10-05 裁剪留待"后续按需补"——本轮即该补齐，命名沿用路线图。

## 2. 现状盘点（设计前提，2026-10-07 逐项核实）

- 后端范式（cloud-system）：Controller 两行式返回 + @PreAuthorize / Service 校验前置 + 审计显式传参 / 手写 XML（deleted=0 显式 + `<if>` 换行 + 无 LIMIT）/ VO 出参 + convert 原生 setter。**user 域已 DTO 接参（UserSaveRequest），role/menu 实体接参是已记档债务**——新代码必须 DTO（CLAUDE.md 通用约束 3）
- 错误码 3xxx 已用至 3007（3001 用户不存在 / 3002 账号已存在 / 3003 角色键已存在 / 3004 角色不存在 / 3005 存在子菜单 / 3006 菜单不存在 / 3007 父菜单非法）；**3008+ 在三份契约中被声明预留给「内置角色/菜单保护」另案，该另案未开工未占用**
- 前端：动态路由（sys_menu M/C 驱动，viewRegistry 白名单注册组件）；v-perms 指令 + stores/perm.ts 快照；三管理页范式（分页表格 + FormDialog）；icon 白名单 ICON_MAP 16 枚（含 Files）
- e2e：五脚本全量链（user→role→menu→scaffold→nav）；**4 处硬编码断言与菜单种子数量耦合**（run-menu-e2e.mjs:147、run-nav-e2e.mjs:193/198/220/423），种子加菜单必须同步维护（见 D9）
- 种子/基线：`cloud_system.sql`（基线，DROP 重建）+ `2026-10-07-menu-nav.sql`（增量范本：头部红线 + 幂等提示 + 基线同步声明）；基线 role_menu 绑定是 `SELECT 1, id FROM sys_menu` 全量式，增量须显式补绑 4 行

## 3. 数据设计

### 3.1 DDL（增量 `2026-10-07-dict-mgmt.sql` 与基线 `cloud_system.sql` 同步落同一份定义）

```sql
CREATE TABLE sys_dict_type (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '字典类型ID',
    dict_name   VARCHAR(30) NOT NULL COMMENT '字典名称，如：用户状态',
    dict_key    VARCHAR(50) NOT NULL COMMENT '字典键，全库唯一，如：user_status',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_key (dict_key)
) ENGINE = InnoDB COMMENT = '字典类型表';

CREATE TABLE sys_dict_data (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '字典项ID',
    dict_type_id BIGINT      NOT NULL COMMENT '所属字典类型ID（sys_dict_type.id，引用完整性由服务层维护，无外键）',
    label        VARCHAR(50) NOT NULL COMMENT '展示标签，如：启用',
    value        VARCHAR(50) NOT NULL COMMENT '存库值，同类型内唯一，如：0',
    sort         INT         NOT NULL DEFAULT 0 COMMENT '排序（同类型内升序）',
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by    VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by    VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_value (dict_type_id, value)
) ENGINE = InnoDB COMMENT = '字典项表';
```

要点：每列 COMMENT ✓ / deleted NOT NULL DEFAULT 0 ✓ / 审计四列 ✓ / 驼峰映射 ✓ / 无外键（微服务惯例，引用完整性服务层维护）✓。`value` 为 MySQL 非保留关键字（5.7/8.0 均可裸用作列名），B1 建表即验证。`uk_type_value` 最左前缀即 dict_type_id，兼作项列表查询索引，**不另建 idx**。增量脚本用 `CREATE TABLE IF NOT EXISTS`（建表幂等），基线随 DROP 重建流程不带 IF NOT EXISTS。

### 3.2 菜单种子（增量 + 基线同步）

```sql
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, create_time) VALUES
(14, 10, '字典管理', 'system:dict:list',   'C', '/system/dict', 'Files', 4, NOW()),
(141, 14, '字典新增', 'system:dict:add',    'F', '', '', 1, NOW()),
(142, 14, '字典修改', 'system:dict:edit',   'F', '', '', 2, NOW()),
(143, 14, '字典删除', 'system:dict:remove', 'F', '', '', 3, NOW());

-- 仅增量脚本需要（基线的 SELECT 全量绑定天然覆盖）
INSERT INTO sys_role_menu (role_id, menu_id, create_time) VALUES
(1, 14, NOW()), (1, 141, NOW()), (1, 142, NOW()), (1, 143, NOW());
```

- id 14/141-143：沿用基线种子分段（11x 用户 / 12x 角色 / 13x 菜单 / 14x 字典）；**执行前先核对该 4 id 未被占用**（`SELECT id FROM sys_menu WHERE id IN (14,141,142,143)` 应 0 行——增量 INSERT 不幂等，脚本头注明）
- **不种任何字典业务数据**（sys_dict_type/data 初始 0 行）：管理页首开为空态即验收态，避免"不可删的种子字典"与 e2e 删净纪律耦合；需要示例数据的验收自己造（见 D7）
- **admin 快照时效**（menu-nav §3 两维分离的自然推论，写入契约 §1）：种子落库后——导航**实时**：admin 刷新页面即见"字典管理"（user-nav 实时查库）；操作**快照**：在线会话 perms 不含 system:dict:*，列表请求得 body 403——**须重新登录**（或 refresh）。e2e 每脚本新登录天然满足；手工验收注意重登

## 4. 设计决策（D 系列）

### D1 类型-项关联：`dict_type_id` 数值列（非 dict_key 字符串冗余）

- 备选 a（选定）：BIGINT id 关联。dictKey 可改（PUT 部分更新允许改键，roleKey 同款），id 关联改名不破坏归属；未来按 dictKey 取数走 `JOIN sys_dict_type`（符合"聚合优先 JOIN 一次成型"）
- 备选 b（弃）：RuoYi 式 `dict_type` 字符串列冗余。省一次 JOIN，但类型改键时项数据全部失联（无外键无级联），数据完整性靠约定不靠结构——弃
- 代价接受：项分页必带 typeId（管理页天然满足，selectedType.id 即是）

### D2 页面形态：**主从式单页**（左类型列表 + 右字典项表格）

- 备选 a（选定）：`/system/dict` 一页两栏。类型与项同屏上下文，选中即联动；一个 C 菜单；左右两表各自分页，与既有分页范式同构
- 备选 b（弃）：类型页 + 项管理弹窗。项表格塞弹窗内再叠 FormDialog 成两层嵌套弹窗，分页/排序体验差——弃
- 备选 c（弃）：RuoYi 式两个 C 菜单（字典管理 / 字典数据独立页）。多一个菜单节点与 perms 面；类型→项的跳转靠路由参数，来回横跳——弃
- 布局：左栏定宽（约 380px）el-card；右栏 flex-1 el-card；右侧未选类型时 el-empty"请在左侧选择字典类型"且"新增字典项"禁用

### D3 权限标识：**统一 `system:dict:{list,add,edit,remove}`**（4 个，C 节点携 list + 3 个 F）

- 现状事实：三管理页种子结构 = C 节点 perms 携 `:list`（页面级）+ F 子节点携 add/edit/remove（按钮级）；一个页面一个实体域
- 备选 a（选定）：dict 视为该页管理的实体聚合，统一 4 perms。种子结构与三页完全同构（C 14 携 list，F 141-143）；type 与 data 写操作共用动作权限——本轮两实体同页同角色群维护，无分权需求方
- 备选 b（弃）：`system:dict:type:*` + `system:dict:data:*` 8 perms。更细，但"页面级 list 挂哪"无解：C 节点只能携一个 list，data:list 需要隐形 F 节点承载——破坏种子结构惯例；分权是真实但未到的需求
- 演进路径：将来需要"只准维护项不准动类型"时，additive 新增 type/data 细粒度 F 节点与端点 @PreAuthorize 改 hasAnyAuthority——菜单种子可后加，走契约修订，不破坏本轮
- 8 端点 @PreAuthorize 映射：两处 page=`system:dict:list`；type 与 data 的 POST/PUT/DELETE 分别=`system:dict:add/edit/remove`

### D4 接口面：8 端点，DTO 接参，无 detail/无搜索

- 类型 4：`GET /dict/type/page`（分页，id 倒序）/ `POST /dict/type`（返新 id）/ `PUT /dict/type`（部分更新）/ `DELETE /dict/type/{id}`
- 项 4：`GET /dict/data/page?typeId=`（分页，sort 升序 id 升序）/ `POST /dict/data` / `PUT /dict/data` / `DELETE /dict/data/{id}`
- 不设 detail 端点：page/列表 VO 已含全部字段（role/menu 域同款现状，编辑回显用行数据）
- 不设搜索参数：role 页"无搜索参数（契约现状）"同款；字典量级靠分页与键命名约定治理（移交备忘）
- 接参 DTO（新代码不背实体接参债务）：`DictTypeSaveRequest{id?,dictName,dictKey,status}` / `DictDataSaveRequest{id?,typeId,label,value,sort,status}`；POST 忽略 id，超集字段 Jackson 反序列化忽略（不存在落库面）

### D5 删除级联：**禁删有项的类型**（3011），不做级联逻辑删

- 备选 a（选定）：删除类型前 count 该类型未删项，>0 → 3011"该字典类型下存在字典项，先删除字典项"。镜像菜单域 3005"存在子菜单，先删除子级"——in-repo 最强先例；单删除端点只动一行；误删保护（类型删除后其项成不可达孤儿）
- 备选 b（弃）：级联逻辑删（一事务删类型 + deleteByTypeId 全部项）。操作省事，但"删 1 行隐藏 N 行"违背最小惊异，且 uk 墓碑叠加（类型+全部项同时占键）放大 D8 的墓碑语义——弃
- 代价接受：清空多项类型须逐项删（无批量删项，移交备忘）；count-then-delete 理论 TOCTOU 窗口接受（与菜单 3005 同口径）

### D6 错误码：**字典域占用 3008-3012，预留声明重排至 3013+**

- 分配：3008 字典类型不存在（含已删）/ 3009 字典键已存在 / 3010 字典项不存在（含已删）/ 3011 该类型下存在字典项 / 3012 该类型下字典项值已存在
- **预留声明重排**（本设计裁定）：三份既有契约（menu-management v2 §5 末行、menu-nav §4、perms-api §2）声明"3008 起预留给内置角色/菜单保护另案"——该另案未开工未占用一码；空段预留破坏顺延分配的审计链（下一个实体每次都要跳段声明）。**裁定：字典从 3008 顺延占用至 3012，3013 起重排为保护另案预留**。原文不回改，以本轮契约 §5 声明性取代（契约修订唯一入口 = 架构-agent，本轮即为修订）

### D7 种子策略：菜单种子落增量+基线，字典数据零种子

- 见 §3.2。零字典种子的理由：字典是纯运营数据（无系统功能依赖它）；种子字典会制造"e2e 不可删数据"与删净断言耦合；空库首开即验证空态渲染
- icon 选 `Files`：ICON_MAP 白名单既有枚举（constants/icons.ts 已具名导入，零前端改）；语义贴合"条目集"

### D8 唯一性与墓碑：双唯一键 + 服务层预检 + DuplicateKey 兜底

- `uk_dict_key`（类型键全库唯一）+ `uk_type_value`（项值类型内唯一）——沿用 uk_account/uk_role_key 完整模式：预检（deleted=0 口径、编辑排除自身）+ `DuplicateKeyException` 兜底转业务码（防并发 TOCTOU 与墓碑占键）
- **墓碑语义（如实声明）**：逻辑删除的类型其 dict_key 永久占键——重建同键类型恒得 3009（role 域同款现状，契约宽松语义清单记档）；项墓碑占 (typeId,value) 同理。若未来成痛点，解法是物理清理任务或 DDL 改造，另案
- label（标签）**不做唯一**：重复标签无程序语义危害（下拉按 value 取数），唯一性约束只给 value（消费键）

### D9 e2e：新 D 系场景 + **既有 4 处硬编码断言维护**（本轮独有的集成成本）

- 新脚本 `run-dict-e2e.mjs`（D0-D4 + CLEANUP），全量链追加至末位；纪律全沿既有（e2e 前缀+时间戳、不碰种子/admin、黑盒、截图视觉核对、场景脚本与功能代码同 commit）
- **断言维护（必须，否则全量回归必红）**：种子 +4 菜单改变 admin 导航形状——
  - `run-menu-e2e.mjs:147` 侧边顺序串 → 加"字典管理"（菜单管理后、工作台前）
  - `run-nav-e2e.mjs:193` 系统管理子级数 3→4；`:198` C 子级名串 +字典管理；`:220` 与 `:423` 侧边精确串 +字典管理
  - 兼容性核实（不改，回归验证）：role R1（用户→角色相邻断言）、R5a（active==total 动态）、user/scaffold（includes 断言、菜单搜索过滤"角色"）均与新增项无冲突；nav N2 受限用户侧边恰"角色管理,工作台"不受影响（新菜单未绑该角色）

### D10 执行通路与卡点

- DDL/种子执行：本机 MySQL 无客户端——**java 单文件源码 + mysql-connector-j**（本地 `~/.m2` 仓库取 jar，版本实现时验证；临时文件置 scripts 外用后即删；与 dynamic-routing B1 同通路）
- 卡点：**cloud-system（9202）需重启**（新 Controller/Service 装配）；网关/sso 零改动不重启；**dev 5173 由前端 agent 自管**（2026-10-07 用户指示，不写"用户管理 5173"）
- 守门：ArchitectureGuardTest（DTO 接参/两行式/显式 @PathVariable/无 Wrapper/`<if>` 换行/枚举 Enum 后缀等）+ MapperXmlBindingTest（XML 语句与方法绑定）对新代码自动生效，构建全绿即合规

## 5. 页面结构设计（线框）

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 面包屑：首页 / 字典管理        （侧边：系统管理 › …用户/角色/菜单/字典管理）│
│ ┌────────────────────────┐  ┌──────────────────────────────────────────┐ │
│ │ 字典类型      [新增类型] │  │ 字典项：用户状态（user_status）[新增字典项]│ │
│ │ ┌────────────────────┐ │  │ ┌──────────────────────────────────────┐ │ │
│ │ │ 字典名称│字典键│状态│操 │ │  │ 标签│值│排序│状态│创建人/时│更新人/时│操作│ │ │
│ │ │ ●(选中行高亮)       │ │  │ │                                      │ │ │
│ │ └────────────────────┘ │  │ └──────────────────────────────────────┘ │ │
│ │ 共 N 条  ‹ 1 2 ›        │  │           共 N 条 ‹ 1 2 › [10/页 ▾]      │ │
│ └────────────────────────┘  └──────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────┘
```

- 左表 4 列（字典名称/字典键/状态 tag/操作 编辑+删除）；窄面板分页 layout `total, prev, pager, next`（省 sizes，pageSize 恒 10）；`highlight-current-row` + `@current-change` 联动右栏
- 右表 10 列（标签/值/排序/状态/创建人/创建时间/更新人/更新时间/操作）+ 完整分页（sizes [10,20,50]，同 role 页）
- 未选类型：右栏表头渲染但空数据 + el-empty 文案"请在左侧选择字典类型"，"新增字典项"disabled；选中后 header 显示 `字典项：{dictName}（{dictKey}）`
- 选中类型被删/被编辑：删除后清空选中与右栏；编辑后按 id 重新对齐选中行（dictName 变化随刷新呈现）
- 状态 tag/时间列格式化、rowOf 收窄、v-loading、删除确认文案——全部沿三页范式（§9 一致性对照）

## 6. 弹窗组件设计（对照 RoleFormDialog）

| 项 | DictTypeFormDialog | DictDataFormDialog |
|---|---|---|
| props | `{ modelValue, mode, dictType? }` | `{ modelValue, mode, dictData? }` |
| 字段 | dictName（必填 1-30）/ dictKey（必填，pattern `^[a-zA-Z][a-zA-Z0-9_]{0,49}$`，**可改**——roleKey 同款）/ status radio 0/1 默认 0 | label（必填 1-50）/ value（必填，pattern `^[A-Za-z0-9_.-]{1,50}$`）/ sort el-input-number 0-999 默认 0 / status radio |
| 隐含字段 | 无 | typeId = 页面当前选中类型（提交时注入，不渲染控件） |
| 提交 | add `createDictType` / edit `updateDictType`（全量三写字段+id） | add `createDictData`（+typeId）/ edit `updateDictData`（全量五写字段+id，typeId 亦提交） |
| 反馈 | 成功 toast + emit success + 关闭；catch 留空（3009 等由拦截器 toast，弹窗不关）；loading 防重 | 同左（3012 同款） |

dictKey/value 的 pattern 是**前端约定**（后端无格式校验，契约宽松语义）——与 menu perms pattern 同口径。

## 7. api 层与类型扩展

- `types/api.ts`：+`SysDictTypeVo` / `SysDictDataVo`（契约 §4 字段表逐字段）；复用既有 `PageResult<T>`
- `api/dict.ts`（新文件，8 函数）：`pageDictType` / `createDictType` / `updateDictType` / `deleteDictType` / `pageDictData` / `createDictData` / `updateDictData` / `deleteDictData`——每函数 JSDoc 注契约节号，入参出参 interface
- `router/viewRegistry.ts`：`VIEW_REGISTRY` 追加 `'/system/dict': DictManageView`；**不改 router/index.ts、不改 Sidebar.vue**（动态路由 + user-nav 渲染自动接管）；页面 `defineOptions({ name: 'SystemDict' })`（path 派生名，keep-alive 契约）

## 8. 数据流

```
页面挂载 ──GET /system/dict/type/page──────────────► 左表（id 倒序，10/页）
行选中 ──GET /system/dict/data/page?typeId=…──────► 右表（sort,id 升序）
新增类型 ──POST /dict/type──► R<Long> 新 id ──► 刷新左表（回第 1 页，新行置顶可见）
新增项 ──POST /dict/data（typeId=选中）──► 刷新右表
删除类型 ──DELETE /dict/type/{id}──3011(有项 toast)/200──► 刷新左表+清选中
网关：JWT 验签 → Redis 在线 → X-User-Perms（登录快照）→ @PreAuthorize system:dict:*
```

审计：写操作 Service 显式 `SecurityUtils.currentAccount()` + `LocalDateTime.now()`（插入四值，更新/删除两值）——手写 SQL 无自动填充。

## 9. 错误处理矩阵（前端策略：200/401 分流，其余拦截器统一 toast msg）

| 场景 | code | 前端表现 |
|---|---|---|
| 类型键重复（保存/编辑） | 3009 | toast "字典键已存在: xxx"，弹窗保留可改 |
| 项值重复 | 3012 | toast，弹窗保留 |
| 删有项类型 | 3011 | toast "…先删除字典项" |
| 目标不存在（并发删除/脏 id） | 3008/3010 | toast，表格刷新后自然对齐 |
| 必填缺失 | 1002 | 前端 rules 先拦（0 请求）；直连 API 才可见 |
| 无操作权限 | 403 | 按钮已按快照隐藏（v-perms）；403 为最终防线 |
| 未认证 | 401 | 拦截器清态跳登录（既有） |

## 10. 测试策略

### 10.1 后端单测（构建期，Mockito mock mapper，沿 SysRoleManageServiceTest 范式）

- SysDictTypeManageServiceTest：save 空名/空键→1002；save 键重复→3009；save DuplicateKey 兜底→3009；update 不存在→3008；update 空白串→1002；update 键重复（排除自身）→3009；delete 不存在→3008；delete 有项→3011（verify never deleteById）；delete 空类型→verify 调用
- SysDictDataManageServiceTest：save typeId 缺→1002 / 类型不存在→3008；save 空 label/value→1002；值重复→3012；page typeId null→1002 / 类型不存在→3008；update 不存在→3010；update 值重复→3012；delete 不存在→3010

### 10.2 后端 curl 验收（经网关，B6，ASCII 入参；临时数据 `e2ecurl` 前缀用后删净）

造类型→分页→造项→重复值 3012→删有项类型 3011→删项→删类型→分页断言无残留；空白键 1002；Long→String/时间格式断言。

### 10.3 黑盒 e2e（run-dict-e2e.mjs，D 系列，有头 + slowMo）

| 场景 | 断言要点 |
|---|---|
| D0 | admin 登录；侧边含"字典管理"且位于菜单管理之后；面包屑 首页/字典管理 |
| D1 | 左表 4 列表头 + 空态；右栏 el-empty"请在左侧选择字典类型"；"新增字典项"禁用 |
| D2 | 类型空提交必填错误 0 请求→新增 E2E 类型（选中态）→行高亮且右栏联动→编辑改名+停用→行更新 tag danger→同键再建→toast 含"字典键已存在" |
| D3 | 右栏空表→新增项（label/value/sort）→行出现→value 重复→toast（3012 文案）→编辑标签/排序→行更新→项空提交 0 请求 |
| D4 | 删有项类型→toast 含"先删除字典项"→行仍在→删全部项→删类型→左行消失且右栏回到空态；删除确认框文案 |
| CLEANUP | 清全部 E2E 前缀类型（先项后类型）；左表无 e2e 残留；无 console error / pageerror |

外加 E1 既有断言维护（D9 的 4 处）与全量六脚本回归；截图 ≥5 张走 analyze_image 视觉核对（结论文字记录，截图不进 git）。

## 11. 已知取舍与移交备忘（下一阶段规划前必读）

1. **消费端点未做**：`GET /system/dict/data/type/{dictKey}`（表单下拉取数，含停用类型/停用项过滤语义）是下轮 additive 需求——DDL/唯一键已为其备好（JOIN 即取）
2. **无批量删项**：清空大类型须逐项删（D5 代价）；批量删 + 项拖拽排序属体验增强另案
3. **无搜索/过滤**：类型页无 dict_name/dict_key 模糊搜（role 页同款契约现状）；字典量级大时补
4. **墓碑占键**（D8）：删除后同 dict_key 永不可重建（3009）；如成痛点另立物理清理任务
5. **无 remark/缓存**：字典备注列与 Redis 缓存（消费端点高频读）随消费轮一并议
6. **type/data 分权未做**（D3 备选 b 演进路径已留）
7. **项删除不校验所属类型存活**：直连 API 可编辑/删除已死类型的项（宽松语义，契约 §6 记档）
8. **dictKey/value 格式后端不校验**：pattern 为前端约定（同 menu perms 口径）；脏值入库属数据治理
9. **e2e 耦合提醒**：本轮后 admin 导航含 5 项（+工作台）；后续任何菜单种子扩张须再走 D9 式断言审查（menu-management 移交备忘 8 的延续）

## 给 backend-agent / frontend-agent 的任务清单

完整可粘发清单见 `docs/superpowers/plans/2026-10-07-dict-management.md`（后端章 B1-B6 / 前端章 F1-F6 / e2e 章 E1-E3）。要点：

- **backend**：B1 增量+基线 SQL + java 单文件执行回查；B2 实体×2 + Mapper×2（XML）；B3 DTO×2 + VO×2 + Convert×2；B4 Service×2 + Controller×2 + 单测×2；B5 全量构建（守护测试绿）；B6【卡点】请用户重启 9202 + curl 验收
- **frontend**：F1 类型字典；F2 api/dict.ts；F3 viewRegistry 注册；F4 主从页；F5 弹窗×2；F6 双次 build + 联调（5173 agent 自管）
- **e2e**：E1 既有 4 处断言维护；E2 run-dict-e2e.mjs + scripts 链；E3 全量回归 + 视觉核对
- 红线：契约定稿后两端不得单方改；既有端点零触碰（additive only）；Element Plus 按需；黑盒纪律
