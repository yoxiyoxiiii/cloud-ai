# 站内消息通知中心（notify 域）技术方案

- 日期：2026-11-08
- 状态：已拍板（需求分析结论全数采纳），本文为定稿设计
- 契约：`docs/superpowers/contracts/2026-11-08-notify-api.md`（前后端唯一对齐物）
- 实施计划：`docs/superpowers/plans/2026-11-08-notify-center.md`

## 0. 需求与拍板结论（一段话）

为平台补站内信基座能力：管理员基于**消息模板**（`${var}` 占位符解析）向用户发送通知；所有登录用户在 cloud-web 全局 header 看到**铃铛未读角标**、新消息**右上角弹框提醒**，并可进**消息中心页**浏览与标记已读。本轮拍板口径：**轻量轮询**推送（30s，SSE 升级后置）；**单状态未读**（未读=未处理，一个统计口径）；**仅管理端手动发送**（service 留发送门面，inner Feign 签名预留不实现）；**模板简版 CRUD 页**；接收人三档（按用户多选/按角色/全员）**写扩散**全做；消息分级（普通/重要/紧急）映射弹框样式与时长；可选相对路由跳转；单条已读+一键全部已读；弹框合并限流；xxl-job 清理任务本轮带上。

落点：后端 cloud-system（:9202），前端 cloud-web 全局层（Navbar 铃铛 + 通知管理菜单页 + 消息中心静态路由页）。

> 权限标识口径说明：主控任务书写 `sys:notify:*`；项目权限标识体系实际为 `<服务名>:<实体>:<动作>`（既有 `system:user:list`、`system:mqDlq:list`），按体系裁定为 **`system:notify:*`**，三文档一致。

## 1. 架构总览

```
【管理链路】
cloud-web 通知管理页(/system/notify, 菜单驱动)
    │ POST /system/notify/template|send ...（经网关 :18080，AuthGlobalFilter 验 JWT+在线）
    ▼
cloud-system(:9202)
    NotifyTemplateManageController ─→ NotifyTemplateManageService ─→ SysNotifyTemplateMapper
    NotifySendManageController ──────→ NotifySendService(发送门面) ─┬→ SysNotifyTemplateMapper(取模板)
                                                                   ├→ SysUserMapper(接收人展开：3 档)
                                                                   ├→ SysNotifyMessageMapper(消息主表 1 行)
                                                                   └→ SysNotifyUserMessageMapper(收件 N 行, 批量)
    落库：sys_notify_template / sys_notify_message / sys_notify_user_message（cloud_system 库）

【用户链路（登录即可，无 perms）】
cloud-web Navbar 铃铛 + Pinia notify store（30s 轮询）
    │ GET /system/notify/summary   ← 未读数 + 最新收件行 id（水位）
    │ GET /system/notify/page      ← 水位变化时拉第一页，id > 水位的行触发弹框
    │ PUT /system/notify/read/{id} / read-all
    ▼
cloud-system NotifyUserController ─→ NotifyUserMessageService ─→ SysNotifyUserMessageMapper
    （数据按 X-User-Id（SecurityUtils.currentUser().userId()）隔离，越权访问等同"不存在"）

【清理链路】
xxl-job admin(容器 :18081, 任务 id 11) ──► cloud-system executor(:19202) NotifyCleanJobHandler
    ① 物理删收件表过期已读行 ② 物理删无收件行残留的消息主表行
```

核心模型决策（后文展开）：**消息表（1 行/次发送）+ 用户收件表（N 行/次发送）写扩散**。未读统计与已读操作只碰收件表；模板内容在发送时解析为最终文本快照进消息表，此后与模板解耦（模板修改/删除不影响已发消息）。

## 2. 数据模型（DDL 与索引设计）

### 2.1 三表 DDL

