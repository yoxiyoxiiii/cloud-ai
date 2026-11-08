# 站内消息通知中心实施计划

- 日期：2026-11-08
- 设计：`docs/superpowers/specs/2026-11-08-notify-center-design.md`（下称"设计"）
- 契约：`docs/superpowers/contracts/2026-11-08-notify-api.md`（下称"契约"——前后端唯一对齐物）
- 轨道：全栈（后端章与前端章**独立可并行**；前端以契约为对齐物 + e2e route mock 先行，不依赖后端代码）

> 阅读提示：本文写给第一次接触本计划的实现 agent。每个任务的"要点"已含关键决策，不需要回读需求分析；细节分歧以契约为准。

## 后端章（cloud-system，B1→B7 章序执行）

### B1 三表 DDL + 种子 + 基线同步

**文件清单**：
- 新建 `cloud-base/scripts/sql/2026-11-08-notify-center.sql`：四段——①三表 DDL（设计 §2.1 原文，索引齐备）②内置模板种子 2 条（`welcome_notice` 公告无变量 / `approval_remind` 提醒含 `${nickname}`，均 isBuiltin=1）③菜单种子 19/196/197/198/199 + admin 角色绑定（契约 §5）④xxl-job 任务种子（USE xxl_job，id 11、组 3、handler `notifyCleanJobHandler`、CRON `0 40 3 * * ?`、trigger_status 1——沿 2026-10-10-mq-clean-xxl-job.sql 形态）
- 修改 `cloud-base/scripts/sql/cloud_system.sql`：基线同步三表 DDL 段 + 菜单 19 段 + 内置模板（沿 ai-rag 种子头注的"基线同步"口径）

**要点**：头注写前置断言（三表不存在、`SELECT id FROM sys_menu WHERE id IN (19,196,197,198,199)` 应 0 行含墓碑、`SELECT id FROM xxl_job.xxl_job_info WHERE id=11` 应 0 行）与不可重放口径（固定主键）。菜单头注带"落库后 admin 须重新登录进权限快照"提示（沿先例）。

**验收**：pymysql（`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python <脚本>`，charset=utf8mb4 参数化）重放成功且前置断言全过；重查 sys_menu 19 段 5 行、sys_notify_template 2 行、xxl_job_info id 11 存在。

### B2 实体 + mapper（三表 + SysUserMapper 两查询）

**文件清单**：
- 新建 `entity/SysNotifyTemplate.java`（内嵌 `TypeEnum{NOTICE(1),REMIND(2)}`、`LevelEnum{NORMAL(0),IMPORTANT(1),URGENT(2)}`，继承 BaseEntity）、`entity/SysNotifyMessage.java`（内嵌 `ReceiverTypeEnum{USER(1),ROLE(2),ALL(3)}`；type/level 字段 Integer、常量引用 SysNotifyTemplate 枚举）、`entity/SysNotifyUserMessage.java`（内嵌 `ReadFlagEnum{UNREAD(0),READ(1)}`；**不继承 BaseEntity**——无 deleted 列，审计字段自带；SysNotifyMessage 同理不继承）
- 新建 `mapper/SysNotifyTemplateMapper.java` + `resources/mapper/SysNotifyTemplateMapper.xml`：`countByCode(code, excludeId)` 查重 / `insertTemplate` / `updateTemplate`（trim+if 动态列）/ `deleteTemplate`（UPDATE deleted=1 + update 审计两值）/ `findById`（deleted=0）/ `pageListTemplate(Page, query)`（name/code 模糊 + type 精确，deleted=0）
- 新建 `mapper/SysNotifyMessageMapper.java` + xml：`insertMessage` / `deleteOrphanMessages`（NOT EXISTS 收件行，清理用）
- 新建 `mapper/SysNotifyUserMessageMapper.java` + xml：`batchInsert`（foreach）/ `countUnread(userId)` / `pageListUserMessage(Page, query)`（JOIN 主表取 title/content/type/level/link_url + 主表 create_time AS send_time，按 um.id DESC）/ `findByIdAndUser(id, userId)` / `updateRead(id, userId, account)`（WHERE read_flag=0 条件写）/ `updateReadAll(userId, account)`（返回影响行数）/ `deleteReadBefore(cutoff)`（清理）
- 修改 `mapper/SysUserMapper.java` + xml：增 `listActiveUserIds()`（status=0 AND deleted=0）、`listUserIdsByRoleIds(roleIds)`（JOIN sys_user_role + sys_user 过滤有效，DISTINCT）

