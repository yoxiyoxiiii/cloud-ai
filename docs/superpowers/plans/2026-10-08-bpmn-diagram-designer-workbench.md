# bpmn 流程图渲染 + 在线设计器 + 工作台两卡 实施计划（全栈：后端章 ∥ 前端章 + e2e 章）

- 日期：2026-10-08
- 需求：bpmn-js 流程图渲染（详情高亮 + 定义页查看，公共 Viewer）+ 在线设计器（Modeler+properties-panel，仅「看 XML+部署」）+ 工作台两卡（我的待办 + 我的申请）+ 菜单种子 331 + e2e BP9-BP12 与既有断言迁移
- 设计：`docs/superpowers/specs/2026-10-08-bpmn-diagram-designer-workbench-design.md`（D1-D9；重点：D1 npm 例外记档与分包铁律、D3 高亮三态矩阵、D6 安全面）
- 契约：`docs/superpowers/contracts/2026-10-08-bpmn-diagram-designer-api.md`（新端点字段表/4008-4009/权限 8 项/种子 SQL 以契约为准，本计划不重复）
- 轨道：**全栈 A 级**——后端章 B1→B5 串行 ∥ 前端章 F1→F7（F7 联调在 B5 后）；e2e 章 E1 在 B3 后，E2 起需 B5+F7

```
主控：① 派发 backend-agent（B1→B5）∥ frontend-agent（F1→F6）
      ② B3 种子落库后放行 E1（menu/role 迁移可先行验证）
      ③ B4 全绿 → B5【卡点】用户重启 9203（新代码）+ admin 重登录 + curl 清单
      ④ F7 联调（B5 后，5173 agent 自管）
      ⑤ e2e：E1（B3 后）→ E2（B5+F7 后）→ E3 八脚本全量 → E4 文档核对
      ⑥ 双审（契约逐条 + quality）→ 修复循环 → 合并 main
```

- 总红线：既有 8 端点 additive-only；4001-4007 零变化；common 模块零触碰；不做删除/挂起/激活端点；**npm 新增恰两包 bpmn-js + bpmn-js-properties-panel（拍板例外，spec D1 记档，范围不外溢）**；bpmn 相关组件一律 dynamic import 分包；服务启停归用户（9203 卡点，9201/9202/18080 本轮零重启）；e2e 黑盒；测试数据 `e2ebpmn${stamp}` 前缀沿用

## 后端章（cloud-base/，backend-agent，B1→B5 串行）

> 规范来源：CLAUDE.md 编码规范 + `/backend-spec` 技能。ArchitectureGuardTest 已在 cloud-bpmn 执法（新代码自动受检：两行式返回/@PathVariable 显式命名/方法 javadoc/Map 禁接参）。本轮无新表无新 mapper 语句——索引与 MapperXmlBindingTest 计数（4）零变化。

### B1 VO + Service 扩容 + 单测（diagram 三态 / xml / deploy 校验链）

