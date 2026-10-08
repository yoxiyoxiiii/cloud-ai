# bpmn 流程图渲染 + 在线设计器 + 工作台两卡 技术方案（Round H）

- 日期：2026-10-08
- 需求（用户拍板口径）：①流程图渲染走 bpmn-js NavigatedViewer（npm 例外获批），消费位两处——请假详情弹窗（高亮进度）+ 流程定义页（查看图），公共 Viewer 组件；②在线设计器走完整 bpmn-js Modeler + properties-panel，API 面仅「看 XML + 部署」（不做挂起/激活，删除永不做）；③工作台两卡（我的待办 + 我的申请最近列表，用户扩大范围项，仍不加统计卡）；④一轮全做
- 契约：`docs/superpowers/contracts/2026-10-08-bpmn-diagram-designer-api.md`（对 bpmn-leave-api v1 additive；4008/4009 新权威）
- 计划：`docs/superpowers/plans/2026-10-08-bpmn-diagram-designer-workbench.md`
- 阶段一需求分析与拍板结论已归主控记录；本设计只记拍板后决策（D 编号）。

## 1. 架构总览（文字架构图）

```
cloud-web(5173)
├─ views/bpmn/components/BpmnViewer.vue      ← 新：公共只读渲染组件（NavigatedViewer dynamic import 独立 chunk）
│    ├─ LeaveDetailDialog 内嵌（详情图：diagram→xml 两调用 + marker 高亮）
│    └─ definition 页「查看流程图」弹窗内嵌（仅 xml，无高亮）
├─ views/bpmn/definition/components/
│    ├─ DefinitionDiagramDialog.vue          ← 新：查看图弹窗（xml→Viewer）
│    └─ DefinitionDesignerDialog.vue         ← 新：设计器弹窗（Modeler+properties-panel+XML 源码 tab，
│                                              编辑回填/本地文件导入/保存部署，dynamic import 独立 chunk）
├─ views/dashboard/index.vue                 ← 改：占位页 → 两卡布局（我的待办 + 我的申请）
│    （数据源既有端点 GET /bpmn/task/todo 前 5 + GET /bpmn/leave/page 前 5；v-perms 整卡隐藏）
└─ api/bpmn.ts + types/api.ts                ← additive：三函数三类型

cloud-gateway(18080) ── /bpmn/** ──lb──> cloud-bpmn(9203)   ← 新增三端点（既有 8 端点零变化）
├─ LeaveController        + GET /leave/{id}/diagram    （BpmnLeaveManageService.findDiagram）
├─ DefinitionController   + GET /definition/{id}/xml    （DefinitionAppService.findXmlById）
│                         + POST /definition/deploy     （DefinitionAppService.saveDeployment，multipart）
├─ application.yml（本地）+ spring.servlet.multipart 3MB/4MB；exception/MultipartLimitExceptionHandler（≥3MB 解析层归口 4009，D4 三段式）
└─ 引擎面：RuntimeService.getActiveActivityIds / HistoryService（历史实例+历史活动）
           / RepositoryService（getProcessModel 原始 XML / createDeployment）

种子：sys_menu +331（F，bpmn:definition:deploy 挂 33 下，admin 绑定）→ menu/role 两 e2e 脚本迁移义务
npm 例外：bpmn-js@^18 + bpmn-js-properties-panel（D1 记档）
```

**核心数据流**：
详情图：详情弹窗打开 → `GET /leave/{id}/diagram`（businessKey 锚点查历史实例 → definitionId + 四字段高亮数据）→ `GET /definition/{definitionId}/xml`（原始资源）→ NavigatedViewer.importXML → canvas.addMarker 着色（契约 §3 三态矩阵）。
部署：设计器保存（Modeler.saveXML → Blob，或本地 .bpmn 文件）→ FormData → `POST /definition/deploy` → 引擎部署（同 key version+1）→ 刷新定义列表 + toast「部署成功 {key} v{n}」；在途实例继续走旧版本（引擎原生，UI 防呆文案明示）。
工作台：进入 /dashboard → 并行 `GET /task/todo`（slice 5）+ `GET /leave/page`（1×5）→ 两卡渲染；无对应 perms 整卡隐藏。