**要点**：全部手写 SQL + `#{}`；XML `<if>` 标签体换行；每列审计时间 SQL `NOW()`；分页方法不写 LIMIT（分页插件）；两无 deleted 流水表的查询无 deleted 条件（设计 §2.3 取舍）。

**验收**：`mvn -f cloud-base/pom.xml test -pl cloud-system -am` 编译与既有守护测试全绿（新 mapper 被守护规则自动覆盖：${}/单行 if/Wrapper/BaseMapper 禁用）。

### B3 模板管理（CRUD）

**文件清单**：
- 新建 `dto/NotifyTemplateAddRequest.java`、`dto/NotifyTemplateEditRequest.java`（校验注解按契约 §1.3/§1.4）、`vo/NotifyTemplateVo.java`
- 新建 `convert/NotifyConvert.java`（静态 toTemplateVo，原生 setter 逐字段）
- 新建 `service/NotifyTemplateManageService.java`：saveXxx/updateXxx/pageListXxx/findXxx/deleteXxx（命名动词集）；查重 3043（countByCode + DuplicateKeyException 兜底同码）、不存在 3044、内置禁删 3049；操作人 SecurityUtils.currentAccount()
- 新建 `controller/manage/NotifyTemplateManageController.java`：`@RequestMapping("/notify/template")` 五端点（契约 §1），两行式返回、@PathVariable 显式、@PreAuthorize 逐端点
- 新建 `src/test/java/.../NotifyTemplateManageServiceTest.java`

**验收**：单测覆盖 3043（新增/修改撞 code、墓碑占键）、3044、3049、软删审计两值；全量测试绿。

### B4 发送门面（解析 + 展开 + 事务）

**文件清单**：
- 新建 `util/NotifyTemplateParser.java`：纯函数 `extractVariables(title, content)`（正则 `\$\{([a-zA-Z][a-zA-Z0-9_]*)\}`，LinkedHashSet 保序）+ `render(text, variables)`；静态无状态
- 新建 `dto/NotifySendRequest.java`（含 `Map<String,String> variables` 字段——DTO 内嵌受控 Map 裁量见设计 §8）、`vo/NotifySendResultVo.java`
- 新建 `service/NotifySendService.java`：`send(NotifySendRequest)` 门面，流程=设计 §4 七步；`@Transactional(rollbackFor = Exception.class)`；批量分批 500；DuplicateKeyException 吸收；receiverType 条件校验 1001、linkUrl `/` 开头 1001；3044→3045→3046→3047 顺序校验
- 新建 `controller/manage/NotifySendManageController.java`：`POST /notify/send`（契约 §2.1，perms system:notify:send）
- 新建测试 `NotifyTemplateParserTest.java`、`NotifySendServiceTest.java`

**验收**：单测覆盖 parser 全分支（提取/替换/缺失/多余/超长）、三档展开（无效用户过滤、ROLE 去重、ALL）、3044/3045/3046/3047、分批调用次数（N>500 场景）、事务注解存在（反射断言）；全量测试绿。

### B5 用户侧（轮询 + 列表 + 已读）

