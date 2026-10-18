# 知识库权限分配实施计划（2026-10-18）

> 依据：设计 `docs/superpowers/specs/2026-10-18-kb-permission-design.md` + 契约
> `docs/superpowers/contracts/2026-10-18-kb-permission-api.md`（前后端唯一对齐物）。
> 两章独立可并行：后端章（Java B1-B4 + Python P1-P4）与前端章（F1-F4）。跨章依赖仅在联调/验证段标注。
> 轨道：全栈（Java 三服务 + Python AI 基座 + cloud-web + cloud-e2e）。
> 通用约束：Java 侧守 ArchitectureGuardTest + `/backend-spec`；Python 侧守 test_architecture_guard.py +
> `/ai-spec`；前端守 `/frontend-page`；审计时间一律 DB 时钟（SQL NOW() / DEFAULT CURRENT_TIMESTAMP），
> 应用层不传时间参数，操作人账号显式传；测试数据 e2e_ 前缀+时间戳；契约与实现不符时回报主控转架构修订，不得单方改。

## 后端章

### B1（Java）网关身份头注入扩展

- 改动：
  - `cloud-base/cloud-common/cloud-common-security-starter/src/main/java/com/cloudai/common/security/constant/SecurityConstants.java`：新增 `HEADER_USER_ROLE_IDS = "X-User-RoleIds"`、`HEADER_USER_DEPT_IDS = "X-User-DeptIds"`（分隔符复用 `PERMS_SEPARATOR`，不新造常量）。
  - `cloud-base/cloud-gateway/src/main/java/com/cloudai/gateway/filter/AuthGlobalFilter.java`：`OnlineSessionView` 增 `roleIds`/`deptIds`（`List<Long>`）；`stripUserHeaders` 增删两头；`withUserHeaders` 注入两头（null → 空串，与 account 同款；join ","）；解析容错沿 permissions 既有 try/catch 分支。
- 测试：`cloud-base/cloud-gateway/src/test/java/com/cloudai/gateway/filter/AuthGlobalFilterTest.java` 增三用例——新头注入（会话含 roleIds/deptIds）、外部伪造两头被剥离、**旧格式会话（JSON 无新字段）注入空串**。
- 验收：网关单测全绿；手工冒烟——携带伪造 X-User-RoleIds 的请求经网关后下游收到的是会话真值或空串（curl 经网关打到 system demo 透传端点或 8010 观察日志，实现时择最小路径）；MVN 全量构建通过。
- 依赖：无（可先行）。注意网关是 WebFlux，禁引 spring-boot-starter-web（既有红线）。

### B2（Java）system 登录聚合扩展 roleIds + deptIds

- 改动：
  - `cloud-base/cloud-api/cloud-system-api/src/main/java/com/cloudai/system/api/domain/LoginUserDTO.java`：增 `roleIds: List<Long>`（仅启用角色）、`deptIds: List<Long>`（自身+全部祖先，root→self；未挂部门为空表）。Feign JSON 加字段向下兼容（旧消费方忽略未知字段）。
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/service/SysUserLinkageService.java`：`findByAccount` 补两段聚合——角色 id 列表（新 mapper 查询）；部门链（`deptMapper.listAll()` 未删除行 → 内存沿 parent_id 向上收集，user.dept_id 为 NULL 短路空表；断链即止防御）。方法体超 50 行目标则抽私有方法。
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/mapper/SysUserMapper.java` + `src/main/resources/mapper/SysUserMapper.xml`：新增 `listRoleIdsByAccount`（JOIN sys_user_role/sys_role，`r.status=0 AND r.deleted=0`，与权限聚合同口径；只用 #{}；返回 List<Long>）。
- 测试：`SysUserLinkageService` 测试类增用例——无角色空表、停用角色被排除、无部门空表、深部门链顺序（root→self）、祖先部门软删（防御断链）。
- 验收：单测全绿；`/inner/user/{account}` 实测返回含两新字段（data 走库验证，pymysql 造数沿中文入库规则）；`$MVN -f cloud-base/pom.xml clean install` 通过。
- 依赖：无（可与 B1 并行；B4 消费本任务产物）。

### B3（Java）system 两端点权限放宽（授权弹窗数据源）

