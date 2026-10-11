# MQ 死信治理实施计划（2026-10-11）

> 输入：设计 `docs/superpowers/specs/2026-10-11-mq-dlq-governance-design.md` + 契约 `docs/superpowers/contracts/2026-10-11-mq-dlq-governance-api.md`（唯一对齐物）。
> **B 章与 F 章零文件交集、独立可并行**（前端仅依赖契约，mock 先行）；E 章在 B7 + F4 后。
> 前置技术验证已由设计文档 §2 收敛（rocketmq-spring 2.3.3 订阅 %DLQ% 静态源码实证），**无 B0 卡点**；运行时冒烟并入 B7 首步。
> 本计划不含 git 操作（提交由主控执行）。mvn 用绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`。

## 后端章（cloud-base/，backend-agent，B1→B7；B2/B3/B5 在 B1 后可并行）

### B1 SQL：mq_dlq_log + 字典 + 菜单 + xxl-job 种子

- 文件清单：
  - `cloud-base/scripts/sql/2026-10-11-mq-dlq-governance.sql`（新，四段）：
    - 段1 mq_dlq_log DDL（设计 §4 原文，落 cloud_system）
    - 段2 字典种子：sys_dict_type `mq_dlq_status` + 4 项 dict_data（0 待处理/1 已重投/2 已忽略/3 已放弃，is_builtin=1；增量环境 dict id 走 AUTO_INCREMENT，按 dict_key 子查询关联——沿 dict 轮增量先例）
    - 段3 菜单种子：sys_menu 17/171/172/173 + sys_role_menu admin 显式 id 列表（契约 §5；头注「admin 须重新登录进权限快照」）
    - 段4 xxl_job 种子：id 10（job_group=3、CRON `0 30 3 * * ?`、handler `dlqLogCleanJobHandler`、trigger_status=1、glue_updatetime=now()——沿 2026-10-10 迁移轮已修正形态：trigger_status 显式列/CRON 6 段无步进陷阱；头注不可重放：先 DELETE FROM xxl_job_info WHERE id = 10）
  - `cloud-base/scripts/sql/cloud_system.sql`（基线同步：mq_dlq_log DDL + 字典 + 菜单段，语义等价注释沿先例）
  - `cloud-base/scripts/sql/2026-10-09-xxl-job-init.sql`（种子段追加 id 10 同款行，注释注明 2026-10-11 死信治理轮增补）
- 步骤：java 单文件源码 + mysql-connector-j 执行增量脚本落两库（cloud_system + xxl_job）→ 回查断言（mq_dlq_log SHOW CREATE TABLE 索引齐全；`SELECT COUNT(*) FROM sys_dict_data d JOIN sys_dict_type t ON d.dict_type_id=t.id WHERE t.dict_key='mq_dlq_status'`=4；sys_menu id IN (17,171,172,173) 四行；xxl_job_info id=10 行 trigger_status=1）。
- 验收：断言全过；脚本头注含执行说明与不可重放警示。

### B2 实体 + Mapper + XML

- 文件清单：
  - `cloud-system/src/main/java/com/cloudai/system/entity/MqDlqLog.java`（新：不继承 BaseEntity——设计 D5 先例；嵌套 `StatusEnum{PENDING(0),RESENT(1),IGNORED(2),ABANDONED(3)}` 含 code 与 of(code)，字段 Integer 映射；msgId/body 等全列）
  - `cloud-system/src/main/java/com/cloudai/system/mapper/MqDlqLogMapper.java`（新）
  - `cloud-system/src/main/resources/mapper/MqDlqLogMapper.xml`（新）
- mapper 方法与 SQL 口径（全部手写 XML、只 `#{}`、无 deleted 列不写 deleted 条件）：
  - `saveDlqLog(MqDlqLog)` insert 全列显式（create_time 显式传参非 DEFAULT 依赖；DuplicateKeyException 由 service catch 吸收）
  - `findDlqLogById(Long id)` 单查全列
  - `pageListDlqLog(Page<MqDlqLog> page, @Param("query") MqDlqPageQueryDto query)` 分页（XML 不写 LIMIT；WHERE 动态：status/bornGroup 精确、msgKey `LIKE CONCAT('%',#{query.msgKey},'%')` 前后模糊、dlqTimeBegin/End 闭区间；ORDER BY dlq_time DESC；`<if>` 标签体换行规范）
  - `updateResend(@Param...)`（status=1、resend_count=resend_count+1、last_resend_time/operate_account/operate_time/update_time 四值——WHERE id=？）
  - `updateStatus(id, status, operateAccount, operateTime)`（ignore/reset/自动置弃共用；WHERE id=?）
  - `deleteExpiredDlqLog(cutoff)`（WHERE status IN (1,2,3) AND dlq_time < #{cutoff}——待处理行永不清，设计 D10）