**文件清单**：
- 新建 `dto/NotifyUserMessagePageQuery.java`、`vo/NotifySummaryVo.java`、`vo/NotifyUserMessageVo.java`
- 新建 `service/NotifyUserMessageService.java`：`findSummary(userId)`（countUnread + MAX id）、`pageListXxx`、`updateRead(id, userId)`（先 findByIdAndUser 归属校验→3048，再条件 UPDATE，0 行=已读幂等成功）、`updateReadAll(userId)` 返回影响数
- 新建 `controller/NotifyUserController.java`：`@RequestMapping("/notify")` 四端点 summary/page/read/read-all（契约 §3）——**不加 @PreAuthorize（登录即可，守护无此机械规则已核）；当前用户取 SecurityUtils.currentUser().userId()**
- 新建测试 `NotifyUserMessageServiceTest.java`

**验收**：单测覆盖 3048（不存在与非本人同文案）、幂等、read-all 计数；全量测试绿。

### B6 xxl-job 清理 + 配置

**文件清单**：
- 新建 `config/CloudSystemNotifyProperties.java`（@ConfigurationProperties("cloud.system.notify")，retentionDays 默认 30——沿 CloudSystemMqDlqProperties 形态）
- 新建 `job/NotifyCleanJobHandler.java`：@XxlJob("notifyCleanJobHandler") 两步删（设计 §6；行数 log.info，不抛即成功）
- 修改 `cloud-system/src/main/resources/application.yml`：`cloud.system.notify.retention-days: 30`

**验收**：编译绿；handler 名与 B1 种子 id 11 的 executor_handler 一致；@XxlJob 类落 job/ 包（守护规则）。

### B7 后端全量验收

**验收标准**：
1. `mvn -f cloud-base/pom.xml clean install -pl cloud-system -am` 全绿（含守护）。
2. 真链路冒烟（主控验收段联动）：起 nacos/mysql/redis → sso → system → gateway → pymysql 重放 B1 SQL → curl 经网关：admin 登录 → 发全员消息（契约 §2.1 请求体）→ 断言 messageId/receiverCount → 另一用户登录 GET summary 断言 unreadCount≥1、latestId>0 → read/{id} 后 summary 归零减一 → read-all → 留档冒烟输出。curl 中文 JSON 是 GBK 坑——中文入参用 ASCII 模板内容或转码（CLAUDE.md 陷阱）。

## 前端章（cloud-web，F1→F5；契约 §1-§3 为唯一对齐物，e2e 全程 route mock 可先行）

### F1 类型与 api 封装

**文件清单**：
- 修改 `src/types/api.ts`：增 NotifyTemplateVo / NotifyTemplatePageQuery / NotifyTemplateSavePayload / NotifySendRequest / NotifySendResultVo / NotifySummaryVo / NotifyUserMessageVo / NotifyUserMessagePageQuery（字段逐一对齐契约；id 类字段全 string）
- 新建 `src/api/notify.ts`：十函数（pageTemplate/getTemplate/addTemplate/editTemplate/removeTemplate/sendNotify/getNotifySummary/pageMyNotify/readNotify/readNotifyAll），沿 `api/mqDlq.ts` 风格（函数注释标契约节号与 perms；request 统一封装）

**验收**：`vue-tsc --noEmit` 零错误。

### F2 通知管理页（模板 CRUD + 发送）

**文件清单**：
- 新建 `src/views/system/notify/index.vue`：ProTable 模板列表（列：code/name/type/level/isBuiltin/createBy/createTime + 操作）；查询区（name/code 模糊 + type 下拉）；按钮 v-perms 控制（system:notify:add/edit/remove/send）
- 新建 `src/views/system/notify/components/TemplateEditDialog.vue`：新增/编辑共用弹窗（编辑回显 detail；isBuiltin 仅文案提示"内置模板：禁删可改"）
- 新建 `src/views/system/notify/components/SendNotifyDialog.vue`：三步单弹窗——①选模板（列表行进入时预选，可换）②变量表单（前端解析模板 `${var}` 动态生成输入框 + **本地实时预览** title/content）③接收人三档（按用户=el-select multiple 拉用户 options；按角色=角色 options；全员=文案确认）+ linkUrl 可选 + 提交（3045/3046/3047 错误码 toast 原文）
- 修改 `src/router/viewRegistry.ts`：注册 `/system/notify` → 通知管理页；页面 defineOptions name 按先例派生
- 用户/角色 options 复用既有 api（`api/user.ts`/`api/role.ts` 的既有列表函数，无 options 端点则拉第一页 pageSize 200）