- 改动：
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/controller/SysDeptController.java`：dept/tree 端点权限表达式追加 `'ai:kb:update'`（hasAnyAuthority 列表 +1，javadoc 注明「知识库授权弹窗共同数据源」）。
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/controller/DataPermController.java`：subject-options 端点（`GET /system/data-perm/subject-options`）@PreAuthorize 改 `hasAnyAuthority('system:dataPerm:list','ai:kb:update')`，javadoc 同步。
  - 契约同步：`docs/superpowers/contracts/2026-10-10-data-permission-api.md` §2.1/§3.6 权限行更新，各加一句修订注记指向 `2026-10-18-kb-permission-api.md` §7（放宽 additive，既有消费方零影响）。
- 验收：仅持 `ai:kb:update`（无任何 system:* 权限）的角色，其用户经网关调两端点 200；原权限组合回归不受影响（既有数据权限 e2e 跑通）；两契约文档与注解一致。
- 依赖：无。

### B4（Java）sso 会话结构扩展

- 改动：`cloud-base/cloud-sso/src/main/java/com/cloudai/sso/domain/OnlineSession.java` 增 `roleIds`/`deptIds`（`List<Long>`）；`TokenService.issueTokens` 从 LoginUserDTO 映射两字段（refresh 链路回查 system 自动携带，零额外改动）。
- 测试：TokenService 测试增用例——登录后会话 JSON 含两字段；**旧格式会话 JSON（无新字段）反序列化不报错且为 null**（存量 Redis 会话兼容实证）；refresh 后新字段更新（换部门场景模拟）。
- 验收：单测全绿；真机链——admin 登录 → 网关转发请求到 8010，AI 侧日志/调试端点可见 X-User-RoleIds/X-User-DeptIds 真值（与 B1/B2/P3 联合验证，见 P4）。
- 依赖：B2（LoginUserDTO 字段先行）。

### P1（Python）授权表实体 + DDL

- 改动：
  - `cloud-base-ai/src/cloud_base_ai/rag/entities.py`：新增 `AiKbGrant`（表 `ai_kb_grant`；内嵌 `SubjectTypeEnum`（ROLE=0/DEPT=1/USER=2，含 code 与 of(code)，字段类型保持 int）；列 id/kb_id/subject_type/subject_id/create_by/create_time——**不继承 AuditMixin/SoftDeleteMixin**（纯关系表：物理删除、无 update 审计列）；create_time 用 `server_default=text("CURRENT_TIMESTAMP")`，插入不设值；每列 comment=）。
  - `cloud-base/scripts/sql/cloud_ai.sql`：基线追加建表（DDL 全文见设计文档 2026-10-18-kb-permission-design.md 第 3 节决策 D4）；新增增量脚本 `cloud-base/scripts/sql/2026-10-18-ai-kb-grant.sql`（同表定义）；pymysql 落库沿中文入库规则。
- 验收：`test_rag_entities.py` 增实体用例通过；guard 全绿（实体列 comment 红线）；`SHOW CREATE TABLE ai_kb_grant` 两索引在案；重复落增量脚本报 uk 已存在（幂等性按既有脚本口径处理）。
- 依赖：无（Python 章起点）。

### P2（Python）DAO 扩展：授权读写 + 可见性过滤

- 改动：`cloud-base-ai/src/cloud_base_ai/rag/dao.py` 新增四方法（收 session、flush 收尾永不 commit）：
  - `list_grants_by_kb(session, kb_id) -> Sequence[AiKbGrant]`（命中 uk_kb_subject 前缀）；
  - `save_kb_grants(session, kb_id, grants) -> None`（saveXxx 动词沿「全删全插即 save」先例：按 kb_id 物理 DELETE → 非空批插；create_by 取 current_account，**不设 create_time**——DB DEFAULT 时钟）；
  - `list_visible_kb_ids(session, user_id, role_ids, dept_ids) -> Sequence[int]`（三分支 OR，空列表分支不拼接防 IN ()；命中 idx_subject）；
  - `delete_grants_by_kb(session, kb_id) -> int`（知识库删除级联段）。
  - `page_list_kbs` 增可选过滤参数（identity 派生的 visible 条件：豁免=None 不过滤；非豁免=`or_(create_by==account, id.in_(visible_ids))`，visible_ids 空则仅 create_by 条件）。
- 验收：`test_rag_dao.py` 增用例——replace 全删全插/空数组清空、visible 三分支组合与空集短路、delete 级联、既有 list 用例不回归（豁免路径行为不变）；guard 全绿。
- 依赖：P1。

### P3（Python）Identity 解析 + service + 端点