- 文件：
  - `cloud-bpmn/src/main/java/com/cloudai/bpmn/vo/DefinitionXmlVo.java`（新建：id/key/name/version/xml 五字段，契约 §2.1）
  - `cloud-bpmn/src/main/java/com/cloudai/bpmn/vo/DeployResultVo.java`（新建：deploymentId + List<DefinitionVo> definitions，契约 §2.2）
  - `cloud-bpmn/src/main/java/com/cloudai/bpmn/vo/LeaveDiagramVo.java`（新建：definitionId/processInstanceId 可空 + activeActivityIds/completedActivityIds List\<String\> + endActivityId 可空，契约 §3）
  - `cloud-bpmn/src/main/java/com/cloudai/bpmn/service/BpmnLeaveManageService.java`（改：+`findDiagram(Long id)`——findById 4001 → businessKey=String.valueOf(id) 查 HistoricProcessInstance 单查 → null 则防御 VO（definitionId=null 空集合，D3）→ 运行中判定（runtime query count）后取 getActiveActivityIds → 历史活动时间升序去重 activityId → endActivityId）
  - `cloud-bpmn/src/main/java/com/cloudai/bpmn/service/DefinitionAppService.java`（改：+`findXmlById(String id)`——repositoryService.getProcessModel（**R2 实现时验证**，异常/空 → 4008）+ ProcessDefinition 查询补 key/name/version；+`saveDeployment(MultipartFile file)`——前置校验空文件/2MB → 4009（常量上限），`createDeployment().addInputStream(文件名, 流).deploy()` try-catch FlowableException → log.error → 4009，成功后 deploymentId 查定义列表组装 DeployResultVo；两方法均只读/单引擎操作**不加 @Transactional**——D4 记档）
  - `cloud-bpmn/src/main/java/com/cloudai/bpmn/controller/DefinitionController.java`（改：类注释「只读」表述更新为契约 §0.1 口径；+两端点方法，见 B2）
  - `cloud-bpmn/src/main/resources/application.yml`（改：+`spring.servlet.multipart.max-file-size: 3MB` / `max-request-size: 4MB`——**本地配置定夺**：与 2MB 业务上限强绑定跟代码走，不进 Nacos（设计 D4 三段式：Boot 默认 1MB 会使 1-2MB 合法文件死在解析层且非 4009 形态））
  - `cloud-bpmn/src/main/java/com/cloudai/bpmn/exception/MultipartLimitExceptionHandler.java`（新建：`@RestControllerAdvice` + `@ExceptionHandler(MaxUploadSizeExceededException.class)` → 两行式返回 `R.fail(4009, "流程文件无效或部署失败")`（`R.fail(int, String)` 重载既有；HTTP 200 + body 4009）；类 javadoc 注明仅拦 multipart 解析层超限、common GlobalExceptionHandler 零触碰红线）
  - `cloud-bpmn/src/test/java/com/cloudai/bpmn/service/BpmnLeaveManageServiceTest.java`（改：+findDiagram 四 case——审批中（active+completed+end null）/已通过（end=endApprove+active 空）/已撤销（end null）/4001/历史缺失防御 VO；mock RuntimeService/HistoryService/TaskService 与 mapper，沿既有 mock 样板）
  - `cloud-bpmn/src/test/java/com/cloudai/bpmn/service/DefinitionAppServiceTest.java`（新建：findXmlById 正常+4008；saveDeployment 成功/空文件 4009/超限 4009/引擎 FlowableException 4009——mock RepositoryService verify deploy 调用参数）
- 验收：`$MVN -f cloud-base/pom.xml test -pl cloud-bpmn` 全绿（含既有守护）

### B2 三 Controller 端点 + 单测

- 文件：
  - `DefinitionController.java`（改）：+`@GetMapping("/{id}/xml")` `@PreAuthorize("hasAuthority('bpmn:definition:list')")` `@PathVariable("id") String id` 两行式返回 `R<DefinitionXmlVo>`（方法 javadoc 注明 definitionId 冒号合法无需编码）；+`@PostMapping(value = "/deploy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` `@PreAuthorize("hasAuthority('bpmn:definition:deploy')")` `@RequestPart("file") MultipartFile file` 返回 `R<DeployResultVo>`（javadoc 注明 multipart 定夺与安全模型，契约 §1/§2.2）
  - `LeaveController.java`（改）：+`@GetMapping("/{id}/diagram")` perms `bpmn:leave:list` 返回 `R<LeaveDiagramVo>`（注意与既有 `/{id}` 共存——Spring 精确段匹配，无冲突）
  - `DefinitionControllerTest.java` / `LeaveControllerTest.java`（改：+新端点委托 verify + @PreAuthorize/@Consumes 注解存在性断言，沿既有 controller 测试样板）
  - `MultipartLimitExceptionHandlerTest.java`（新建：直调 handler 方法断言 code=4009/msg 逐字——解析层归口形态锁定，D4 三段式第 3 段）
- 验收：cloud-bpmn 全单测绿；ArchitectureGuardTest 绿

### B3 菜单种子 331（增量 + 基线 + 落库回查）

