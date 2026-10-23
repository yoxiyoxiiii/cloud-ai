# 用户自定义表格配置实施计划（2026-10-23，全栈 A 级）

> 结论先行：用户表格列配置（顺序/显隐/列宽，每用户每表格服务端持久化）。设计依据 `docs/superpowers/specs/2026-10-23-table-config-design.md`（下称「设计」，决策 D1-D13 在其 §4）；契约 `docs/superpowers/contracts/2026-10-23-table-config-api.md`（前后端唯一对齐物）。**后端章与前端章独立可并行**（联调点在前端章 T5 e2e）；前端章 T1 POC 是双页改造的强制前置。与容器化轮排队关系见 §0。

## 0. 前置条件与全局纪律

**前置条件**：
1. 列权限轮（commit d5e4267）与 ProTable 轮（a2f4e7e）均已提交——已满足
2. **与容器化轮串行**：容器化轮（`2026-10-22-containerization.md`）实施期间不得与其它重启服务的轮次并行——本轮需重启 system（新表+新端点），两轮排队先后由主控协调，不并行开工

**全局纪律**：
- mvn 绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；uv/python 绝对路径（MySQL 读写一律 pymysql 优先，charset utf8mb4）
- Git Bash 中文 JSON GBK 坑：冒烟入参用 ASCII
- e2e 判读只认 `grep -c '[FAIL]'` == 0（exit 0 是假绿）
- 零 git 提交（主控统一提交）；5173 在跑（用户 dev server）则复用且绝不 kill
- 红线：数据权限域零文件 diff、usePermColumns 零 diff、错误码零新增、sys_menu 零新增（设计 §9 全清单）

## 1. 后端章（cloud-system，可与前端章并行）

### T1 DDL + 实体 + mapper

**文件清单**：
- 新增 `cloud-base/scripts/sql/2026-10-23-table-config.sql`（设计 §3.1 全文）
- 改 `cloud-base/scripts/sql/cloud_system.sql`（基线同步追加同表）
- 新增 `cloud-base/cloud-system/src/main/java/com/cloudai/system/entity/SysTableConfig.java`（不继承 BaseEntity——配置态物理删无逻辑删列，沿 sys_data_perm_rule 先例；字段 id/userId/tableKey/config/createTime/updateTime，类内嵌套注释写明取舍）
- 新增 `cloud-base/cloud-system/src/main/resources/mapper/SysTableConfigMapper.xml` + 接口 `mapper/SysTableConfigMapper.java`

**要点**：
- mapper 方法（命名动词集）：`findConfig(@Param userId, @Param tableKey)`（uk 等值定位，LIMIT 1 防御）/ `insertConfig(SysTableConfig)` / `updateConfig(SysTableConfig)`（按 uk 定位 set config+update_time）/ `deleteConfig(@Param userId, @Param tableKey)`
- SQL 全 `#{}` 占位；审计时间一律 SQL `NOW()`（insert 时 create=update 同值）；无 deleted 列无 `deleted=0` 条件
- 实体 config 为 String（JSON 串盲存），序列化/反序列化在 Service 层做

**验收**：`$MVN -f cloud-base/pom.xml clean install -pl cloud-system -am` 全绿；pymysql 建表成功且 `SHOW CREATE TABLE` 与设计 §3.1 一致（uk_user_table 在位）。

### T2 Service + Controller + 单测

**文件清单**：
- 新增 `dto/TableConfigSaveRequest.java`（columns: List<TableColumnConfigDto>，内嵌 TableColumnConfigDto{key,visible,width}）
- 新增 `vo/TableConfigVo.java`（columns: List<TableColumnConfigItemVo>）
- 新增 `service/TableConfigService.java`：`findMyConfig(tableKey)`（无行返回 null）/ `saveMyConfig(tableKey, req)`（校验链→JSON 序列化→upsert，撞 DuplicateKeyException 转 update，D6）/ `deleteMyConfig(tableKey)`（物理删，不存在幂等成功）
- 新增 `controller/TableConfigController.java`：`@RequestMapping("/table-config")` 三端点（GET/PUT/DELETE `/{tableKey}`，`@PathVariable("tableKey") String tableKey` 显式命名），**免 @PreAuthorize**（javadoc 注明 my-scope 先例口径），两行式返回
- 新增 `test/.../TableConfigServiceTest.java`