- 改动：
  - `cloud-base-ai/src/cloud_base_ai/security.py`：新增 `Identity`（user_id: int | None、account、perms、dept_ids、role_ids）与 contextvar 存取；`require_perms` 内解析五头并存 Identity（保留 set_current_account 兼容调用）；解析容错——逗号分段非整数跳过记 warning；新增常量 `MGMT_PERMS = {"ai:kb:add", "ai:kb:update", "ai:kb:delete"}` 与 `is_mgmt_exemption(identity)` 帮助函数（或落 service，按内聚择一，javadoc/注释写明豁免口径）。
  - `cloud-base-ai/src/cloud_base_ai/rag/schemas.py`：`KbPermsVo`（role_ids/dept_ids/user_ids: list[int]）、`KbPermsSaveReq`（三字段可选默认 []，每元素 ≥1、每数组 ≤100——pydantic 约束）。
  - `cloud-base-ai/src/cloud_base_ai/rag/service.py`：
    - `find_kb_perms(kb_id) -> KbPermsVo`（find_kb_by_id 校验 5004 → list_grants_by_kb → 三组分型）；
    - `save_kb_perms(kb_id, req) -> None`（校验 5004 → 组装实体（重复 id 去重）→ `save_kb_grants`；@transactional）；
    - `page_list_kbs`/`page_list_kb_files` 接 Identity：豁免短路走原逻辑；files 非（豁免/创建者/授权命中）抛 `BusinessException("无权访问该知识库", 5011)`（先 5004 后 5011 顺序）；
    - `delete_kb` 级联追加 `delete_grants_by_kb`（与三表软删同事务）。
  - `cloud-base-ai/src/cloud_base_ai/rag/router.py`：`kb_update_router` 追加两端点（GET/PUT `/{kb_id}/perms`，两行式返回，javadoc 注契约节号）——**零新 router、main.py 零改动**。
- 验收：`test_rag_service.py`/`test_rag_router.py`/`test_security.py` 增用例全绿（§2/§3 各分支：403/1001/5004/200；5011 三态判定：豁免/创建者/granted）；`uv run --project cloud-base-ai pytest` 全量通过（含 guard）。
- 依赖：P1、P2。本地直连冒烟：手工五头（X-User-Perms/X-User-Account/X-User-Id/X-User-RoleIds/X-User-DeptIds）矩阵验证 §4 三态与 §5 5011。

### P4（Python）双路实证与契约回填

- 改动：无代码；产出验证记录回填 `docs/superpowers/contracts/2026-10-18-kb-permission-api.md` §9。
- 验证矩阵（环境：MySQL/Nacos/Redis + sso/system/gateway/8010）：
  1. 直连路：手工头矩阵——豁免（带任一管理 perms）全量可见；list-only 用户 own+granted（RoleIds/DeptIds 各组合）；DeptIds 祖先链命中部门授权；空五头=仅 own；files 5011；
  2. 网关全链：admin（豁免）全见；造 list-only 测试用户（e2e_ 前缀+时间戳，挂角色+部门）——授权前仅 own、角色授权后命中、部门（含祖先节点）授权后命中、清空授权后回落；换部门后重登生效（快照时效实证）；
  3. 兼容：B4 旧会话场景（升级前登录的会话 → 新头空串 → 授权不命中不报错）；
  4. 回归：既有 kb 七端点对 admin 行为零变化。
- 清理：e2e_ 测试用户/角色/部门/知识库删除收口（沿既有清理口径）。
- 验收：验证记录含请求/响应/DB 回查三要素（沿母契约 §10 格式）；异常分支文案与契约一致。
- 依赖：B1-B4、P1-P3 全部完成（联调段）。

## 前端章

### F1（前端）类型与 API 封装

- 改动：
  - `cloud-web/src/types/api.ts`：`KbPermsVo`、`KbPermsSavePayload`（role_ids/dept_ids/user_ids: number[]，可选）。
  - `cloud-web/src/api/rag.ts`：`getKbPerms(kbId: number): Promise<KbPermsVo>`（GET `/ai/rag/kb/${kbId}/perms`）、`saveKbPerms(kbId: number, payload: KbPermsSavePayload): Promise<null>`（PUT 同路径）——注释标契约 §2/§3 与 id 类型换轨（选择器 string id → Number 提交）。
- 验收：`npm run typecheck`/`vue-tsc` 通过（沿工程既有检查命令）；无 Element Plus 全量引入（红线）。

### F2（前端）授权弹窗 KbPermsDialog