- 文件：
  - `cloud-base/scripts/sql/2026-10-08-bpmn-deploy-menu.sql`（新建：契约 §5 全文——331 F 节点 + admin 绑定增量（NOT EXISTS 防重，**SELECT 带 `FROM DUAL`——MySQL 5.7 裸 SELECT 带 WHERE 会 1064，主控审查修订**）；头注释幂等核对：`SELECT id FROM sys_menu WHERE id = 331`（**含墓碑不带 deleted=0**）应 0 行，占用即停回报主控）
  - `cloud-base/scripts/sql/cloud_system.sql`（改基线同步：30 段注释块内 +331 行；admin 绑定由既有 sys_role_menu 全量式 SELECT 天然覆盖）
  - 落库：java 单文件源码 + mysql-connector-j 通路执行增量 + 回查（331 行 is_builtin=1 type=F parent=33；sys_role_menu admin 绑 331 存在；31 行既有种子原样——红线核对）
- 验收：回查全过；**perms 快照时序记档**——admin 须重新登录后 bpmn:definition:deploy 才进 OnlineSession（B5 前提）；本任务不起停服务
- 注意：落库完成才放行 E1（menu/role 迁移验证前置）

### B4 全量构建 + 守护核对

- 命令：`D:/software/apache-maven-3.8.4/mvn -f cloud-base/pom.xml clean install`
- 核对：BUILD SUCCESS；ArchitectureGuardTest 两服务绿；MapperXmlBindingTest system 46 + bpmn 4 **均不变**（无新 mapper 语句）；common 模块零触碰（git diff 核对）；默认构建零 IT 执行
- 验收：全绿

### B5【卡点】用户重启 9203 + curl 验收

- 前置：B1-B4 全绿、331 已落库、**用户重启 cloud-bpmn（9203，新代码）**；admin 重新登录（取 bpmn:definition:deploy 快照）
- curl 清单（token 经网关；XML 文件 UTF-8 落盘——multipart 文件字节直发无 GBK 陷阱，D5）：
  1. **xml 端点**：`GET /bpmn/definition/page` 取 leave_approval 最新 definitionId → `GET /bpmn/definition/{id}/xml` → code 200，data.xml 含 `<process id="leave_approval"` 与中文 name，key/name/version 回显
  2. **diagram 三态**：审批中单（新发起）→ activeActivityIds=["approval"]、endActivityId=null；已通过单 → active=[]、endActivityId="endApprove"、completed 含 start/approval；已撤销单 → endActivityId=null、completed 截断（**R4 实测定型记档**）
  3. **部署**：取 2 的 xml 存 `leave_v2.xml`（原样）→ `curl -F 'file=@leave_v2.xml' ... POST /bpmn/definition/deploy` → 200，definitions[0].version 递增；page 端点 latestVersion 显示新版本
  4. **错误码**：4008（GET /bpmn/definition/leave_approval:9:9/xml 乱 id）；4009 四路——空文件（`-F 'file=@empty.txt'` 0 字节）、**超限业务段（2.5MB 文件——走 Service 前置校验路径）**、**超限解析段（3.5MB 文件——走 MaxUploadSizeExceededException → 本地 handler 归口路径，D4 三段式两段分别验证）**、坏 XML（文本文件 `not bpmn`）；均为 HTTP 200 + body 4009 形态断言
  5. **权限**：无 bpmn:definition:deploy 的用户 token 调 deploy → HTTP 200 + body code 403（@PreAuthorize 口径）；无 token 直调 → HTTP 401（网关层）
- 验收：清单逐条通过并记录；发现问题回报主控（不自行改契约）

## 前端章（cloud-web/，frontend-agent，F1→F7；F1-F6 与后端章并行）

> 规范来源：`/frontend-page` 技能。字段/降级链/权限以契约为唯一依据。**分包铁律（设计 D1）**：bpmn-js 两包相关组件一律 defineAsyncComponent/dynamic import，含 CSS 随 chunk——验收看 build 产物。

### F1 npm 安装 + types + api 模块

