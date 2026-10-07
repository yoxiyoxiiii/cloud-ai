# 数据字典管理实施计划（全栈：后端章 ∥ 前端章 + e2e 章）

- 日期：2026-10-07
- 需求：数据字典管理（sys_dict_type / sys_dict_data 两级 CRUD + cloud-web 主从管理页）
- 设计：`docs/superpowers/specs/2026-10-07-dict-management-design.md`（D1-D10）
- 契约：`docs/superpowers/contracts/2026-10-07-dict-api.md`（字典域现行版 v1；错误码 3008-3012 归本域，3013+ 归保护另案）
- 并行编排：后端章（B1-B6）与前端章（F1-F6）**无文件交集、可同时派发**；前端 F6 联调与 e2e 章依赖 B6 完成（此前按契约 mock 或等待）

```
主控：① 同消息并行派发 backend-agent（B1→B6）+ frontend-agent（F1→F6）
      ② B1 落库 + B6【卡点】用户重启 9202 + curl 验收通过 → 前端 dev 指 18080 真实联调（F6 复验）
      ③ 前端-agent 跑 E1-E3（断言维护 + D 系新场景 + 六脚本全量回归）+ 视觉核对
      ④ 双审（契约逐条 + quality）→ 修复循环 → 合并 main
```

- 权限快照注意（契约 §1）：B1 种子落库后 admin **须重新登录**才有 system:dict:*（e2e 每脚本新登录天然满足；手工验收同）

## 后端章（cloud-base/cloud-system/ + scripts/sql/，backend-agent）

> 规范来源：CLAUDE.md 编码规范 + `/backend-crud` 技能；ArchitectureGuardTest / MapperXmlBindingTest 对新代码自动执法，构建全绿即合规。改动全部新增文件 + 两个 SQL 文件，**既有 Java 文件零改动**。

### B1 DDL 与种子（两表 + 菜单 + admin 绑定 + 执行落库）

- 文件：
  - `cloud-base/scripts/sql/2026-10-07-dict-mgmt.sql`（新建，增量）
  - `cloud-base/scripts/sql/cloud_system.sql`（改，基线同步）
- 内容：
  - 增量脚本：头注释（红线：不动既有表结构，sys_menu 仅 INSERT 4 行新种子；admin 账号/角色零触碰，仅补 sys_role_menu 4 行绑定；幂等提示：建表 IF NOT EXISTS 幂等，INSERT 显式 id 不幂等——重复执行前先 `SELECT id FROM sys_menu WHERE id IN (14,141,142,143)` 应 0 行；基线已同步，新环境直接跑基线无需本增量）。语句体 = 设计 §3.1 两表（IF NOT EXISTS 版）+ §3.2 菜单 4 行 + 绑定 4 行
  - 基线 `cloud_system.sql`：DROP 段追加 `DROP TABLE IF EXISTS sys_dict_data; DROP TABLE IF EXISTS sys_dict_type;`；sys_menu 建表后追加两表 CREATE（不带 IF NOT EXISTS，随 DROP 流程）；种子 INSERT 追加 4 行（14/141/142/143，与增量语义等价；role_menu 的 SELECT 全量绑定天然覆盖，**不重复加显式绑定**）
  - **执行落库**（MySQL 无客户端，CLAUDE.md 通路）：临时 java 单文件源码（JDBC `jdbc:mysql://127.0.0.1:3306/cloud_system`，root/空密码）逐条执行并打印影响行数；mysql-connector-j jar 取本地 `~/.m2/repository/com/mysql/mysql-connector-j/`（版本实现时验证）；临时文件放 `cloud-base/scripts/` 外用后即删，不进 git
  - **执行前核对**（脚本头同款提示）：`SELECT id FROM sys_menu WHERE id IN (14,141,142,143)` 应 0 行；`SHOW TABLES LIKE 'sys_dict%'` 应空——不满足则停下排查，不盲跑