- 验收：`-pl cloud-system -am` 编译过；XML 无 `${}`、单行 `<if>`、LIMIT。

### B3 watch 监听面 + 配置

- 文件清单：
  - `cloud-system/src/main/java/com/cloudai/system/config/CloudSystemMqDlqProperties.java`（新：`@ConfigurationProperties("cloud.system.mq-dlq")`——watch.create/event 的 dlqTopic+group、resendMax=5、retentionDays=30；规范 6 对象封装）
  - `cloud-system/src/main/java/com/cloudai/system/mq/AbstractMqDlqWatchListener.java`（新：onMessage=MessageExt → 组装 MqDlqLog（msgId/txNo(TX_NO userProperty)/bornTopic=userProperty RETRY_TOPIC 优先、缺省退化 msg.getTopic()【B7 实证修正：DLQ 消息 topic 已被 broker 改写为 %DLQ%组名，照抄会发进 DLQ 主题，见设计 D5 记档】/bornGroup=watch topic strip `%DLQ%` 前缀/watchGroup/tag=msg.getTags()/msgKey=KEYS 优先退化 msgId（与 DedupRocketMQListener.resolveMsgKey 同口径）/body=UTF-8 解码超 60000 截断+截断尾标记/reconsumeTimes/bornTime=bornTimestamp/dlq_time=storeTimestamp）→ 调 service.saveWatched；异常原样抛出走重试。子类提供 watchGroup 与 dlqTopic）
  - `cloud-system/src/main/java/com/cloudai/system/mq/MqDlqWatchCreateListener.java` + `MqDlqWatchEventListener.java`（新薄壳：`@Component` + `@UkIdempotentListener("uk_msg_id(mq_dlq_log)：watch 重复投递幂等吸收（msgId 全局唯一）")` + `@RocketMQMessageListener(topic="${cloud.system.mq-dlq.watch.create.dlq-topic}", consumerGroup="${cloud.system.mq-dlq.watch.create.group}", maxReconsumeTimes=3)`——event 壳同款 event 键；守护规则 v6 合规）
  - `cloud-system/src/main/resources/application.yml`（改：设计 D11 配置段原文 + 注释「watch 组名全新拟定禁与业务组重名；DLQ topic 与来源组名手工同步」）
- 验收：模块构建过；薄壳类注解三件套齐全（守护 v6 扫描面）；Properties 有 javadoc。

### B4 Service + Controller（含重投编排）