```sql
-- =====================================================================
-- 站内信通知中心三表（2026-11-08，设计 docs/superpowers/specs/2026-11-08-notify-center-design.md）
-- 模型：模板(软删) + 消息主表(流水,无 deleted) + 用户收件表(写扩散,无 deleted)
-- =====================================================================
USE cloud_system;

CREATE TABLE sys_notify_template (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '模板ID',
    code             VARCHAR(50)   NOT NULL COMMENT '模板编码（业务引用键，如 welcome_notice；内置模板供未来 inner 接口按 code 引用）',
    name             VARCHAR(50)   NOT NULL COMMENT '模板名称（管理列表展示）',
    title_template   VARCHAR(100)  NOT NULL COMMENT '标题模板（支持 ${var} 占位符）',
    content_template VARCHAR(1000) NOT NULL COMMENT '内容模板（支持 ${var} 占位符）',
    type             TINYINT       NOT NULL DEFAULT 1 COMMENT '消息类型：1公告 2提醒（发送时快照到消息表）',
    level            TINYINT       NOT NULL DEFAULT 0 COMMENT '消息级别：0普通 1重要 2紧急（发送时快照；映射前端弹框样式与停留时长）',
    variables_desc   VARCHAR(500)  NOT NULL DEFAULT '' COMMENT '变量说明（人工维护的变量名与含义文本，仅文档用途；变量集合以模板内容正则解析为准，不重复存储）',
    is_builtin       TINYINT       NOT NULL DEFAULT 0 COMMENT '内置标记：1=内置模板（禁删，内容可改）0=用户创建',
    create_by        VARCHAR(30)   DEFAULT NULL COMMENT '创建人（登录账号）',
    create_time      DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_by        VARCHAR(30)   DEFAULT NULL COMMENT '更新人（登录账号）',
    update_time      DATETIME      DEFAULT NULL COMMENT '更新时间',
    deleted          TINYINT       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除 1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_code (code)
) ENGINE = InnoDB COMMENT = '站内信模板表（管理域：逻辑删除）';

CREATE TABLE sys_notify_message (
    id             BIGINT        NOT NULL AUTO_INCREMENT COMMENT '消息ID',
    template_id    BIGINT        NOT NULL COMMENT '来源模板ID（sys_notify_template.id，发送时快照）',
    title          VARCHAR(100)  NOT NULL COMMENT '消息标题（模板解析后最终文本，快照）',
    content        VARCHAR(1000) NOT NULL COMMENT '消息内容（模板解析后最终文本，快照）',
    type           TINYINT       NOT NULL COMMENT '消息类型：1公告 2提醒（模板快照）',
    level          TINYINT       NOT NULL COMMENT '消息级别：0普通 1重要 2紧急（模板快照）',
    link_url       VARCHAR(200)  DEFAULT NULL COMMENT '前端跳转路由（相对 path 如 /bpmn/task；NULL=纯展示无跳转）',
    receiver_type  TINYINT       NOT NULL COMMENT '接收人维度：1按用户 2按角色 3全员（发送时快照，审计用）',
    receiver_count INT           NOT NULL COMMENT '实际展开收件人数（发送时快照，审计用）',
    create_by      VARCHAR(30)   DEFAULT NULL COMMENT '发送人（登录账号）',
    create_time    DATETIME      DEFAULT NULL COMMENT '发送时间',
    update_by      VARCHAR(30)   DEFAULT NULL COMMENT '更新人（本表不可编辑，恒 NULL，列保留对齐审计基线）',
    update_time    DATETIME      DEFAULT NULL COMMENT '更新时间（本表不可编辑，恒 NULL）',
    PRIMARY KEY (id)
) ENGINE = InnoDB COMMENT = '站内信消息表（发送流水：不可编辑、无业务删除入口；过期由清理任务物理删，沿 mq_tx_log 流水表先例）';

CREATE TABLE sys_notify_user_message (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '收件记录ID（用户侧列表行与已读操作的目标标识）',
    message_id  BIGINT      NOT NULL COMMENT '消息ID（sys_notify_message.id）',
    user_id     BIGINT      NOT NULL COMMENT '收件用户ID（sys_user.id）',
    read_flag   TINYINT     NOT NULL DEFAULT 0 COMMENT '已读标记：0未读 1已读（未读=未处理，单状态口径）',
    read_time   DATETIME    DEFAULT NULL COMMENT '已读时间（未读为 NULL）',
    create_by   VARCHAR(30) DEFAULT NULL COMMENT '创建人（=发送人账号，批量展开时统一填写）',
    create_time DATETIME    DEFAULT NULL COMMENT '投递时间（=发送时间）',
    update_by   VARCHAR(30) DEFAULT NULL COMMENT '更新人（已读操作时=操作用户账号）',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_user (message_id, user_id),
    KEY idx_user_read (user_id, read_flag),
    KEY idx_read_time (read_time)
) ENGINE = InnoDB COMMENT = '站内信用户收件表（写扩散：一条消息 N 行；无业务删除入口，过期由清理任务物理删，沿 mq_consume_dedup 先例）';
```