- 验收（执行后同通路回查）：
  1. `SHOW TABLES LIKE 'sys_dict%'` 两表在；两表 `SHOW COLUMNS` 含全部列与 COMMENT（deleted 列 NOT NULL DEFAULT 0）
  2. `SELECT id,name,perms,type,path,icon,sort FROM sys_menu WHERE id IN (14,141,142,143)`：14=C/system:dict:list//system/dict/Files/4，141-143=F 三按钮 perms add/edit/remove
  3. `SELECT role_id,menu_id FROM sys_role_menu WHERE menu_id IN (14,141,142,143)` 恰 4 行且 role_id=1
  4. 既有表零变化：`SELECT COUNT(*) FROM sys_menu` 仅 +4；sys_user/sys_role/sys_user_role 行数不变
  5. **本任务不起停任何服务**（SQL 直落库；9202 重启在 B6）

### B2 实体与 Mapper（手写 SQL）

- 文件（全新增）：
  - `entity/SysDictType.java` / `entity/SysDictData.java`（extends BaseEntity，@TableName，@TableId(IdType.AUTO)；status 字段 Integer + 嵌套 `StatusEnum{NORMAL(0),DISABLED(1)}` 含 code/of——ArchitectureGuardTest 强制 Enum 后缀）
  - `mapper/SysDictTypeMapper.java` / `mapper/SysDictDataMapper.java`（纯接口，不 extends BaseMapper）
  - `resources/mapper/SysDictTypeMapper.xml` / `SysDictDataMapper.xml`
- 语句清单（对照 SysRoleMapper.xml 范式：allColumns sql 片段 / deleted=0 显式 / `<if>` 标签体换行 / 只用 #{} / 分页不写 LIMIT）：
  - Type：`pageList(Page)`（id DESC）、`findById`、`countByDictKey(dictKey, excludeId)`、`save`（动态列 trim）、`update`（动态 set + update 审计）、`deleteById(id, updateBy, updateTime)`
  - Data：`pageListByTypeId(Page, typeId)`（WHERE dict_type_id=#{typeId} AND deleted=0 ORDER BY sort ASC, id ASC）、`findById`、`countByTypeId(typeId)`、`countByTypeValue(typeId, value, excludeId)`、`save`、`update`、`deleteById`
  - 多参数方法显式 @Param；`value` 列为 MySQL 非保留关键字可裸用（B1 建表已验证）
- 验收：`mvn -f cloud-base/pom.xml test -pl cloud-system -am` 编译过，MapperXmlBindingTest 对新 mapper 绑定绿

### B3 DTO / VO / Convert

- 文件（全新增）：`dto/DictTypeSaveRequest.java`、`dto/DictDataSaveRequest.java`、`vo/SysDictTypeVo.java`、`vo/SysDictDataVo.java`、`convert/SysDictTypeConvert.java`、`convert/SysDictDataConvert.java`
- 内容：DTO 字段 = 契约 §2.2/§3.2 入参表（TypeRequest{id,dictName,dictKey,status}；DataRequest{id,typeId,label,value,sort,status}，Long 类型字段 Long→String 由全局 Jackson 出参处理）；VO 字段 = 契约 §4（不含 deleted）；Convert 静态 `toVo` 原生 setter 逐字段（禁三方拷贝工具）
- 验收：编译过；字段名/类型与契约 §4 逐字段一致

### B4 Service 与 Controller（含单测）

- 文件（全新增）：
  - `service/SysDictTypeManageService.java`、`service/SysDictDataManageService.java`
  - `controller/SysDictTypeController.java`（@RequestMapping("/dict/type")）、`controller/SysDictDataController.java`（@RequestMapping("/dict/data")）
  - `test/.../service/SysDictTypeManageServiceTest.java`、`SysDictDataManageServiceTest.java`