## 2. 决策记录

### D1 npm 例外记档（拍板项，红线例外而非放开）

- 本轮新增 npm 依赖恰两包：`bpmn-js@^18`（NavigatedViewer 渲染 + Modeler 设计器，同包两种入口）与 `bpmn-js-properties-panel`（属性面板，peer 兼容版本实现时验证锁定）。
- **例外范围限定**：仅用于 bpmn 流程图渲染与在线设计器；不因此放开「零新增 npm 依赖」红线——后续任何新增依赖仍走例外审批。
- **分包铁律**：两包一律经 dynamic import 进入 async chunk，不进主包——BpmnViewer 组件内 `await import('bpmn-js/lib/NavigatedViewer')`，DesignerDialog 内 `await import('bpmn-js/lib/Modeler')` + `await import('bpmn-js-properties-panel')`；**组件本身在被消费处用 defineAsyncComponent 引入**（含其静态 import 的 bpmn CSS 一并入 async chunk——CSS 若随静态链进主包即违背分包目的）。验收=build 产物出现 bpmn 独立 js/css chunk 且 index 主包体积无显著增长（计划 F6）。
- 参考量级：NavigatedViewer ~194KB min、Modeler ~420KB min（bpmn.io 官方 dist；路由级分包下按需加载）。

### D2 渲染方案（NavigatedViewer + marker 高亮）

- 只读渲染选 `bpmn-js/lib/NavigatedViewer`（Viewer + 画布缩放/平移模块，比 Modeler 小一半）——滚轮缩放、拖拽平移开箱即用，MVP 不加自定义工具条。
- 高亮实现：`canvas.addMarker(elementId, cls)` + 自定义 CSS 两类——`.bpmn-highlight-active`（主高亮：当前节点/结束节点，绿色描边填充）与 `.bpmn-highlight-completed`（浅色：已执行路径节点）。挂 marker 的 id 不存在时 addMarker 静默无害（completedActivityIds 含网关/事件节点，Viewer 全量元素可命中）。
- 连线浅色高亮**白得**（E4 实测回流修正原前提）：completedActivityIds 实测**含 sequenceFlow id**（ACT_HI_ACTINST 对 flow 亦有记录，如 flowStart/flowApprove）——前端全量传 Viewer，addMarker 作用于 flow 元素即连线着色（F7 视觉已核）。原「MVP 不做（只含活动节点）」前提不成立，关闭移交项。
- CSS 引入：`bpmn-js/dist/assets/diagram-js.css` + `bpmn-js/dist/assets/bpmn-font/css/bpmn.css` 在 BpmnViewer 组件内静态 import（随 async chunk 分包）；高亮样式写在组件 scoped/全局层（diagram-js 元素在影子 DOM 外，marker 类需全局样式——实现时验证 scoped 是否命中，不命中则非 scoped style 块）。
- importXML 后调 `canvas.zoom('fit-viewport', null)` 自适应容器。

### D3 高亮数据源定夺（契约面已定，此处记实现依据）

- 锚点：`businessKey = leaveId` 查 `HistoricProcessInstanceQuery.processInstanceBusinessKey(...)` 单查——历史表三态（运行中/已结束/已撤销）均有痕，不碰 bpmn_leave.process_instance_id（撤销后 null）。
- 四字段语义（契约 §3 三态矩阵为前端渲染权威）：
  - activeActivityIds：先判运行中（`runtimeService.createProcessInstanceQuery().processInstanceId(pid).count() > 0`）再取 `getActiveActivityIds(pid)`——防御实例已结束仍调该 API 的未定义行为；
  - completedActivityIds：`createHistoricActivityInstanceQuery().processInstanceId(pid).orderByHistoricActivityInstanceStartTime().asc().list()` 取 activityId LinkedHashSet 去重；
  - endActivityId：历史实例 `getEndActivityId()`（审批中/撤销 null——**撤销态实测定型（B5/E4 回填，R4 已闭）**：endActivityId=null、completedActivityIds 截断至删除点，实测 `[start,flowStart,approval]`，实例于 approval 任务处被删）。