### 2.2 索引设计与取舍（逐个注明命中的查询）

| 索引 | 命中的查询 | 取舍说明 |
|---|---|---|
| `sys_notify_template.uk_code (code)` | 新增/修改模板查重（`countByCode`）；未来 inner 接口按 code 取模板 | 唯一键刚需（规范：uk_ 兼查重）。模板表量级极小（几十行），type 筛选走全扫即可，**不建 idx_type**（低基数小表，索引负收益） |
| `sys_notify_message` 仅 PK | 本轮唯一写路径为 insert；读路径全部经收件表 JOIN 命中 PK | 管理端"发送历史列表"查询后置（见 §9），届时按查询清单补增量 `idx_create_time` |
| `sys_notify_user_message.uk_message_user (message_id, user_id)` | ① 发送批量插入的幂等防御（同消息同用户只一行）② 清理任务第二步孤儿主表判断 `NOT EXISTS (... WHERE message_id = m.id)`（最左前缀命中） | 发送前 Set 去重保证常态不撞；uk 是并发/重试防御层，DuplicateKeyException 兜底转业务码 |
| `sys_notify_user_message.idx_user_read (user_id, read_flag)` | ① summary 未读计数 `COUNT(*) WHERE user_id=? AND read_flag=0`（30s 轮询高频，最热的查询）② 用户分页列表 `WHERE user_id=? [AND read_flag=?] ORDER BY id DESC`（二级索引叶子含 PK，前缀等值 + 反向扫，实现时 EXPLAIN 抽查）③ 全部已读 `UPDATE ... WHERE user_id=? AND read_flag=0` | 三查询共享一个复合索引；read_flag 放第二列因所有查询都带 user_id 等值前缀 |
| `sys_notify_user_message.idx_read_time (read_time)` | 清理任务第一步 `DELETE ... WHERE read_flag=1 AND read_time < ?` | 每日一次低频，但收件表是全库行数最大的表（用户数×消息数），无索引则全表扫已读行回表过滤；read_time 范围扫后回表核 read_flag，代价可接受。不建 (read_flag, read_time) 复合——read_flag=1 恰好是 idx_read_time 命中行集的全集，前缀无过滤增益 |

### 2.3 模型取舍记档

- **软删/物理删分域**：模板表是管理域配置（误删可追溯，逻辑删沿 sys_user/sys_menu 惯例）；消息主表与收件表是发送流水（无业务删除入口，保留策略清理物理删，沿 `mq_tx_log`/`mq_consume_dedup` 先例）。收件表若用逻辑删会持续膨胀（写扩散行数最大），物理删保持表薄。
- **快照语义**：消息表落的是解析后最终文本 + 模板 type/level 快照，模板此后任何变更（含删除）不影响已发消息——避免消息中心展示回溯联动。
- **不冗余 title/content 进收件表**：收件行保持最薄（id/message_id/user_id/read_flag/read_time + 审计），列表 JOIN 主表取文案；写扩散的存储成本最小化。
- **收件行 id 作为用户侧操作标识**（非 message_id）：用户视角每行是一条"我的消息"，已读操作天然按行归属校验。

## 3. 模板解析与变量校验