- 文件清单：
  - `cloud-system/src/main/java/com/cloudai/system/dto/MqDlqPageQueryDto.java`（新：契约 §3.1 七字段，Bean Validation 非必填全可选）
  - `cloud-system/src/main/java/com/cloudai/system/vo/MqDlqVo.java` + `MqDlqDetailVo.java`（新：契约 §3.1/§3.2 字段表；bodyPreview=body 前 120 字符+省略号）
  - `cloud-system/src/main/java/com/cloudai/system/convert/MqDlqConvert.java`（新：toVo/toDetailVo 原生 setter 逐字段，禁三方拷贝）
  - `cloud-system/src/main/java/com/cloudai/system/service/MqDlqLogService.java`（新，方法单一职责 ≤50 行）：
    - `saveWatched(MessageExt msg, String watchGroup, String dlqTopic)`——组装+saveDlqLog+catch DuplicateKeyException log.info 吸收（组装逻辑若基类已做则此处只落库，职责按实现归位）
    - `pageList(MqDlqPageQueryDto)` → PageResult<MqDlqVo>
    - `detail(Long id)` → MqDlqDetailVo（3038）
    - `resendDlq(Long id)`——设计 D7 编排：校验（3038/3040/3041+自动置弃）→ `ConsumeDedupDao.delete(bornGroup, msgKey)`（注入 starter bean）→ syncSend（destination=tag 非空拼 `topic:tag`；headers：`msg_key != msg_id` 时 setHeader(KEYS)、tx_no 非空时 setHeader(TX_NO)（常量复用 `TxMessageSenderImpl.HEADER_TX_NO/HEADER_KEYS`）；payload=body bytes UTF-8）→ catch log.error → 3039 → 成功后 updateResend。**不包 @Transactional**（设计 D7 记档）
    - `ignoreDlq(Long id)`（0→2，3040）/`resetDlq(Long id)`（2|3→0 清零计数，3040）——operate 审计两值显式 `SecurityUtils.currentAccount()` + now
  - `cloud-system/src/main/java/com/cloudai/system/controller/manage/MqDlqManageController.java`（新：五端点对齐契约 §1；两行式返回；@PathVariable("id") 显式命名；javadoc；@PreAuthorize 四标识；resend/ignore/reset 无 body 入参）
  - **实现记档（方法名偏差，URL/契约零影响）**：Service 方法名按守护动词白名单落位——计划原文 `resendDlq/ignoreDlq/resetDlq/detail` → 实现 `updateResend/updateIgnore/updateReset/findDetail`（resend/ignore/reset 均为行状态修改类动作走 `updateXxx`、单查走 `findXxx`，方法命名动词集合规必然；saveWatched/pageList 原名落位不变）。
- 单测：`MqDlqLogServiceTest`（resend 六分支：3038/3040 终态/3041 自动置弃/dedup 清行调用验证/send 异常 3039/成功 updateResend；ignore/reset 状态机边界；saveWatched DuplicateKey 吸收）+ `MqDlqWatchListenerTest`（组装字段口径：msg_key 退化/bornGroup strip/body 截断/tag 可空）。
- 验收：`-pl cloud-system -am` 全绿；守护零红（内联 R.ok/隐式 PathVariable/Map 接参等全规避开）。

### B5 清理 job

- 文件清单：`cloud-system/src/main/java/com/cloudai/system/job/DlqLogCleanJobHandler.java`（新：`@XxlJob("dlqLogCleanJobHandler")` 薄壳——cutoff=now-retentionDays 调 `deleteExpiredDlqLog`，删除行数 log.info；job/ 包落位合规；无 XxlJobHelper 依赖）
- 验收：模块构建过；`@Scheduled` 全仓仍为零（守护 v8 面）；admin 手动触发联调归 B7。

### B6 全量构建 + 守护全绿

- 命令：`$MVN -f cloud-base/pom.xml clean install`（上轮 404+ 基线 + 本轮新增单测全绿）。
- 验收：BUILD SUCCESS、零守护红；bpmn/sso/gateway 零文件改动（git status 核对——拍板"bpmn 侧零改动"红线）。

### B7 联调验证收口（需起服务 9201-9203/18080；admin 18081 常驻）