- 文件：
  - `cloud-web/package.json`（改：+`bpmn-js`（^18）+ `bpmn-js-properties-panel`（peer 兼容版，**R1 安装时验证锁定，冲突回报**）——恰此两包，npm 例外范围不得外溢）
  - `cloud-web/src/types/api.ts`（改 additive：契约 §7 三类型 DefinitionXmlVo/DeployResultVo/LeaveDiagramVo，注释引契约节号）
  - `cloud-web/src/api/bpmn.ts`（改：+`getDefinitionXml(id: string)` / `getLeaveDiagram(id: string)` / `deployDefinition(file: File)`——后者 `FormData` + `request<DeployResultVo>({ url: '/bpmn/definition/deploy', method: 'post', data: formData })`，注释注明 multipart 非 JSON body（契约 §2.2）与 axios 对 FormData 自动设 boundary（request.ts 拦截器未强设 Content-Type，已核对）
- 验收：`npm run build` 绿

### F2 公共 BpmnViewer 组件（渲染 + 高亮）

- 文件：`cloud-web/src/views/bpmn/components/BpmnViewer.vue`（新建）
  - props：`xml: string`；`activeIds?: string[]`；`completedIds?: string[]`（缺省无高亮）
  - 实现：`onMounted`/watch xml → `const { default: NavigatedViewer } = await import('bpmn-js/lib/NavigatedViewer')` → new NavigatedViewer({container}) → importXML → `canvas.zoom('fit-viewport')` → activeIds 逐个 `canvas.addMarker(id, 'bpmn-highlight-active')`、completedIds 同款 completed 类；组件内静态 import `bpmn-js/dist/assets/diagram-js.css` 与 `bpmn-js/dist/assets/bpmn-font/css/bpmn.css`（随 async chunk）；高亮样式块 **R5 实现时验证** scoped 命中，不命中转非 scoped style；容器固定高度（如 360px）+ loading 态；importXML 失败 catch 留空态
  - 组件不注册 viewRegistry（非路由页）；被消费处一律 defineAsyncComponent 引入
- 验收：build 绿；dev 手测一图（可用 F3/F4 联调前先静态 xml 串冒烟后删）

### F3 请假详情弹窗接入流程图

- 文件：`cloud-web/src/views/bpmn/leave/components/LeaveDetailDialog.vue`（改）
  - 弹窗加宽（640→860px）；descriptions 与 el-steps 之间加「流程图」区块：detail 就绪后串行 `getLeaveDetail`（既有）→ `getLeaveDiagram(leave.id)` → definitionId 非空则 `getDefinitionXml(definitionId)` → defineAsyncComponent 引入 BpmnViewer 传 xml + activeIds/endActivityId 组合（契约 §3 三态矩阵：active = activeActivityIds，end 态把 endActivityId 并入主高亮；completed 浅色）→ definitionId=null 隐藏图区（防御态）
  - 图区 loading/失败空态；时间线（el-steps）保留不动——图与时间线互补
- 验收：build 绿

### F4 流程定义页：查看图弹窗 + 设计器

- 文件：
  - `cloud-web/src/views/bpmn/definition/index.vue`（改：表 +操作列（width ~160）——行按钮「查看图」（perms list 可见）与「设计」（v-perm `bpmn:definition:deploy`）；头部 +「新建流程」按钮 v-perm 同上；BP6 表头由 4 列变 5 列——E1 迁移对应）
  - `cloud-web/src/views/bpmn/definition/components/DefinitionDiagramDialog.vue`（新建：props definitionId——`getDefinitionXml` → defineAsyncComponent BpmnViewer（无高亮）；弹窗 860px）
  - `cloud-web/src/views/bpmn/definition/components/DefinitionDesignerDialog.vue`（新建，设计 D5：弹窗 fullscreen 或 ~1100px；顶部 el-alert info 常驻防呆文案「新版本仅对新发起的流程生效，在途流程继续走原版本」；el-tabs 两页——「流程图」：dynamic import `bpmn-js/lib/Modeler` + `bpmn-js-properties-panel`（attachTo），工具条按钮「导入本地文件」（input file accept .bpmn,.xml → `file.text()` 回填 importXML——**E4 偏离记档**：等价标准 API，非 FileReader）/「适应画布」；「XML 源码」：只读 pre 展示当前 XML（编辑态 Modeler.saveXML 同步，或未改时初始 xml）；footer「保存部署」——当前态取 XML（Modeler saveXML 或源码文本）→ `new File([blob], 'process.bpmn20.xml')` → `deployDefinition` → 成功 ElMessage「部署成功 {key} v{version}」（取返回 definitions 拼接）→ emit 刷新列表 + 关弹窗；打开态：新建=空 Modeler，编辑=props definitionId → getDefinitionXml 回填；全部 dynamic import（Modeler/properties-panel 及其 css 进独立 chunk））