- **占位符语法**：`${var}`，变量名字母开头、后接字母数字下划线（正则 `\$\{([a-zA-Z][a-zA-Z0-9_]*)\}`）。不引模板引擎（Velocity/Freemarker 对此场景过重），正则提取 + 逐个 `String.replace` 替换，零依赖。
- **变量集合的单一事实源 = 模板内容本身**（title_template + content_template 正则提取，LinkedHashSet 保序）。`variables_desc` 只是给人看的说明文本，不参与解析——避免"声明了变量却没用到"的双源漂移。
- **缺失变量硬失败**：发送时入参 variables 的键集 ⊉ 模板提取的变量集 → 业务异常 3045，msg 明确列出缺失变量名；**多余变量忽略**（模板后续加变量不破坏既有调用方，前向兼容）。
- **解析后超长校验**：解析结果超出 title(100)/content(1000) 列宽 → 3046（不做静默截断，防残句公告发出）。
- **纯函数解析器**：`util/NotifyTemplateParser`（静态方法：extractVariables / render），无状态可直测。
- **前端发送弹窗预览在本地做**（同一正则替换展示），以后端提交时校验为准——预览零接口。

## 4. 发送流程（NotifySendService 门面）

```
send(NotifySendRequest)  [@Transactional(rollbackFor = Exception.class)，多表写]
 1. 前置校验：receiverType=1 时 userIds 非空 / =2 时 roleIds 非空（1001）；
    linkUrl 非空时必须以 "/" 开头（1001，相对路由约束）
 2. 取模板 findById（deleted=0）→ 不存在 3044
 3. 解析变量集 → 校验缺失（3045）→ render（超长 3046）
 4. 展开收件人（LinkedHashSet 去重）：
    USER(1)：userIds → 过滤无效（status≠0 或 deleted=1 的用户剔除）
    ROLE(2)：roleIds → JOIN sys_user_role + sys_user 过滤有效用户，DISTINCT user_id
    ALL(3)：全部有效用户（status=0 AND deleted=0）
    展开结果为空 → 3047
 5. insert sys_notify_message 1 行（receiver_type/receiver_count 快照，审计 SQL NOW()）
 6. 批量 insert sys_notify_user_message N 行（foreach，500/批；create_by 统一=发送人）
    DuplicateKeyException 兜底吸收（uk 防御层，常态不触发）
 7. 返回 NotifySendResultVo{ messageId, receiverCount }
```

- **接收人展开查询放 SysUserMapper**（表归属原则：notify 域 mapper 只碰 notify 三表）：新增 `listActiveUserIds()`（全员）与 `listUserIdsByRoleIds(roleIds)`（按角色），均过滤 `status=0 AND deleted=0`。
- **展开策略=发送时快照**：按角色/全员发送只落当时有效用户，之后入离职不回溯（语义简单正确；"新用户可见历史全员消息"属读扩散模型，明确不做）。
- **不做 MQ 异步**：单库多表事务（@Transactional）即满足一致性；管理端同步发送同步见结果。未来 inner 接口高频化时再评估事务消息（记 §9）。
- **门面预留**：`send` 即未来 inner Feign 控制器直接复用的 service 门面（形态见契约 §6）。

## 5. 用户侧轮询与防骚扰（前端机制，接口见契约 §3）

- **单一轮询接口 summary**（30s）：返回 `{ unreadCount, latestId }`（latestId=该用户最新收件行 id，MAX 主键，无收件=0）。角标与"有无新消息"判定共用一次请求，不为弹框单独拉详情——轮询成本每用户每 2 分钟 1 个轻请求。
- **水位机制**：Pinia store（`stores/notify.ts`）内存水位 `poppedId`。登录首拉 summary 时**初始化水位=latestId（不弹存量）**；此后轮询发现 latestId > 水位 → 拉 page 第一页 → 取 `id > 水位` 的行弹框 → 水位推进到最大 id。刷新页面重置为首拉值，天然不重弹历史。
- **合并限流**：单轮增量逐条弹 ElNotification，**最多 3 条**（按 id 降序取最新 3 条），其余仅累进角标；多轮各弹各（30s 自然节流）。
- **弹框样式按时长映射 level**：0 普通 → info 型 4.5s；1 重要 → warning 型 8s；2 紧急 → error 型 duration 0（常驻手动关闭）。
- **点击行为**：标已读（调 read 接口，失败静默）+ 关闭 + `router.push(linkUrl)`（有值时）。
- **页面隐藏跳过**：`document.hidden` 时本轮轮询跳过（一行判断，不做 visibilitychange 恢复即拉——下轮 30s 自然补上）。
- **多标签页**：各 tab 独立轮询独立弹（不去重跨 tab；BroadcastChannel 共享记 §9 后置）。
- **生命周期**：Navbar（挂载于登录后唯一布局 Layout）onMounted 启动轮询、onUnmounted 停止——登出跳 /login 时 Layout 卸载自然停轮询，无需侵入 auth store。