- Service 行为（契约 §2/§3 行为列逐条落码；校验前置 + 审计显式 + catch `log.error` 记根因后转 BusinessException）：
  - Type：`pageList(query)`；`save(req)`——空白校验(1002)→查重(3009)→审计四值→DuplicateKey 兜底 3009→返新 id；`update(req)`——requireType(3008)→空白拦截(1002)→查重排除自身(3009)→动态更新+审计两值；`delete(id)`——requireType→countByTypeId>0 抛 3011→deleteById
  - Data：`pageList(typeId, query)`——typeId 空 1002→类型存在 3008→分页；`save(req)`——typeId/label/value 校验(1002)→类型存在(3008)→(typeId,value) 查重(3012)→审计四值→兜底 3012；`update(req)`——requireData(3010)→空白拦截→typeId 变更时类型校验(3008)→查重排除自身（生效 typeId = 传入 ?? 现值）→动态更新；`delete(id)`——requireData(3010)→deleteById
  - 方法命名合规（find/save/update/pageList/delete/count 前缀）；写操作审计 = `SecurityUtils.currentAccount()` + `LocalDateTime.now()`；type delete 单表单语句无 @Transactional，role 模式中仅多表写才加
- Controller：8 端点（契约 §2.1-§3.4 路径/方法/@PreAuthorize 逐条）；两行式返回；`@PathVariable("id")` 显式命名；每方法 javadoc；page 端点 `PageQuery query` + 项 page 加 `@RequestParam(value = "typeId", required = false) Long typeId`（**required=false + Service 判空 1002**——避免缺参走 Spring 参数绑定异常偏离契约，契约 §3.1）
- 单测（Mockito mock mapper，沿 SysRoleManageServiceTest 范式；用例名自述）：
  - Type：save 空名/空键→1002；save 键重复→3009；save DuplicateKey→3009 且 log 兜底路径；update 不存在→3008；update 空白名→1002；update 键重复→3009；delete 不存在→3008；delete 有项→3011 且 verify never deleteById；delete 空类型→verify deleteById 一次
  - Data：save typeId null→1002；save 类型不存在→3008；save 空 label/value→1002；save 值重复→3012；pageList typeId null→1002 / 类型不存在→3008；update 不存在→3010；update 值重复→3012；delete 不存在→3010
- 验收：新增单测全绿（预计 17 例）；Service 方法体 ≤50 行目标/100 上限

### B5 全量构建与守护验证