- 验收：build 绿 ×2；dev 冒烟——查看图弹窗渲染 leave_approval、设计器编辑回填与 XML tab 可见

### F5 工作台两卡

- 文件：`cloud-web/src/views/dashboard/index.vue`（重写：defineOptions name='Dashboard' 保留——keep-alive 契约）
  - 两卡布局（**E4 偏离记档**：flex 双列 + `@media (max-width: 991.98px)` 窄屏堆叠（EP md 断点同值），**不用 el-row/el-col**——栅格组件首次进主包会拖入全量响应式栅格 ~36KB 主 CSS，违 F6 ±5% 分包门；视觉等价：gutter 16/双列/窄屏堆叠）：「我的待办」el-card v-perm `'bpmn:task:list'`（onMounted `listTodoTasks()` slice(0,5)——行：leaveTitle/leaveTypeLabel 降级链/applyUserName ?? applyUser/createTime；空态 el-empty「暂无待办任务」；footer「查看全部」→ router.push('/bpmn/task')）；「我的申请」el-card v-perm `'bpmn:leave:list'`（`pageLeave({pageNum:1,pageSize:5})`——行：title/status tag（LEAVE_STATUS_TAG 同款映射+statusLabel 降级链）/createTime；空态「暂无申请记录」；「查看全部」→ '/bpmn/leave'）
  - 两请求 Promise.all 并行；失败 catch 留空态（拦截器 toast）；时间线无依赖
- 验收：build 绿；dev admin 两卡可见、受限形态由 v-perms 保证（联调在 F7）

### F6 双 build + 分包验证

- 命令：`cd cloud-web && npm run build` ×2（components.d.ts 两次生成口径）
- **分包核对（D1 验收）**：dist/assets 出现 bpmn 相关独立 js chunk（含 NavigatedViewer/Modeler/properties-panel 三入口分包）；index 主 chunk 体积与改造前基线对比无显著增长（±5% 内）；bpmn css 在独立 chunk 非主 style
- 验收：双 build 绿 + 分包核对记录在案

### F7 dev 联调（B5 后）

- 命令：dev server 5173 agent 自管；admin 重新登录（B5 后已含）
- 走查：详情弹窗图与高亮（三态各一单）→ 定义页查看图 → 设计器编辑原样重部署（version 递增 + 防呆文案可见）→ 新建流程导入本地文件 → 坏文件部署 toast 4009 → 工作台两卡与待办/申请页数据一致 → 无 deploy 权限视角（若有第二账号；无则以下轮 e2e 为准）
- 验收：与 B5 curl 形态一致

## e2e 章（cloud-e2e/，E1 在 B3 后先行；E2 起需 B5+F7）

### E1【前置】种子与既有断言迁移（3 文件改 + 2 文件核对记档）

- 文件 1 `cloud-e2e/run-menu-e2e.mjs`：SEED_MENU_IDS 数组 +`'331'`（30→31 项；注释互指 run-role-e2e R5a 同步义务）；CLEANUP `>= SEED_MENU_IDS.length` 断言随常量自动放宽（31），文案中「30」表述同步
- 文件 2 `cloud-e2e/run-role-e2e.mjs`：R5a SEED_MENU_IDS +`'331'`（31 项）；「恰 30」断言与文案 →「恰 31」（R5a 语义=绑定 id 全量精确，admin 绑定是封闭集）
- 文件 3 `cloud-e2e/run-bpmn-e2e.mjs` 既有断言迁移（设计 D8，**必红点不改必挂**）：
  - BP6 :546 表头断言 `'定义标识,定义名称,版本,部署时间'` → 5 列（+`'操作'` 末位）；版本断言 `/^\d+$/` 本就宽松零改动
  - BP8 「定义页无写按钮（表头 4 列无操作列 + 零按钮）」子断言 → 反转：admin（331 绑定 + B5 重登录后）定义页**可见**「新建流程」按钮与操作列（查看图/设计）
  - 头注释场景描述同步