- 历史实例缺失（防御态）：definitionId=null + 空数组直返，前端隐藏图区——不设新错误码（契约 §3）。

### D4 部署端点（multipart 定夺 + 校验链）

- **multipart 而非 JSON body 的理由**：①BPMN XML 大概率含中文（流程名/节点名），JSON body 内嵌中文字符串在 Git Bash curl 下经 shell 编码为 GBK 必 500（CLAUDE.md 陷阱）；multipart `-F 'file=@x.xml'` 文件按 UTF-8 字节原样传输，天然免疫；②前端两条路径同构（Modeler saveXML→Blob→FormData 与本地 .bpmn 文件上传）；③未来多资源文件扩展位。
- **大小上限三段式（4009 形态闭合，主控审查修订）**：
  1. **Service 前置业务上限 2MB**（file null/empty → 4009；size > 2MB → 4009）——覆盖 0-3MB 全区间的业务语义层；
  2. **Spring multipart 解析层**：cloud-bpmn 本地 `application.yml` 显式 `spring.servlet.multipart.max-file-size: 3MB` / `max-request-size: 4MB`。**本地而非 Nacos 定夺**：与业务上限强绑定、跟代码走；审查实锤——全仓零 multipart 配置，Boot 默认 max-file-size=1MB 会使 1-2MB 合法文件死在解析层（MaxUploadSizeExceededException）且观测形态非 4009。解析层只兜 ≥3MB 极端值；
  3. **解析层异常归口**：cloud-bpmn 本地 `@RestControllerAdvice` + `@ExceptionHandler(MaxUploadSizeExceededException.class)` → `R.fail(4009, "流程文件无效或部署失败")`（`R.fail(int, String)` 重载既有；HTTP 200 + body 4009；**common GlobalExceptionHandler 零触碰红线**）——全尺寸域超限观测形态恒 4009，B5 curl 两段（2.5MB/3.5MB）分别验证。
- 校验链（Service 前置，顺序）：file null/empty → 4009；size > 2MB → 4009；`createDeployment().addInputStream(文件名, 流).deploy()` 包 try-catch，FlowableException（解析/schema/无 process 定义）→ log.error 根因 → 4009（msg 固定文案，不透传引擎文案）。
- 成功路径：部署后 `createProcessDefinitionQuery().deploymentId(deploymentId).list()` 组装 DeployResultVo（DefinitionVo 复用，不做 latestVersion 过滤——返回的就是本次产生的版本）。
- 事务：deploy 为引擎单操作、不涉业务表，不加 @Transactional（规范「多表写」口径不适用）。
- Controller 形态：`@PostMapping(value = "/deploy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)` + `@RequestPart("file") MultipartFile file`——MultipartFile 为框架类型非 Map，DTO 规范不冲突（类比 PageQuery）。

### D5 设计器形态（Modeler + properties-panel + XML 源码 tab）

- 弹窗 `DefinitionDesignerDialog`（宽 ~1100px 或 fullscreen）：左 canvas（Modeler）+ 右 properties-panel + 顶部三 tab 或并置——MVP 定**双 tab：「流程图」（Modeler+属性面板）/「XML 源码」（只读 pre 展示当前 XML，兼作「看 XML」消费位）**。
- 两种打开态：①「新建流程」按钮（空画布，可「导入本地文件」按钮选 .bpmn/.xml 回填）；②行操作「设计」（GET /definition/{id}/xml 回填 Modeler，编辑现有流程）。
- 保存部署：Modeler `saveXML({format:true})` → Blob → `deployDefinition(file)`；XML tab 态也可直接部署当前文本（Blob 从文本构造）——两态同一出口。
- 防呆文案（dialog 顶部 info 条常驻）：「新版本仅对新发起的流程生效，在途流程继续走原版本」。
- 权限：新建/设计/部署按钮 v-perm `bpmn:definition:deploy`（XML 查看/查看图走 list 权限）。
- properties-panel 接入（**E4 实装形态修正**：`attachTo({modeler, container})` 为 v1 形态，实装 v5.65.1 用 **additionalModules 注册两 Module + Modeler 构造项 `propertiesPanel: { parent }`**——等价实现，不涉契约）：MVP 用默认面板（通用属性+flowable 扩展属性如 assignee 在 documentation/extension 区编辑）——不定制自定义分组（记移交）。