- 内容：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am`
- 验收：全绿（既有 60+ 单测 + 新增 + ArchitectureGuardTest/MapperXmlBindingTest 无新违规）

### B6【卡点：需用户重启 cloud-system】curl 验收（经网关）

- 内容（ASCII 入参——Git Bash 中文 JSON 是 GBK 会 500；**绝不以种子 id 做写操作**）：
  1. **请用户重启 9202**（`java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar`；网关 18080 / sso 9201 在跑则不动；重启后 admin 重新登录拿新快照）
  2. `POST /sso/auth/login`（admin/admin123）取 accessToken
  3. `GET /system/dict/type/page?pageNum=1&pageSize=10`：`total:"0"`,`rows:[]`；id 类字段为字符串
  4. 类型闭环：POST `{"dictName":"e2ecurl-status","dictKey":"e2ecurl_status","status":0}` → 记新 id → 同 body 再 POST → 断言 code 3009 → POST `{"dictName":"","dictKey":"x"}` → 1002 → PUT 正常改名 → PUT `{"id":"..","dictName":""}` → 1002
  5. 项闭环：`GET /system/dict/data/page?typeId={id}`（200 空表）→ POST 项 `{"typeId":"..","label":"Active","value":"0","sort":1,"status":0}` → 同 value 再 POST → 3012 → `DELETE /system/dict/type/{id}` → **3011** → 删项 → 删类型 → type/page 断言无 e2ecurl 残留
  6. 审计核对：e2ecurl 行 createBy/updateBy=`admin`，createTime 为 `yyyy-MM-dd HH:mm:ss`
- 验收：断言全过且无残留；完成后回报主控（后端就绪信号，解锁 F6 联调）

## 前端章（cloud-web/，frontend-agent）

> 规范唯一来源 `/frontend-page` 技能；样板 = 用户/角色页三件套；Element Plus 按需引入（ElMessage/ElMessageBox 显式 import）；**router/index.ts 与 Sidebar.vue 零改动**（动态路由 + user-nav 自动接管）。依赖顺序 F1→F2→F3→F4/F5→F6。

### F1 类型字典扩展

- 文件：`cloud-web/src/types/api.ts`（改）
- 内容：按契约 §7 追加 `SysDictTypeVo` / `SysDictDataVo`（审计四字段 `string | null`）与五个 Payload/Query interface；注释标"契约 2026-10-07-dict-api §7"
- 验收：`npm run build` 零错误；既有类型零改动

### F2 api 层（新文件）

- 文件：`cloud-web/src/api/dict.ts`（新建）
- 内容：8 函数（每函数 JSDoc 注契约节号）：`pageDictType(DictTypePageQuery): Promise<PageResult<SysDictTypeVo>>`、`createDictType(SaveDictTypePayload): Promise<string>`、`updateDictType(UpdateDictTypePayload): Promise<null>`、`deleteDictType(id): Promise<null>`、`pageDictData(DictDataPageQuery): Promise<PageResult<SysDictDataVo>>`、`createDictData/updateDictData/deleteDictData`；URL 带 `/system/dict/...` 前缀走 `/api` 代理
- 验收：build 零错误；契约没有的参数不出现

### F3 视图注册（动态路由接入）

- 文件：`cloud-web/src/router/viewRegistry.ts`（改）
- 内容：import `DictManageView from '../views/system/dict/index.vue'`；`VIEW_REGISTRY` 追加 `'/system/dict': DictManageView`
- 验收：build 绿；登录后（种子生效 + 重登）侧边"系统管理"下出现"字典管理"（icon Files），点进面包屑 首页/字典管理

### F4 主从式管理页（设计 §5）

- 文件：`cloud-web/src/views/system/dict/index.vue`（新建）
- 内容：
  - `defineOptions({ name: 'SystemDict' })`（path 派生名，keep-alive 契约）；STATUS_MAP（0 正常 success / 1 停用 danger）+ `rowOf` 唯一收窄（左右两表各一，泛型标注）
  - 布局：flex 两栏 el-card——左 `width: 380px`，右 `flex: 1; margin-left: 16px`
  - 左栏：header"字典类型"+ 新增类型按钮（v-perms `system:dict:add`）；el-table 4 列（字典名称 min-width 110 / 字典键 min-width 120 / 状态 80 tag / 操作 110 fixed right——编辑+删除 link）；`highlight-current-row` + `@current-change` 联动；分页 `layout="total, prev, pager, next"`、pageSize 恒 10（窄面板省 sizes）；onMounted 加载第 1 页
  - 右栏：header `字典项{selected ? '：' + dictName + '（' + dictKey + '）' : ''}` + 新增字典项按钮（v-perms add，**未选中 disabled**）；el-table 10 列（标签 110 / 值 110 / 排序 70 / 状态 80 tag / 创建人 100 / 创建时间 160 / 更新人 100 / 更新时间 160 / 操作 110 fixed right）；未选中时表格 data 空 + `el-empty description="请在左侧选择字典类型"`（或 empty 插槽）；完整分页（sizes [10,20,50]，同 role 页）
  - 联动与刷新：行选中 → `loadDataPage(1)`；类型增/改/删后 `loadTypePage(当前页)`，**删除的若是选中类型则清 selectedType**；编辑后按 id 重新对齐选中行（dictName 变化随刷新呈现）；项增/改/删后 `loadDataPage(当前页)`
  - 删除：类型确认文案 `确定删除字典类型 "{dictName}" 吗？`；项确认文案 `确定删除字典项 "{label}" 吗？`；3011/3008/3010 由拦截器 toast，catch 留空
  - v-perms 挂载 6 点（契约 §8）：两处新增=add，两侧编辑=edit，两侧删除=remove
- 验收：空库首开 = 左空表 + 右 el-empty + 新增字典项禁用；选中联动正确；选中类型被删后右栏回空态；分页/高亮/时间格式（`?? '-'`）与 role 页一致

### F5 两个表单弹窗（设计 §6）

- 文件：`cloud-web/src/views/system/dict/components/DictTypeFormDialog.vue`、`DictDataFormDialog.vue`（新建）
- 内容（对照 RoleFormDialog 范式）：
  - DictTypeFormDialog：props `{ modelValue, mode: 'add' | 'edit', dictType?: SysDictTypeVo }`；emits `update:modelValue` / `success`；字段 dictName（必填 1-30）/ dictKey（必填，pattern `^[a-zA-Z][a-zA-Z0-9_]{0,49}$`，**编辑可改**——roleKey 同款）/ status radio 0/1 默认 0；watch(modelValue) 打开初始化（edit 回显三字段，add 置默认，先 clearValidate）；提交 validate → create/update（**edit 全量提交三写字段 + id**）→ toast"新增成功/保存成功"→ emit success + 关闭；catch 留空（3009 由拦截器 toast，弹窗不关）；loading 防重
  - DictDataFormDialog：props `{ modelValue, mode, dictData?: SysDictDataVo, typeId: string }`（typeId=页面当前选中类型，由页面传入）；字段 label（必填 1-50）/ value（必填，pattern `^[A-Za-z0-9_.-]{1,50}$`）/ sort el-input-number 0-999 默认 0 / status radio；提交 add 注入 typeId、edit 全量提交五写字段 + id（typeId 亦提交）；其余同上（3012 toast 弹窗不关）
- 验收：空提交出必填错误且 0 请求；pattern 拦截（dictKey 数字开头 / value 带空格）；编辑回显正确；保存后父页刷新且弹窗关闭；重复键/值提交弹窗保留可改

### F6 构建与联通验证（dev 5173 由 agent 自管，不起不动后端）

- 内容：连续两次 `npm run build` 全绿（components.d.ts 陷阱）；B6 就绪后 agent 自起 dev server（5173），`curl http://localhost:5173/api/system/demo/ping` 代理链路通；手工走查：空态 → 新增类型选中 → 增/改/停用项 → 3011 拦截 → 3012 拦截 → 删项删类型全闭环
- 验收：两次 build 零错误；手工冒烟六步可用（后端未就绪阶段先按契约 mock，联调切回 18080 并在报告注明）