- **零迁移核对记档（备忘 13 结论写进两脚本头注释或 E3 记录）**：run-nav-e2e 零改动——331 为 F 节点不进导航树，N1 根级形状与侧边精确串不变；run-dict-e2e 零改动——无新字典，SEED_KEYS 与侧边串不变
- 验收：B3 落库后 menu/role 单跑绿；bpmn 脚本 BP1-BP8 迁移后形态待 E2 一并跑

### E2 BP9-BP12 新增场景（run-bpmn-e2e.mjs）

- BP9 详情流程图：BP7 后同弹窗（或重开 A 单详情）断言 `.djs-container svg` 可见 + 主高亮类节点 ≥1（endApprove 态）；审批中单（重开一单）断言 approval 高亮
- BP10 定义页查看图：「查看图」弹窗 svg 渲染（leave_approval 名/节点可见）+ 关闭
- BP11 设计器部署：「设计」打开 leave_approval → Modeler 加载（画布元素 ≥1）→ XML 源码 tab 含 `leave_approval` → 不改动「保存部署」→ toast/列表刷新后断言 version 较打开前 +1（页内 fetch `/api/bpmn/definition/page` 或 UI 版本列）
- BP12 工作台：goto /dashboard 两卡标题可见（我的待办/我的申请）+ 待办卡行数 = min(5, 页内 fetch /task/todo 长度) + 我的申请卡含本轮 stamp 标题 + 两「查看全部」跳转 URL 断言；**无权限隐藏不建受限账号**（沿备忘 12 先例，契约 §6 记档）——改为断言卡片容器带 v-perms 移除语义可选（DOM 不存在即过），实现时取简
- CLEANUP/VERIFY 既有口径不变（本轮 stamp 全终态）+ 部署 v2+ 残留允许（D9 纪律，头注释补一行）
- 验收：`node run-bpmn-e2e.mjs` 全绿（B5+F7 后）

### E3 八脚本全量回归

- 命令：`cd cloud-e2e && npm run e2e`（有头全量；package.json e2e 串行清单无需改——bpmn 脚本已在）
- 核对：七既有脚本零回归（menu/role 按 E1 新口径；nav/dict 零改动应绿——E1 核对结论兜底）；BP1-BP12 全绿；种子终态（admin/31 菜单/4 内置字典/user_status 原样）
- 验收：八脚本全绿 + 结果记录

### E4 文档同步核对

- 核对：本计划/契约/设计三文档与实现一致（4008/4009 msg 逐字、331 种子行、LeaveDiagramVo 字段、分包产物）；`docs/superpowers/` 无遗留 TODO；git diff 核对 common 零触碰、package.json 增量恰两包
- 验收：无差异或差异已回报主控修订

## 移交后续阶段的备忘