### D6 安全面（部署端点 = 高权限操作）

- **JUEL 注入面**：BPMN `${...}` 表达式运行时可执行任意 Java 方法——缓解 ①perms bpmn:definition:deploy 种子仅绑 admin；②大小 2MB；③引擎 schema 校验；④内网管理端定位。不引入表达式静态扫描（白名单校验器属过度设计，记移交可选）。
- **XXE（E4 实证收口，R3 已闭·结论 A）**：默认防护闭合，D6 原预案（deploy 入口前置 secure 解析）判定不需要。实证双链：①静态——flowable-engine 7.2.0 部署主链 BpmnXMLConverter（flowable-bpmn-converter）用加固 StAX，字节码实证 XMLInputFactory 三属性显式 false（isReplacingEntityReferences / isSupportingExternalEntities / supportDTD），无任何 setProperty 放开路径；validateModel 走 XSD Validator 非 DOM/SAX 无实体展开面；②动态——DOCTYPE+外部实体探针（file:/// win.ini）实测被引擎解析层拒绝（XMLStreamException → 4009 固定文案），响应零泄漏特征、ACT_RE 三表零残留（事务完整回滚）。**结论绑定 flowable 7.2.0——升级时须重跑探针复核**（探针要点：multipart 部署 xxe.bpmn20.xml + 断言 4009 + DB 三表零行）。
- 版本语义防呆：引擎原生在途走旧版——UI 文案明示（D5），不提供任何「迁移在途实例」能力（永不做删除/cascade）。
- multipart 经网关纯透传（WebFlux 路由不解析 body），无中间层风险。

### D7 工作台两卡（拍板扩大项）

- `dashboard/index.vue` 重写为两卡布局（el-row gutter / el-col 各半，窄屏堆叠）：**我的待办**（TaskVo 行：标题/类型译文降级链/申请人/时间，前 5 条 slice；「查看全部」→ `/bpmn/task`）与**我的申请**（LeaveVo 行：标题/状态 tag（LEAVE_STATUS_TAG 同款映射）/发起时间，前 5 条；「查看全部」→ `/bpmn/leave`）。
- 两请求 `Promise.all` 并行；单卡请求失败留空态（拦截器 toast）。
- 降级语义：卡片容器 v-perm 整卡隐藏（el-card 单根组件，指令作用于根元素移除即整卡消失——v-perms 既定语义）；有权限空数据 → el-empty 空态文案（契约 §6）。
- 工作台静态语义不动：恒可见兜底、侧边尾挂、不在 sys_menu——两卡是页内按权限显隐的局部。

### D8 菜单种子 331 + e2e 迁移义务

- 种子：契约 §5（331 F 节点 + admin 绑定增量 + 基线同步）；幂等核对含墓碑（30 段先例）。
- e2e 迁移（备忘 13 义务，计划 E1）：run-menu-e2e SEED_MENU_IDS 30→31（+331）；run-role-e2e R5a 31 项精确同步；**nav/dict 零迁移核对结论**：331 为 F 节点不进导航树/侧边串（nav 精确串不变）、无新字典（SEED_KEYS 不变）——两脚本零改动，E3 回归兜底。
- **BP6/BP8 既有断言反转迁移**（审查发现，必迁）：BP6 表头断言「4 列精确无操作列」→ 5 列（+操作列）；BP8「定义页无写按钮（零按钮）」→ admin 登录态可见「新建流程」按钮（331 绑 admin 后语义反转）——两处不改必红。

### D9 e2e 新场景与残留纪律