## e2e 章（cloud-e2e/，frontend-agent 或主控指派）

### E1 既有断言维护（种子 +4 菜单的必改项，与功能代码同 commit）

- 文件与改动（旧 → 新，全部为 admin 导航形状断言）：
  - `cloud-e2e/run-menu-e2e.mjs:147`：`'用户管理,角色管理,菜单管理,工作台'` → `'用户管理,角色管理,菜单管理,字典管理,工作台'`
  - `cloud-e2e/run-nav-e2e.mjs:193`：`root.children.length === 3` → `=== 4`
  - `cloud-e2e/run-nav-e2e.mjs:198`：`'用户管理,角色管理,菜单管理'` → `'用户管理,角色管理,菜单管理,字典管理'`（N1 如有逐子级字段断言，补 字典管理 `path=/system/dict`、`icon=Files` 行，对齐既有三子级写法）
  - `cloud-e2e/run-nav-e2e.mjs:220` 与 `:423`：侧边精确串同 147 处理（+字典管理）
- 不改仅回归验证的兼容点（设计 D9 已核实）：role R1 相邻断言 / R5a active==total / user 与 scaffold 的 includes 断言 / 菜单搜索过滤"角色" / N2 受限用户侧边恰"角色管理,工作台"
- 验收：改后 user/role/menu/scaffold/nav 五脚本单跑全绿

### E2 新场景脚本 run-dict-e2e.mjs（D 系列，设计 §10.3）