- 步骤（agent 自管服务，沿 MEMORY 纪律；Nacos rocketmq.name-server 已配）：
  1. **watch 订阅冒烟（R1 残余运行时验证）**：起四服务 → 日志确认两 watch 组订阅注册（无订阅异常/权限拒绝）。
  2. **造死信（坏 body 链）**：java 单文件 producer 直发 TX_APPROVAL_CREATE，body=`"not-json"`、KEYS=`leave:e2e-dlq-<ts>`（全 ASCII）→ 等 4 次投递周期（重试 ×3+死信，分钟级）→ 断言：mq_dlq_log 新行（born_group=g_bpmn_approval_create、status=0、reconsume_times=3、msg_key=KEYS）。
  3. **重投代际链**：curl POST resend 该行 → 断言行 status=1/resend_count=1 → 坏 body 再死信 → **新行出现**（新 msgId 同 msg_key）→ 页面断言面留 E 章。
  4. **成功重投闭环**：SQL 造一行合法 body（ApprovalCreateInnerRequest JSON：businessType=leave、businessKey=`e2e-dlq-ok-<ts>`、approver=admin、leaveType=1、title/dates 全 ASCII）→ curl resend → 断言：bpmn_approval 新行（uk_business 命中）+ CREATE_RESULT 事件 → approval_projection 投影行 create_result=1（**消费成功+事件链收敛直证**）。
  5. **ignore/reset**：curl ignore 一行（status 0→2）→ curl reset（2→0、计数清零）。
  6. **上限置弃**：SQL 置一行 resend_count=5 → curl resend → 3041 + status=3。
  7. **清理任务**：admin「执行一次」id 10 → joblog 双 code=200 + system 日志删除行数痕。
  8. **dashboard 核对**：watch 消费后 %DLQ% 堆积清零（设计 D2 运维语义变化实证记档）。
  9. 验证毕 kill 四服务（netstat+taskkill）；admin 容器与 RocketMQ 容器不触碰。
- 验收：步骤 2-8 断言结果记入回报；任何一步 FAIL 留栈排查（R1 触发则回报主控启用 admin API 退路评估——设计 D2）。

## 前端章（cloud-web/，frontend-agent，F1→F4，与 B 章真并行；mock 先行）

### F1 types + api 封装 + mock

- 文件清单：`cloud-web/src/types/api.ts`（MqDlqVo/MqDlqDetailVo/查询参数类型，契约 §3.1/§3.2 逐字段）；`cloud-web/src/api/` 下 mqDlq 模块（五端点封装，请求封装铁律）；mock 数据（列表两行+代际链两行同 msgKey+终态行——B 端未就绪时联调）。
- 验收：vue-tsc 零错误；契约字段名逐字对齐（Long string 形态）。

### F2 列表页

- 文件清单：`cloud-web/src/views/system/mqDlq/index.vue`（新）：
  - 过滤区：状态（el-select 字典 mq_dlq_status 驱动）/来源组（el-select 两静态项+清空）/msgKey（el-input）/死信时间范围（el-date-picker daterange）
  - 表格：msgId（截断+tooltip）/bornTopic/tag/msgKey/reconsumeTimes/dlqTime/status（字典标签：0 warning/1 primary/2 info/3 danger）/resendCount/operateAccount/操作列（详情/重投/忽略/重置——契约 §4 可见性规则+v-perm 权限指令；**同 msgKey 多行分组 warning 提示**：列表数据按 msgKey 聚合计数，>1 的行首列标记"代际链 N 行"）
  - 分页（既有列表页模式）；默认查询 status=0
  - 组件按需引入铁律（unplugin 自动，禁全量）
- 验收：mock 数据渲染正确（含代际链标记/按钮可见性四态）；build 零错误。

### F3 详情 + 确认弹窗

- 文件清单：`cloud-web/src/views/system/mqDlq/`（详情弹窗组件：全字段+body `<pre>` JSON 展示+bodyTruncated 提示；重投确认弹窗：msgId/msgKey/bornTopic 回显+高危警示文案；忽略/重置确认弹窗：终态提示）。
- 验收：mock 联调四操作流转正确；弹窗模式沿 /frontend-page 规范。