**要点**：
- userId 取 `SecurityUtils.currentUser().getUserId()`；无登录态理论不可达（网关前置），防御性判 null 抛 BusinessException(1002) 即可（javadoc 注明）
- 校验链（Service 手工抛 1002，D4 全项）：tableKey 正则 `^[A-Za-z0-9:-]{1,64}$`；Jackson 解析与结构校验（解析失败/字段类型不符→1002「列配置格式无效」）；key 正则 `^[A-Za-z0-9_]{1,32}$`、visible boolean、width 40-1000、列数 ≤50、`ObjectMapper.writeValueAsString` 总长 ≤4000
- upsert：先 findConfig 判存→insert/update；insert 撞 uk catch DuplicateKeyException 后重读行转 update（log.error 记根因再收敛，规范第 4 条）
- VO 转换原生 setter 逐字段；catch 异常必须 log.error 后转业务异常

**验收**：`clean install -pl cloud-system -am` 全绿（含新单测）；单测覆盖设计 §7.1 全部用例（校验链逐项红例/upsert 三态/物理删幂等/无行 null）。

### T3 契约冒烟（真链路）

**内容**（起 system 9202 + 网关 18080，java -jar 绝对路径）：
1. `curl http://localhost:18080/system/table-config/system:user`（带合法 token）→ data=null
2. PUT 存配置（ASCII body）→ GET 回读一致（顺序/visible/width）
3. 再 PUT 覆盖（改序+改 visible）→ GET 验证覆盖生效
4. DELETE → GET 回 null；DELETE 再删仍 200
5. 红例：tableKey `bad key!` → 1002；超长 config（>50 列）→ 1002
6. 隔离验证：另一账号 token GET/PUT——只能见/写自己的行（pymysql 查表确认行数）
7. 冒烟完 kill 所起服务

**验收**：以上逐项通过并记档（含请求/响应摘录）；MySQL 读写走 pymysql。

## 2. 前端章（cloud-web + cloud-e2e，可与后端章并行；T5 起需后端 T2/T3 就绪）

### T1 sortablejs POC（强制前置，通过才进 T3/T4）

**文件清单**：临时 POC 文件（如 `cloud-web/src/components/__poc/SortablePoc.vue` + 临时路由挂载或 dev 页直改 dict 页分支——形态实现者自定，**验证完必须删除**，不留工程）

**内容**：最小验证五点（设计 D9）——① ProTable expose `getTable()` 拿 el-table 实例（本任务顺带做 ProTable 唯一增量：`ProTableExposed` 增 `getTable(): TableInstance | undefined`，defineExpose 透传 tableRef，文件其余零 diff）；② sortablejs 挂 `.el-table__header thead tr` 成功；③ onEnd **先还原 DOM 再改数据源**，v-for keyed 重排无闪烁无错位；④ filter 排除锁列 th（选择列/操作列）禁拖；⑤ `@header-dragend` 透传捕获列宽（签名/参数以 EP 2.14 实测为准，实现时验证）。

**验收**：五点手测通过（截图 ≥3 张：拖拽中/拖后列序/列宽变化）；POC 文件删除后 `npm run build` 连续两次全绿；sortablejs 安装记录（版本号+`vite build` 产物体积增量记档）。**任一点不过→回报主控，不硬闯双页改造**（fallback 候选：配置面板上下移按钮零依赖方案，主控决策）。

### T2 useTableConfig composable + TableConfigButton 组件