**验收**：`npm run dev` 手测菜单 19 进入（需 B1 菜单种子 + admin 重登）；模板增删改查流、发送三档全通（对接后端后）；vue-tsc 零错误。

### F3 铃铛 + 轮询 + 弹框

**文件清单**：
- 新建 `src/stores/notify.ts`（Pinia）：state { unreadCount, latestId, poppedId 水位 }；actions { startPolling（30s setInterval + 立即首拉：首拉**只初始化水位不弹存量**）、stopPolling、poll（document.hidden 跳过；latestId>水位→拉 page 第一页→增量>3 条只弹最新 3 条→水位推进）、markRead/markReadAll（复用 api 并同步本地未读数）}
- 修改 `src/layouts/components/Navbar.vue`：右侧动作区加铃铛（el-icon Bell + el-badge 显示 unreadCount，0 隐藏）+ el-popover 下拉（最近未读 5 条：title/type/level 色点 + 时间；底部两按钮「全部已读」「查看全部」→ router.push('/notify/center')）；onMounted startPolling / onUnmounted stopPolling
- 弹框：增量行逐条 `ElNotification`（**手动 import**，按需引入红线——设计 §8）；level→(type,duration) 映射 0:(info,4500) 1:(warning,8000) 2:(error,0 常驻)；onClick=markRead+关闭+linkUrl 有值 router.push
- 修改 `src/layouts/index.vue` 不需要（Navbar 自持生命周期）

**验收**：手测——另一账号发送后 ≤30s 铃铛角标增长+弹框出现；>3 条只弹 3；紧急消息常驻；点击弹框跳转+已读；登出后轮询停止（Network 面板无 /notify/summary 请求）；刷新页面不重弹存量。

### F4 消息中心页

**文件清单**：
- 新建 `src/views/notify/center.vue`：ProTable 我的消息列表（列：title/type/level/readFlag/sendTime + 操作[已读/跳转]）；查询区（type 下拉 + readFlag 下拉 tab 式）；「全部已读」按钮（成功 toast「已读 N 条」后刷新 + notify store 未读数同步）
- 修改 `src/router/index.ts`：Layout children 手写静态路由 `{ path: 'notify/center', name: 'NotifyCenter', meta: { title: '消息中心' } }`（登录即可，不进 viewRegistry 动态菜单——契约 §5 说明）；页面 defineOptions name='NotifyCenter'

**验收**：直链 `/notify/center` 可达（登录态）；筛选/分页/单条已读（已读行幂等重复点击不报错）/全部已读流全通；vue-tsc 零错误。

### F5 e2e 场景脚本

**文件清单**：
- 新建 `cloud-e2e/run-notify-e2e.mjs`：Playwright route mock `/system/notify/**` 全端点（分层数据：模板 2 条、summary 态迁移模拟新消息到达）；场景——①通知管理页 CRUD 流（含 3043 撞 code toast）②发送弹窗三档+变量缺失 3045 toast+全员发送成功反馈 ③铃铛角标渲染与下拉内容 ④mock summary 变化触发弹框出现（断言 ElNotification 节点）与 3 条限流 ⑤消息中心筛选/单条已读/全部已读/角标归零
- 修改 `cloud-e2e/package.json`：`"e2e:notify": "node run-notify-e2e.mjs"`（**不进 e2e 聚合**，沿 aichat 先例）