## 6. 定时清理（xxl-job）

- handler：`job/NotifyCleanJobHandler`（@XxlJob("notifyCleanJobHandler")，落 job/ 包守护规则），admin 任务 **id 11、组 3（cloud-system）、CRON 每日 03:40**（错峰 03:00 MQ 两表清理 / 03:30 死信清理）。
- 两步（同一次执行内顺序完成，无事务包裹——清理幂等，中断重跑无害）：
  1. 物理删收件表：`DELETE WHERE read_flag=1 AND read_time < NOW() - N 天`（**未读行永不删**——删未读=丢用户消息）。命中 `idx_read_time`。
  2. 物理删孤儿主表：`DELETE FROM sys_notify_message m WHERE NOT EXISTS (SELECT 1 FROM sys_notify_user_message u WHERE u.message_id = m.id)`（第 1 步删空收件行的消息随之收敛）。命中 `uk_message_user` 最左前缀。
- 配置：`CloudSystemNotifyProperties`（@ConfigurationProperties("cloud.system.notify")，规范 6 对象封装），`retention-days` 默认 30（仓库 application.yml 默认值 + Nacos 可覆盖，沿 CloudSystemMqDlqProperties 先例）。
- 删除行数 log.info 记档；不抛异常即成功（xxl 缺省口径，沿 DlqLogCleanJobHandler）。

## 7. 错误码与权限标识

- **错误码**：system 段 3xxx 现值 3001-3042 连续全占（本轮全量复核：内联与常量两形态合计；契约 2026-10-23-table-config-api 已记档"下轮 3043 接续"）。本轮通知域分配 **3043-3049**（明细见契约 §4）。
- **权限标识**（管理端，进 sys_menu 种子）：`system:notify:list` / `system:notify:add` / `system:notify:edit` / `system:notify:remove` / `system:notify:send`。
- **用户侧四接口登录即可**（不加 @PreAuthorize）：网关已完成 JWT+在线校验，服务层以 `SecurityUtils.currentUser().userId()` 隔离数据——收件行不存在**或非本人**一律 3048，不泄露他人数据存在性。
- **菜单种子**：id **19**（C「通知管理」，path `/system/notify`，perms `system:notify:list`，父 10 系统管理，sort 8）+ 按钮级 **196/197/198/199**（add/edit/remove/send）。19 段与 196-199 经基线全量核对空闲（18 顶级已被 AI 域占用、181-195 已占、196-199 空闲）。admin 角色绑定 + cloud_system.sql 基线同步，沿 2026-10-13-ai-rag-chat.sql 头注口径。
- **种子模板**（同 SQL 文件）：两条 `is_builtin=1` 内置模板——`welcome_notice`（公告，无变量）与 `approval_remind`（提醒，含 `${nickname}` 变量示例，演示变量链路）。

## 8. 规范合规要点（方案天然合规，backend/frontend agent 照此免翻查）