**文件清单**：
- 新增 `cloud-web/src/api/tableConfig.ts`（契约 §6 三函数）
- 改 `cloud-web/src/types/api.ts`（TableConfigVo/TableColumnConfig/TableConfigSavePayload 类型 + TABLE_KEY_USER/TABLE_KEY_DICT 常量，新域分段注释）
- 新增 `cloud-web/src/composables/useTableConfig.ts`（设计 D7/D8：mergeColumns 合并函数单一落点——权限赢过滤→存序重排+visible→新列原序追加；columns computed；load/saveNow/saveDebounced(800ms)/reset/applyOrder/applyWidth；快照回退 D11；sortable 挂载/还原/filter 封装此文件）
- 新增 `cloud-web/src/components/TableConfigButton.vue`（设计 D10：齿轮按钮+el-dialog 面板——勾选显隐/面板内拖拽排序/「已脱敏」标注/恢复默认 confirm；确定 emit save、取消不落库；unplugin 自动注册）

**要点**：TS strict 禁 any/裸 as（EP 类型边界沿用集中断言）；`isColumnHidden/isColumnMasked` 以函数引用入参组合 usePermColumns（零改既有 composable）；load 失败降级默认列序不抛；el-* 按需引入红线照旧。

**验收**：`npm run build` 两次全绿；build 产物体积记录（sortablejs 增量）；组件在临时消费位（可先挂 dict 页 dev 分支）手测保存/回读链路通（后端未就绪时 mock 响应亦可——正式验收在 T5）。

### T3 user 页顺迁 ProTable + 接入配置

**文件清单**：
- 改 `cloud-web/src/views/system/user/index.vue`

**要点**：
1. **顺迁 ProTable**（设计 D12）：el-table/el-pagination 整段换 ProTable；不加 selectable/双击（保持现能力）；`row-key="id"` 保留（E3 渲染竞态先例）；v-loading→loading prop；分页 v-model 双向 + `@page-change`（size 变更重置 pageNum 由组件内置，删 handleSizeChange）
2. **列定义数组化**：10 列 → defs 数组（`{key,label,minWidth,slot?}`；操作列锁列模板直写 `fixed="right"` 不进 defs）；模板 v-for 渲染 el-table-column，富内容列（账号徽标/状态 tag/mobile·email `-` 降级）v-for 内按 key v-if 分支
3. **列显隐收敛合并函数**：删 mobile/email 现有 v-if 散写（`usePermColumns` 保留，isHidden/isMasked 传 composable）；页面不再有任何列级 v-if（红线，设计 §9 第 4 条）
4. 工具栏 header 区加 `TableConfigButton`（与「新增用户」并排）
5. keep-alive 组件名 `SystemUser` 不动；rowOf 收窄函数保持

**验收**：build 两次全绿；dev 手测：默认列序=原序、拖拽列序/列宽/面板显隐均生效且 reload 持久、恢复默认回原样；admin 配 email 隐藏列规则后该列消失且面板无该项（清规则复原）。

### T4 dict 页接入配置

**文件清单**：
- 改 `cloud-web/src/views/system/dict/index.vue`

**要点**：4 数据列 → defs 数组（字典名称富列/字典键/状态 tag；操作列锁列）；无权限面（isColumnHidden 不传）；E3 注释随迁；勾选/双击/分页三能力行为零改（回归锚点）；TableConfigButton 进 header 区。

**验收**：build 两次全绿；dev 手测配置持久化与恢复默认；勾选/双击弹框/翻页/sizes 切换行为与改造前一致。

### T5 e2e 新脚本

**文件清单**：
- 新增 `cloud-e2e/run-tableconfig-e2e.mjs`；改 `cloud-e2e/package.json`（scripts 增 `e2e:tableconfig`；不进 `e2e` 聚合——沿 dataperm 家族惯例，聚合扩容另议）