- 文件：`cloud-e2e/run-dict-e2e.mjs`（新建，复用 `lib/harness.mjs`）；`cloud-e2e/package.json`（改：`e2e` 链末追加 `&& node run-dict-e2e.mjs`；`e2e:headless` 同理；新增 `e2e:dict`）
- 场景断言（逐条实现）：
  - D0：admin 登录；侧边含"字典管理"且序在"菜单管理"后；点击进入，面包屑 首页/字典管理
  - D1：左表 4 列表头（字典名称/字典键/状态/操作）；左分页"共 0 条"（种子零字典数据）；右栏 el-empty"请在左侧选择字典类型"；"新增字典项"disabled
  - D2：类型弹窗空提交"请输入字典名称"+0 请求 → 新增 `E2E字典{ts}`（dictKey `e2edict{ts}`）→ 行出现且**选中高亮**、右栏 header 显示 `字典项：E2E字典{ts}（e2edict{ts}）`→ 编辑改名+停用 → 行内名称更新 + 状态 tag danger → 同 dictKey 再建 → toast 含"字典键已存在"
  - D3：右栏空表 → 项弹窗空提交 0 请求 → 新增项（label `E2E项A`、value `val{ts}`、sort 1）→ 行出现 → 同 value 再建 → toast 含"字典项值已存在" → 编辑标签/排序 → 行更新
  - D4：删有项类型 → toast 含"先删除字典项"（3011）→ 类型行仍在 → 删全部项 → 删类型（确认框文案含类型名）→ 左行消失 + 右栏回 el-empty
  - CLEANUP：删全部 `e2edict`/`E2E` 前缀类型（**先删项后删类型**，3011 语义）；`page` 断言无 e2e 残留；种子菜单/admin 零触碰
- 纪律：黑盒（禁 import 前端内部代码）；数据 e2e 前缀+时间戳；HTTP 恒 200（≥400 仅预期业务码 toast 场景）；无 console error / pageerror；截图 ≥5 张（页面全貌/类型弹窗/项弹窗/3011 toast/删除确认）走 analyze_image 视觉核对，结论文字记录，截图不进 git；**场景脚本与功能代码同一 commit**
- 验收：`npm run e2e:dict` 单跑 PASS；CLEANUP 断言通过

### E3 全量回归

- 内容：`cd cloud-e2e && npm run e2e`（有头）六脚本（user→role→menu→scaffold→nav→dict）顺序全跑
- 验收：六脚本全 PASS、无 console error / pageerror、无数据残留；完成后回报主控进入双审

## 集成与验收编排（主控）

1. 同消息并行派发两章（后端改 cloud-base / 前端改 cloud-web，零目录冲突）；各自完成章内验收后回报
2. B1 落库 → B6【卡点】请用户重启 9202 + curl 验收 = 后端就绪 → F6 真实联调复验 → E1-E3
3. 双审：spec 审查按契约逐条（字段表/错误码 3008-3012/权限标识/占位声明重排/additive 声明）+ quality 审查 → 修复循环 → 合并 main（e2e 场景脚本与功能代码同一 commit）

## 移交备忘（下一阶段规划前必读）

1. **消费端点未做**：`GET /system/dict/data/type/{dictKey}`（表单下拉取数，含"停用类型/项不参与消费"过滤与缓存策略）——DDL/唯一键已备好，下轮 additive，届时议 Redis 缓存与 remark 列
2. **墓碑占键**：删除后同 dict_key / (typeId,value) 永不可重建（3009/3012）；如运营反弹，另立物理清理任务（设计 D8）
3. **无批量删项/搜索/拖拽排序**：体验增强另案（设计 §11）
4. **type/data 分权演进路径**：统一 system:dict:* 的拆分方案已留（设计 D3），走契约修订 + 种子 additive F 节点
5. **e2e 断言耦合登记**：admin 侧边精确串现散布于 run-e2e(模糊)/run-menu(147)/run-nav(220,423)/run-scaffold——后续每轮菜单种子扩张必须重跑 D9 式审查；建议后续把"侧边顺序断言"收敛为"含关键项+相对顺序"弱断言（另案）
6. **错误码账本**：3xxx 现用 3001-3012；**3013 起预留给「内置角色/菜单保护」另案**（本轮契约 §5 重排声明，三份旧契约原文未回改——下轮保护另案开工时以其契约再接续声明）
7. **权限快照验收注意**：任何"种子后立即验收"流程都含"重新登录"步骤（导航实时/操作快照两维，契约 §1）；手工验收与 e2e 均适用