- 新建：`cloud-web/src/views/rag/kb/components/KbPermsDialog.vue`（沿 RuleConfigDialog/AssignMenuDialog 弹窗形态）：
  - props：modelValue、kb（行数据，标题显示库名）；
  - 三块选择（el-divider 分段或 el-tabs，实现择一、注释写明）：
    - 角色：`el-select multiple filterable`，数据源 `listSubjectOptions(0)`（api/dataPerm.ts 既有）；
    - 部门：`el-tree` show-checkbox node-key=id，数据源 `treeDept()`（api/dept.ts 既有）；
    - 用户：`el-select multiple filterable`，数据源 `listSubjectOptions(1)`（label=`昵称(账号)`）；
  - 打开时并行拉三源 + `getKbPerms` 回显（选项 id 为 string、回显值为 number——比较与勾选统一转换；granted id 不在选项中显示「id:N（已失效）」占位，不阻断保存）；
  - 提交：三集合去重 → `Number(id)` 转换 → `saveKbPerms`（每数组上限 100 前端友好提示，后端 1001 权威）；成功 toast + 关闭；失败拦截器统一 toast（5004/1001）；
  - 权限注记：弹窗数据源两端点已放宽 `ai:kb:update`（契约 §7）——组件头注释写明，防后来者误判越权。
- 验收：真机冒烟（Playwright MCP + 视觉模型）覆盖——三块勾选/回显/清空保存/已失效占位四态；`npm run lint`/typecheck 通过；组件按需引入（unplugin 自动，禁全量注册）。
- 依赖：契约定稿（不依赖后端完成，可 mock 并行；真机联调依赖 P3+B3）。

### F3（前端）知识库页接入

- 改动：`cloud-web/src/views/rag/kb/index.vue`：操作列增「权限」按钮（`v-perms="'ai:kb:update'"`，置于「编辑」旁 link 样式）→ 打开 KbPermsDialog（传行数据）。
- 验收：admin 真机见按钮可开弹窗；无 ai:kb:update 用户不显示按钮（权限快照语义，测试用户重登后验证）；既有列表/文件/重嵌入功能零回归。
- 依赖：F2。

### F4（前端）e2e 场景

- 新建：`cloud-e2e/run-ai-kb-perm-e2e.mjs` + `cloud-e2e/package.json` 增 `"e2e:kbperm"` 脚本（沿 `e2e:aikb` 独立脚本形态，不进 run-e2e.mjs 聚合——先例口径）；与功能代码同 commit；测试数据 e2e_ 前缀+时间戳。
- 场景（route mock 层，沿 run-ai-kb-e2e.mjs 分层 mock 形态）：
  1. admin 打开知识库页 → 行内「权限」按钮 → 弹窗三块勾选（角色/部门树/用户）→ 保存断言请求体（number 数组、去重）→ toast 成功；
  2. 回显场景：mock getKbPerms 返回含已失效 id → 占位 tag 断言；
  3. 按钮权限：mock perms 快照不含 ai:kb:update → 按钮不可见；
  4. 列表可见性（mock 层模拟 §4 修订）：list-only 用户仅见 own+granted 行。
- 验收：`node run-ai-kb-perm-e2e.mjs` 全绿；退出码陷阱按既有口径核对（`grep -c '[FAIL]'` 判定，exit 0 非依据）；既有 `e2e:aikb` 回归通过（admin 豁免路径断言不破——若有 total 断言需按 §4 语义复核，admin 全见故不变）。
- 依赖：F1-F3。

## 移交后续阶段的备忘

1. **检索轮消费点**：「我的可用知识库」只读端点与检索/聊天选库的可见库过滤，直接复用 P2 的 `list_visible_kb_ids`（Identity + ai_kb_grant）——2026-10-18 拍板缓出，届时按 kb-perm 契约 additive 扩展（设计文档 §7.1）。
2. **会话兼容先例**：OnlineSession 加字段=空值兼容（网关注入空串），未来再扩身份头沿用同款；不得删改既有字段。
3. **多级授权扩展点**：ai_kb_grant 加 perm_level 列即升级分级授权；出参从数组改对象数组属 breaking，须走契约修订段。
4. **主体存在性校验**：当前弱引用自愈口径（失效授权永不命中）；若要求保存时严格校验，需 AI→system 存在性查询通道（新契约立项）。
5. **subject-options 规模**：全量启用用户无分页，用户量增长后变慢——属数据权限域既有端点，届时分页化走 data-perm 契约演进。
6. **e2e 聚合**：e2e:kbperm 未进 run-e2e.mjs 聚合（沿 e2e:aikb 先例）；若后续做全量回归收编，两脚本一并评估。