后端（cloud-system）：
- 手写 SQL mapper XML，禁 Wrapper/BaseMapper；只用 `#{}`；分页 `IPage<T> method(Page<T> page)` 不写 LIMIT；模板表所有查询 WHERE 显式 `deleted = 0`，删除=UPDATE deleted=1 + update 审计两值。
- Controller 两行式返回、`@PathVariable("id")` 显式命名、DTO 入参/VO 出参（Service 层原生 setter 转换，集中 `convert/NotifyConvert`）、管理端 @PreAuthorize。
- 实体内嵌枚举 Enum 后缀（TypeEnum/LevelEnum/ReceiverTypeEnum/ReadFlagEnum），字段保持 Integer 映射，Java 引用禁魔法数。
- 多表写 @Transactional（发送）；已读/全部已读单表单语句不加。审计时间 SQL NOW()；操作人 SecurityUtils.currentAccount()。
- Service catch 异常先 log.error 记根因再转业务异常；方法 ≤50 行目标；入参 >3 封装对象。
- ArchitectureGuardTest 构建期自动覆盖新代码（禁内联 R.ok/单行 if/Map 直接接参等），无需新增守护规则。

一处明示裁量：**NotifySendRequest DTO 内含 `Map<String, String> variables` 字段**——「禁止 Map 接参」指 Controller 方法签名不得以 Map 整体接参；模板变量的键空间是动态的（由模板内容决定），DTO 内嵌受控 Map（键须匹配 `[a-zA-Z][a-zA-Z0-9_]*`）是该语义的自然形态，守护测试检查的是方法参数类型，不拦 DTO 字段。

前端（cloud-web）：
- Element Plus 按需引入红线：组件走 unplugin 自动解析；ElNotification 属函数式 API，沿 ElMessage 先例**手动 import**（`import { ElNotification } from 'element-plus'`，resolver 处理样式注入）。
- 通知管理页挂菜单（viewRegistry 注册 `/system/notify` + defineOptions name 按先例派生）；消息中心页为**静态路由** `/notify/center`（Layout children 手写，登录即可，不进动态菜单——sys_menu 按角色绑定，全员功能不能靠菜单树分发）。
- 列表页沿 ProTable 先例（props: rows/total/pageNum/pageSize + pageChange）；api 封装沿 `api/mqDlq.ts` 风格（类型进 `types/api.ts`，id 均为 string——Long→String）。

## 9. 后置项（本轮不做，移交账见 plan）

1. **SSE/WebSocket 实时通道升级**：替代轮询；summary/page 接口即降级路径，前端只需换轮询器实现（store 动作接口不变）。
2. **inner Feign 发送接口实现**：cloud-system-api `NotifyClient`（按 templateCode 发送），契约 §6 已预留形态；触发条件=首个业务事件接入方。
3. **业务事件自动发信**（如审批待办→站内信）：含 bpmn 侧改造与 MQ 投递方式选型（同步 Feign vs 事务消息）。
4. **待办双状态**（read_flag + 处理状态）：随业务事件接入轮（届时"已处理"才有翻转入口）。
5. 跨标签页去重（BroadcastChannel）；管理端发送历史列表页（主表查询路径，需补索引）；按部门/组织架构发送；消息撤回；已读回执报表；用户手动删消息。

## 10. 测试策略

- **后端单测**（JUnit5 + Mockito，沿 cloud-system 先例）：
  - NotifyTemplateParserTest（纯函数）：变量提取/替换/缺失/多余/超长。
  - NotifySendServiceTest：3044/3045/3046/3047 全分支、三档展开（含无效用户过滤与去重）、事务注解与批量分批、DuplicateKey 吸收。
  - NotifyTemplateManageServiceTest：3043 查重（含墓碑占键）、3049 内置禁删、软删审计两值。
  - NotifyUserMessageServiceTest：3048 归属校验（非本人=不存在）、已读幂等（重复已读成功 0 行）、read-all 计数。
- **e2e**（cloud-e2e，route mock 分层，独立脚本不进聚合，沿 aichat 先例）：模板 CRUD 流、发送流（三档接收人+变量校验报错）、铃铛角标与下拉、弹框出现与限流、消息中心筛选/已读/全部已读。
- **真链路冒烟**（主控验收段）：起服 → 种子 → admin 发全员消息 → 普通用户登录验角标/弹框/已读链路 → 未读数归零。
- 全量回归门槛：`mvn test -pl cloud-system` 全绿（含守护）；前端 `vue-tsc --noEmit` 零错误。