## 给 backend-agent 的任务清单（可直接粘发）

按 B1→B6 执行；对齐基线 = 契约 `docs/superpowers/contracts/2026-10-07-dict-api.md` + 设计 `2026-10-07-dict-management-design.md`（D5/D6/D8 必读）+ 本计划后端章 + `/backend-crud` 技能 + CLAUDE.md 编码规范。要点重申：

- B1：增量 `2026-10-07-dict-mgmt.sql`（两表 IF NOT EXISTS + 菜单 4 行 + 绑定 4 行，头注红线与幂等提示）+ 基线 `cloud_system.sql` 同步；**执行前核对 4 个 id 空闲**；java 单文件源码执行（connector-j 从 ~/.m2 取，路径实现时验证）+ 四条回查；不动服务
- B2：实体×2（StatusEnum 嵌套 Enum 后缀）+ Mapper×2 + XML×2（pageList/findById/countBy…/save/update/deleteById；deleted=0 显式、`<if>` 换行、#{} only、无 LIMIT）
- B3：DictTypeSaveRequest/DictDataSaveRequest + SysDictTypeVo/SysDictDataVo + Convert×2（原生 setter）
- B4：Service×2（校验前置 1002/查重 3009、3012/存在性 3008、3010、3011/DuplicateKey 兜底 + log.error/审计显式四值两值）+ Controller×2（8 端点两行式/显式 @PathVariable/javadoc/@PreAuthorize 映射）+ 单测×2（17 例清单见 B4）
- B5：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am` 全绿
- B6：【卡点】**请用户重启 9202**（网关/sso 不动；admin 重登）+ curl 验收（契约 §5 错误码逐个触发：1002/3008/3009/3010/3011/3012；e2ecurl 前缀删净）
- 红线：与契约不符回报主控不自行猜测；不碰种子数据；既有 Java 文件零改动；完成后回报（解锁 F6 联调）

## 给 frontend-agent 的任务清单（可直接粘发）

按 F1→F6 顺序执行；对齐基线 = 契约 §2/§3/§4/§7/§8 + 设计 `2026-10-07-dict-management-design.md`（D2/D3 必读）+ 本计划前端章 + `/frontend-page` 技能。要点重申：

- F1：`types/api.ts` +SysDictTypeVo/SysDictDataVo + 五个 Payload/Query（契约 §7）
- F2：`api/dict.ts` 8 函数（JSDoc 注契约节号；URL `/system/dict/...`）
- F3：`viewRegistry.ts` +`'/system/dict'`；**router/index.ts 与 Sidebar.vue 零改动**；页面 defineOptions name `SystemDict`
- F4：`views/system/dict/index.vue` 主从页（左 380px 4 列窄分页 highlight-current-row；右 10 列完整分页；el-empty 空态 + 新增字典项未选中 disabled；删除选中类型清右栏；v-perms 6 挂载点）
- F5：DictTypeFormDialog（dictKey 可改 + pattern）+ DictDataFormDialog（typeId 由页面注入；value pattern；edit 全量五字段+id）；catch 留空 + loading 防重
- F6：连续两次 `npm run build` 全绿；后端就绪后 **agent 自管 dev 5173** 联调冒烟（未就绪先按契约 mock 并注明）
- e2e（E1-E3）：E1 改 4 处断言（menu:147 / nav:193/198/220/423，N1 补字典管理逐字段）；E2 `run-dict-e2e.mjs` D0-D4+CLEANUP + package.json 链追加 `e2e:dict`；E3 六脚本全量回归；截图 ≥5 张 analyze_image 视觉核对（结论文字记录）
- 红线：Element Plus 按需引入（禁全量）；契约与实测不符回报主控；e2e 黑盒 + e2e 前缀数据 + 不碰种子/admin；场景脚本与功能代码同一 commit