### F4 build + dev 真栈联调（B7 后）

- 步骤：`npm run build`（分包体积无异常膨胀）→ dev 起前端对 B7 栈：列表/详情/忽略/重置/重投按钮态真数据断言（B7 预置行）→ 权限快照验证（admin 重登后菜单 17 出现）。
- 验收：截图/记档；验证毕停 dev 栈。

## e2e 章（cloud-e2e/，E1→E2，B7+F4 后）

### E1 新场景 mq-dlq（页面断言面）

- 文件清单：`cloud-e2e/run-mqdlq-e2e.mjs`（新）+ `cloud-e2e/package.json`（scripts 增 `e2e:mqdlq`）。
- 场景（黑盒走网关+浏览器；**数据前提=B7 预置行**，脚本头注写明：前置检查列表 ≥1 行待处理，缺失则记「前置缺失」SKIP 而非 FAIL——防环境漂移假红）：
  1. admin 登录 → 侧边栏「系统管理/死信治理」菜单出现 → 进入列表
  2. 列表渲染：列齐全、状态标签字典译文、代际链标记（B7 步骤 3 两行同 msgKey）
  3. 过滤：status=待处理 → 仅待处理行
  4. 详情弹窗：body 原文展示、msgId/msgKey 与列表一致
  5. 按钮：待处理行有重投+忽略无重置；已忽略/已放弃行（B7 步骤 5/6 置的）有重置无重投——**disabled 逻辑断言**
  6. 忽略操作：一行 0→2 状态标签流转（用 B7 专用行，不动其他）
  - **重投不做 e2e**（设计 D12 记档：真链路 B7 直证；e2e 无法自造死信）
- 验收：`npm run e2e:mqdlq` 全 PASS；断言锚 B7 预置 msgKey 前缀 `e2e-dlq`。

### E2 全量回归

- 命令：`cd cloud-e2e && npm run e2e`（有头全量；预热纪律先行）。
- 验收：全量 PASS/0 FAIL（本轮零既有端点改动，零 diff 预期）；FAIL 留栈排查（沿 /dev-regression 纪律）。

## 移交后续阶段的备忘（下一阶段债务清单）

1. **告警通道**：watch 落库即触发点天然具备——webhook/邮件通知接人（设计移交 1）。
2. **watch 配置完全体**：消费组接入频繁时评估编程式 consumer/starter 化（设计移交 2）。
3. **消费端异常摘要透传**：页面失败原因免查日志（设计移交 3，需动消费端另立轮次）。
4. **自动重投评估**：死信量起且均为可恢复型时重估（设计移交 4）。
5. **跨行代际上限**：按 msg_key 聚合历史重投次数上限（设计移交 5，真实滥用出现再做）。
6. **DLQ topic 与业务组名一致性守护**：yml 值漂移表现为静默不落库——评审规则+定期核对（设计 R6）。
7. **admin API 退路**：R1 若在生产环境触发（B7 本机已实证则闭环），读取层替换方案边界见设计 D2。
8. **处置 UPDATE 条件写防护（双审建议 2，2026-10-11）**：resend/ignore/reset 的 UPDATE WHERE 仅 id——并发窗口可突破状态机不变量（ resend_count>5 / 终态行仍发出消息）；设计 D7 已论证最坏有界无害（人工低频+二次确认+消费端 L1/L2 幂等兜底），后续迭代补 `WHERE resend_count < #{max}` / `WHERE status = #{expect}` 形态条件写（沿 MQ 回写三防先例）。
9. **status 拆箱 null 防护与 job 构造器风格（双审建议 3/4，2026-10-11）**：Service 五处 Integer==int 拆箱无 null 防护（DDL NOT NULL 实际不可达，防御风格与 Convert 层不一致）；DlqLogCleanJobHandler 手写构造器可换 @RequiredArgsConstructor（watch listener 因 super 传参不在此列）——顺手轮次统一。