**场景清单**（e2etc 前缀造数，黑盒纪律禁 import 前端内部代码）：
1. **user 顺序持久**：登录 admin → 表头拖拽（playwright dragTo th）改列序 → reload → 断言列头顺序持久
2. **user 显隐持久**：面板隐藏「手机号」→ 确定 → reload → 断言该列头不存在；恢复显示断言回归
3. **user 列宽持久**：拖列宽（header-dragend 或 resize 手柄）→ reload → 断言 th 宽度持久（断言方式：渲染后 th 宽度值/style，实现时以 EP 实测 DOM 定）
4. **权限交互（核心）**：数据权限造数（沿 e2e:dataperm-usercolumn 口径：角色挂用户管理菜单闭包 + email 隐藏列规则 + 测试用户）→ 测试用户登录 user 页 → 断言 email 列不渲染**且配置面板无 email 选项**；该用户配置 JSON 已含 email（预置）→ reload 无报错且 email 仍不渲染（存量配置不清洗）；清扫规则与造数
5. **user 恢复默认**：改乱配置 → 面板恢复默认 → reload 断言默认列序
6. **dict 三合一**：顺序+显隐+列宽持久化核心场景（无权限面）
7. **清扫**：e2etc 残留零断言（用户/配置行/规则全清，报告残留）
- 锁列断言：操作列恒在末尾且不可拖（拖拽尝试后顺序不变——可作可选断言）

**验收**：`npm run e2e:tableconfig` 有头跑 `[FAIL]` 计数 0；既有断言风格一致（login/造数 harness 复用既有脚本模式）。

### T6 回归 + 视觉核对 + 收口

**内容**：
1. 前置栈：system(9202)→sso(9201)→bpmn(9203)→gateway(18080)（agent 自管起停）；5173 先探活，用户的绝不动
2. 回归清单（判定只认 `grep -c '[FAIL]'` == 0）：`e2e:tableconfig`（新）+ `e2e:dict` + `e2e:user` + `e2e:dataperm-usercolumn` + dataperm 家族其余三脚本 + `e2e:nav` + 全量聚合 `e2e`
3. 视觉核对：截图 ≥6 张（user 默认态/拖拽后面板/dict 配置态/权限用户视角缺列态/列宽变化/恢复默认）→ `cloud-e2e/artifacts/` → Read 得上传 URL → analyze_image 核对，核对完即删
4. kill 本轮所起服务；汇报文件清单/build 结果/各脚本 PASS 计数/体积增量/起停记录

## 3. 任务依赖图

```
后端 T1 → T2 → T3（独立线，可与前端全线并行）
前端 T1(POC) → T2 → T3 ∥ T4 → T5(需后端 T2/T3 就绪) → T6
（前端 T2/T3/T4 开发期可连后端未起的服务做 mock；T5 起真联调）
```

## 4. 给涉及端 agent 的任务清单（主控整段贴发用）

### 4.1 给 backend-agent 的任务清单

> 角色：按设计 `docs/superpowers/specs/2026-10-23-table-config-design.md`（重点 §3 数据设计、§4 决策 D2-D6/D13）与契约 `docs/superpowers/contracts/2026-10-23-table-config-api.md`，在 cloud-system 实现表格配置三端点。执行计划后端章 T1→T2→T3。
>
> **纪律**：数据权限域零文件 diff；错误码零新增（1002+文案）；sys_menu 零新增；端点免 @PreAuthorize 且 userId 取 `SecurityUtils.currentUser()` 不收前端传参；config 盲存（只校验格式与上限，不校验列含义、不建 tableKey 注册表）；mapper 手写 SQL 全 `#{}`、审计时间 SQL `NOW()`、无 deleted 列（配置态物理删先例）；Service catch 先 log.error；方法命名动词集（findMyConfig/saveMyConfig/deleteMyConfig）；DDL 每列 COMMENT；mvn 绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；零 git 提交；与容器化轮不并行开工（主控排期）。
>
> **T1**：建表脚本（设计 §3.1 逐字）+ 基线 cloud_system.sql 同步 + 实体（不继承 BaseEntity，取舍注释进类头）+ mapper XML 四方法（findConfig/insertConfig/updateConfig/deleteConfig，uk 等值定位）；验收=构建绿 + pymysql 建表成功且 uk 在位。
> **T2**：DTO/VO/Service（校验链 D4 全项→JSON 串→upsert，DuplicateKeyException 兜底转 update）/Controller 三端点两行式（javadoc 注明免权限口径）；单测按设计 §7.1 全用例；验收=`clean install -pl cloud-system -am` 全绿。
> **T3**：起 system+网关按计划 §1 T3 七步冒烟（ASCII body、pymysql 查库、双账号隔离验证），完事 kill；验收=逐项记档回报。
> **完成回报**：文件清单/构建与单测结果/冒烟七步结论/uk 与索引核对/遇到的设计偏差（有则回报不自行改契约）。