**验收**：`cd cloud-e2e && npm run e2e:notify` 全绿；grep 输出无 `[FAIL]`（退出码陷阱——harness 失败不设 exit code，只认 `[FAIL]` 计数）。**e2e 脚本与功能代码同 commit**（主控要求）。

## 移交后续阶段的备忘

1. **SSE 实时通道升级**：替代 30s 轮询；summary/page 接口即降级路径，仅换 store 轮询器实现；长连接新课题（心跳/重连/多 tab/建连后踢会话）届时立项（设计 §9.1）。
2. **inner Feign 发送接口**：契约 §6 形态已记档（按 templateCode），service 门面已就位，触发条件=首个业务事件接入方；实现时 /system/inner/** 网关屏蔽规则已存在无需变更。
3. **业务事件自动发信 + 待办双状态**（审批待办等）：bpmn 侧改造 + MQ 投递选型独立一轮；届时收件表增处理状态列（read_flag 单状态口径不翻案，是扩展不是变更）。
4. **BroadcastChannel 跨标签页去重**：当前各 tab 独立轮询独立弹（设计 §5）。
5. **管理端发送历史页**：主表查询路径，需增量 `ALTER TABLE sys_notify_message ADD INDEX idx_create_time (create_time)`（当前仅 PK，设计 §2.2 取舍记档）。
6. 其余后置：按部门发送、消息撤回、已读回执报表、用户手动删消息（设计 §9）。
7. 上一轮遗留（agent-mcp-server-binding）不因本轮变化，见 `docs/superpowers/plans/2026-10-31-agent-mcp-server-binding.md` §3。

## 给 backend-agent 的任务清单

按 B1→B7 章序执行，每任务完成即 commit（TDD：B3-B5 先测试后实现）。关键决策已全列入上文对应任务，补充三条全局口径：

1. **错误码**：3043-3049（契约 §4，system 段 3001-3042 已占、3049 为本轮终值）；常量内联 `new BusinessException(3043, "...")` 沿 3001 风格。
2. **权限**：管理端逐端点 @PreAuthorize（perms 五个见契约 §5）；用户侧四端点**不加** @PreAuthorize，当前用户 `SecurityUtils.currentUser().userId()`（守护无 @PreAuthorize 机械规则，不会误红）。
3. **事务**：仅 NotifySendService.send 加 @Transactional（多表写）；已读/全部已读单表单语句不加；解析器是纯函数不进 service。

全量门槛：`mvn -f cloud-base/pom.xml test -pl cloud-system -am` 全绿；不启动 xxl-job admin 也能跑单测（handler 是普通 bean）。

## 给 frontend-agent 的任务清单

按 F1→F5 章序执行（可先于后端并行——e2e 与手测全走 route mock / 后端就绪后联调），每任务完成即 commit（e2e 脚本与对应功能代码同 commit）。全局口径：

1. **对齐物=契约 `2026-11-08-notify-api.md`**：字段名/类型（id 全 string、Integer 数字不转）/错误码 toast 原文展示；本文档 F 任务已列每端点归属节号。
2. **按需引入红线**：ElNotification 手动 `import { ElNotification } from 'element-plus'`（函数式 API 不经模板解析器）；其余组件模板用法自动解析。
3. **轮询与水位**：30s + 登录首拉只初始化水位（latestId）不弹存量；document.hidden 跳过；单轮增量>3 只弹最新 3；水位是内存态（刷新重置为首拉值）。生命周期挂 Navbar onMounted/onUnmounted（登出自然停）。
4. **路由两形态**：通知管理页挂菜单（viewRegistry 注册 `/system/notify`，依赖菜单种子 19——联调前置 B1+admin 重登）；消息中心页静态路由 `/notify/center`（Layout children 手写，不进 viewRegistry）。
5. 全量门槛：`vue-tsc --noEmit` 零错误 + `cd cloud-e2e && npm run e2e:notify` 全绿（只认 grep `[FAIL]` 计数为 0，exit 0 是假绿）。