- BP9 详情流程图：已通过单详情弹窗 `.djs-container svg` 存在 + 主高亮节点类存在（endApprove）。
- BP10 定义页查看图：弹窗 svg 渲染 + 关闭。
- BP11 设计器部署：编辑 leave_approval → 不改动原样「保存部署」→ 断言 version 递增（页内 fetch /definition/page 或 UI 版本列）——**画布拖拽不进 e2e**（成本不成比例），XML tab 可断言内容含 leave_approval。
- BP12 工作台：admin /dashboard 两卡可见 + 待办卡行数与页内 fetch /task/todo 一致 + 我的申请卡含本轮 stamp 标题 + 查看全部跳转 URL 断言；**无权限隐藏不建第二 bpmn 受限账号**（沿备忘 12 先例：v-perms 整卡显隐为简单前端逻辑风险低，单侧保证 + 契约 §6 记档）。
- 残留纪律：e2e/验收部署产生的 v2+ 定义允许残留（latestVersion 过滤下 UI 恒显最新版；原样重部署语义零变化）；不做定义删除。

## 3. 错误处理

- 4008/4009 见契约 §4；引擎异常 catch → log.error 根因 → BusinessException（4008 语义场景=FlowableObjectNotFoundException，4009=部署链 FlowableException/校验）——不透传引擎栈与英文文案。
- diagram 防御态（历史缺失）不抛错——空 VO 前端隐藏图区（D3）。
- 前端：xml/diagram/deploy 失败走拦截器统一 toast；Viewer importXML 解析失败（理论不发生——XML 出自引擎）catch 留图区空态。

## 4. 测试策略

| 层 | 内容 | 位置 |
|---|---|---|
| Service 单测 | findDiagram 三态矩阵（mock 引擎 API）+ 4001 + 防御态；findXmlById 4008/正常；saveDeployment 空文件/超限/引擎异常 4009 + 成功路径 | cloud-bpmn test |
| Controller 单测 | 三新端点委托 verify + @PreAuthorize 注解存在性 | cloud-bpmn test |
| 守护测试 | ArchitectureGuardTest/MapperXmlBindingTest 既有绿（无新 mapper 语句，绑定计数 4 不变） | cloud-bpmn test |
| curl 卡点 | 契约 §8 清单（用户起 9203） | 计划 B5 |
| 前端 | 双 build 绿 + 分包验证（bpmn 独立 chunk、主包不增） | 计划 F6 |
| e2e | BP9-BP12 + BP6/BP8 迁移 + 八脚本全量 | 计划 E 章 |

## 5. 风险与未知

| # | 风险 | 消解路径 | 状态 |
|---|---|---|---|
| R1 | bpmn-js properties-panel peer 版本匹配 | F1 安装时验证锁定，冲突即回报 | 计划内 |
| R2 | `repositoryService.getProcessModel` 7.2 API 形态 | B1 实现时验证（备选 BpmnModel+BpmnXMLConverter 往返，注释丢失记档） | 实现时验证 |
| R3 | Flowable XML 解析 XXE 默认防护状态 | **已闭（结论 A：默认防护闭合，D6 预案判定不需要）**——flowable-engine 7.2.0 部署主链 BpmnXMLConverter（flowable-bpmn-converter）用加固 StAX：字节码实证 XMLInputFactory 三属性显式 false（isReplacingEntityReferences / isSupportingExternalEntities / supportDTD），无任何 setProperty 放开路径；validateModel 走 XSD Validator 非 DOM/SAX 无实体展开面。**结论绑定 flowable 7.2.0——升级时须重跑探针复核**（探针要点见 D6） | 已闭（实证收口） |
| R4 | 撤销态历史实例形态（endActivityId/已执行活动截断点） | B5 curl 实测定型（E4 回填：endActivityId=null、completedActivityIds 截断至删除点 `[start,flowStart,approval]`） | 已闭（B5 实测定型） |
| R5 | marker CSS scoped 命中（diagram-js 元素选择器） | D2 实现时验证，不命中转全局 style 块 | 实现时验证 |
| R6 | e2e 部署残留影响后续轮断言 | BP6 版本断言本就宽松（/^\d+$/）；total 不锁；残留记档 D9 | 已闭 |
| R7 | multipart 大文件经网关超时 | 上限 2MB + 内网环境，不构成风险 | 已闭 |