### 4.2 给 frontend-agent 的任务清单

> 角色：按设计 `docs/superpowers/specs/2026-10-23-table-config-design.md`（重点 §4 决策 D7-D12）与契约 `docs/superpowers/contracts/2026-10-23-table-config-api.md`（§6 前端类型增量、§2 端点形态），实现前端表格配置能力与双页接入。执行计划前端章 T1→T2→T3∥T4→T5→T6。
>
> **纪律**：T1 POC 是双页改造强制前置，任一验证点不过即回报主控待命（fallback=面板上下移零依赖方案，主控决策），POC 文件验证完删除；数据权限域零文件 diff、`usePermColumns.ts` 零 diff（函数引用组合）；ProTable 仅增 expose `getTable()`，其余零 diff；页面列显隐只准走合并函数（禁列级 v-if 散写）；按需引入红线（禁 app.use(ElementPlus)/全量样式）；TS strict 禁 any/裸 as（集中断言函数模式）；零 git 提交；5173 用户的 dev server 绝不 kill；e2e 判读只认 `[FAIL]` 计数。
>
> **T1 POC**：装 sortablejs+@types（版本与体积记档）；ProTable expose 增量；五验证点（挂载/onEnd 先还原 DOM 再改数据/v-for 协同/filter 锁列/header-dragend 透传，EP 签名实测为准）；验收=五点手测过+截图≥3+POC 删除后 build 两次绿。
> **T2**：api/tableConfig.ts 三函数 + types 增量（类型+TABLE_KEY 两常量）+ useTableConfig.ts（mergeColumns 单一落点/load/save 防抖 800ms/reset/快照回退/sortable 封装）+ TableConfigButton.vue（面板：勾选/拖拽排序/已脱敏标注/恢复默认 confirm）；验收=build 两次绿+体积增量记档。
> **T3 user 页**：顺迁 ProTable（不加勾选双击、row-key 保留、@page-change 接线）+ 10 列 defs 数组化（富列 v-if 分支）+ 删 mobile/email 散写 v-if（收敛合并函数）+ 工具栏加 TableConfigButton；验收=build 两次绿+dev 手测配置/持久化/恢复默认+email 隐藏规则下列消失且面板无该项。
> **T4 dict 页**：4 数据列 defs 数组化（无权限面）+ TableConfigButton；勾选/双击/分页三能力行为零改；验收=build 两次绿+行为与改前一致。
> **T5 e2e**：run-tableconfig-e2e.mjs 七场景（计划 §2 T5 清单，e2etc 前缀，权限场景造数沿 e2e:dataperm-usercolumn 口径）+ package.json 挂 e2e:tableconfig（不进聚合）；验收=有头跑 `[FAIL]`==0。
> **T6**：起前置栈（5173 先探活）→ 回归清单全跑（tableconfig/dict/user/dataperm-usercolumn/dataperm 家族/nav/全量聚合 e2e）→ 视觉核对 ≥6 张（核对完删）→ kill 自起服务。
> **完成回报**：文件清单/两次 build 结果/POC 五点结论与 sortablejs 版本体积/各脚本 PASS 计数与 grep 判定/视觉核对结论/起停服务记录。