1. **连线（sequence flow）高亮**：**已关闭（E4 白得）**——completedActivityIds 实测含 flow id（ACT_HI_ACTINST 对 flow 有记录），前端全量传 Viewer 即连线着色（设计 D2 修正记档），无需另案
2. **设计器属性面板定制**：properties-panel 默认面板；自定义分组（中文标签/审批人字段直填 flowable:assignee）属体验增强另案（D5）
3. **表达式静态扫描/白名单**：JUEL 注入面当前靠权限闸（admin-only）；多租户/开放部署前须评估（D6）
4. **bpmn-js 版本升级窗口**：^18 锁定；升级时 F6 分包核对回归
5. **R4 撤销态历史形态实测定型**：B5 curl 记档结论回填设计文档（若 endActivityId 实测有值，契约 §3 撤销行同步修订——主控出口）
6. **工作台两卡刷新策略**：当前进页拉取；待办实时推送（轮询/SSE）另案
7. **设计器 P1 修复记档（F7 发现）**：初版 `v-if="booting"` 与 canvasRef 互斥致画布永不渲染（v-if 卸载期间 ref 悬空，Modeler 挂载时机错过）——F6 build 冒烟未覆盖设计器深链路漏网；修复=容器常驻 + v-loading（E4 回流记档，后续同类弹窗画布组件沿此模式）
8. **e2e 残留版本形态**：leave_approval 定义已至 v7（历轮部署累积）——D9 残留纪律允许，latestVersion 过滤下 UI 恒显最新版，行为零变化；后续轮 BP6 版本断言维持 /^\d+$/ 宽松口径
9. **S15 boot-401 回跳竞态修复记档（E3 发现/F7 后收口）**：request.ts redirectToLogin 增 vue-router START_LOCATION 哨兵早退——首导航未提交时只清态不抢跳，深链回跳收敛为守卫单路带 to.fullPath；修因=Round H dashboard 重写扩大初始模块图使隐伏竞态转确定性；常规 401 分支零改动（build×2 + 三路径手测 + run-e2e 18/18 含 S15 复绿全证）

## 给 backend-agent / frontend-agent / e2e 的任务清单（可粘发）

- **backend（B1→B5 串行）**：B1 三 VO + BpmnLeaveManageService.findDiagram（businessKey 历史锚点+三态矩阵+防御态）+ DefinitionAppService.findXmlById/saveDeployment（前置校验 4009/引擎异常 4009/getProcessModel R2 验证）+ application.yml +multipart 3MB/4MB（本地，D4 三段式）+ exception/MultipartLimitExceptionHandler（≥3MB 解析层归口 4009，common 零触碰）+ 单测全 case；B2 三端点（xml perms list / deploy multipart+RequestPart perms bpmn:definition:deploy / diagram perms leave:list，两行式+javadoc+显式命名）+ controller 单测 + handler 直测；B3 种子 331 增量（**FROM DUAL**，5.7 兼容）+基线+java 通路落库回查（幂等含墓碑）；B4 全量构建（守护绿+绑定计数不变+common 零触碰）；B5【卡点】用户重启 9203+admin 重登+curl 五组（xml/diagram 三态/原样重部署 v+1/4008+4009 四路含 2.5MB 业务段与 3.5MB 解析段/403+401）
- **frontend（F1→F7）**：F1 npm 恰两包（bpmn-js ^18+properties-panel，R1 验证）+三类型+三 api 函数（deploy FormData）；F2 BpmnViewer 公共组件（NavigatedViewer dynamic import+marker 双高亮+fit-viewport+CSS 随 chunk+R5 验证）；F3 详情弹窗 +860px 图区（diagram→xml 串行，三态高亮，防御态隐藏）；F4 定义页操作列+新建按钮（v-perm deploy）+查看图弹窗+设计器弹窗（Modeler+properties-panel+XML 源码 tab+导入文件+保存部署+防呆 alert，全 dynamic import）；F5 工作台两卡（复用 todo/leave page 前 5+v-perms 整卡隐藏+空态+查看全部跳转）；F6 双 build+分包核对（bpmn 独立 chunk+主包不增）；F7 联调（B5 后）
- **e2e（E1 B3 后 → E2 B5+F7 后 → E3 → E4）**：E1 menu/role SEED +331（31 项）+ BP6 表头 5 列/BP8 写按钮反转迁移 + nav/dict 零迁移记档；E2 BP9-BP12（详情图高亮/查看图/原样重部署 version+1/工作台两卡）；E3 八脚本全量；E4 文档核对
- 红线：契约 additive-only（4001-4007 零变化）；删除/挂起/激活端点永不做；npm 增量恰两包；分包铁律；common 零触碰；9203 用户启停；e2e 黑盒 e2ebpmn 前缀；v2+ 定义残留允许
