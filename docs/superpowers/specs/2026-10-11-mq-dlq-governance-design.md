# RocketMQ 死信治理（DLQ 监听落库 + 人工重投闭环）技术方案（2026-10-11）

> 拍板结论（阶段一三项全取架构倾向）：① 完整面——监听落库 mq_dlq_log + 前端页面（查询/详情）+ 人工重投闭环（清 dedup 残留行→syncSend 原 topic→状态流转留痕），一轮做完；"明确不做"清单（告警通道/自动重投/%RETRY% 治理/消息轨迹/消息体编辑重发/多集群/bpmn 侧改动）全部维持。② 重投=重发原 topic（保留原 KEYS/tag/body/TX_NO userProperty，新 msgId、maxReconsumeTimes 重新计数；先清 (group, msg_key) dedup 残留行防假重投；offset 回退与"只标记不重发"否决）。③ 防循环：上限 5 次 + "已放弃"终态（超限自动置终态，页面不再放行）。
> **与拍板字面的两处口径差异（明示采纳理由，主控审查可纠正）**：
> - Q6「监听清单配置化」：采纳为**注解薄壳 × N + yml placeholder 配置**（topic/consumerGroup 全走 `cloud.system.mq-dlq.watch.*` 配置键，沿投影轮 consumerGroup 占位符先例），而非编程式 DefaultMQPushConsumer 配置驱动——理由见 D3。
> - watch 失败兜底：沿阶段一 Q5 倾向（抛出重试×3→watch 组自身递归死信，Dashboard 人工兜底），无变化，方案显著记档（D4）。

## 1. 架构总览（文字架构图）

### 1.1 死信现状（改造前）

```
消费失败三分类（2026-10-09 事务消息轮定型）
  ├─ 幂等吸收 → ACK
  ├─ 确定性业务失败白名单 → 转结果事件 → ACK
  └─ 未知/系统异常 → 重试 ×3 → %DLQ%<consumerGroup> →【人工 = 空白】
                                                    （唯一手段：RocketMQ Dashboard 本机容器 8080：
                                                     无权限收口、无留痕、broker 默认 72h 后物理删除不可追溯）

两个消费组（投影轮后收敛）：
  g_bpmn_approval_create   → %DLQ%g_bpmn_approval_create   （L2 uk 幂等；create 死信=单据永挂 0，无自动兜底）
  g_system_approval_event  → %DLQ%g_system_approval_event  （L1 dedup；CREATE_RESULT 死信无兜底，TERMINAL 死信对账自愈）
```

### 1.2 改造后（watch 监听面 + 治理页面 + 重投闭环，全部落 cloud-system）

```
【监听面】cloud-system mq/ 包（注解薄壳 × 2，配置驱动）
  %DLQ%g_bpmn_approval_create   ← g_system_dlq_watch_create   ┐ onMessage：MessageExt → insert mq_dlq_log
  %DLQ%g_system_approval_event  ← g_system_dlq_watch_event    ┘ （uk_msg_id 防重；DuplicateKey 吸收 ACK）
                                                                  落库失败 → 抛出重试×3 → %DLQ%g_system_dlq_watch_*
                                                                  （递归死信，Dashboard 人工兜底——Q5 口径记档）

【治理面】cloud-system Controller/Service/Mapper（既有规范全套）
  GET  /system/mq-dlq/page      分页列表（状态/来源组/KEYS 模糊/时间范围过滤，默认待处理+死信时间倒序）
  GET  /system/mq-dlq/{id}      详情（含 body 原文）
  POST /system/mq-dlq/{id}/resend  人工重投（高危，二次确认）
  POST /system/mq-dlq/{id}/ignore  人工忽略（裁定不处理）
  POST /system/mq-dlq/{id}/reset   admin 重置（已忽略/已放弃 → 待处理，计数清零）
        └─ cloud-web /system/mq-dlq 页面（列表+详情弹窗+确认弹窗，字典驱动状态）

【重投链】MqDlqLogService.resendDlq(id)（非事务编排，三步幂等——D7）
  ① 状态校验（0/1 可重投；2/3 → 3040；resend_count≥5 → 自动置 3 已放弃 + 3041）
  ② 清 dedup 残留行：DELETE mq_consume_dedup WHERE consumer_group=born_group AND msg_key=msg_key
     （复用 starter ConsumeDedupDao；防"假重投"——删行失败残留的 dedup 行会 ACK 吞掉重投消息，
       这是 Dashboard resend 做不到/做不对的点；删 0 行无害——L2 路径本无 dedup 行）
  ③ syncSend 原 topic：destination=born_topic[:tag]，body 原文 bytes，headers 保留原 KEYS/TX_NO
     （msg_key≠msg_id ⇒ msg_key 即原始 KEYS 才 setHeader；TX_NO 非空才 setHeader）
     → 成功后 UPDATE 行：status=1、resend_count+1、last_resend_time、operate_account/time
  重投后消息再次消费失败 → 新 msgId 再进 DLQ → watch 落**新行**（同 msg_key 多行=重投代际链，
  页面按 KEYS 过滤可见全链——确定性 bug 类死信的证据链，人工据此停手修码）

【清理面】job/dlqLogCleanJobHandler（xxl-job 任务 id 10，组 3 cloud-system）
  终态行（1/2/3）保留 N 天（默认 30，对齐 mq_tx_log 先例）后物理删；待处理行（0）永不清
```

### 1.3 落库面

| 库 | 新表/任务 | 用途 |
|---|---|---|
| cloud_system | mq_dlq_log | 死信台账（watch 落库 + 处置留痕 + 重投代际链） |
| xxl_job | 任务 id 10 | dlqLogCleanJobHandler 每日 03:30（错峰 03:00 两表清理） |

## 2. 前置技术验证（阶段二已收敛，B0 退役）

**验证点：rocketmq-spring 2.3.3 + rocketmq-client 5.3.1 订阅 `%DLQ%` 系统主题可行性——静态源码实证通过**（本地 Maven 仓 sources jar，starter 2.3.3 parent pom 确认 client 版本=5.3.1）：

1. `org.apache.rocketmq.common.topic.TopicValidator`（rocketmq-common 5.3.1）：`VALID_CHAR_BIT_MAP['%'] = true`——**`%` 是 topic 名合法字符**（合法集正则 `^[%|a-zA-Z0-9_-]+$`）；`SYSTEM_TOPIC_SET` 为**按名枚举**（TBW102/SCHEDULE_TOPIC_XXXX/RMQ_SYS_* 等十个），不含 `%DLQ%`/`%RETRY%` 前缀匹配——`%DLQ%xxx` 不在客户端禁订阅集合。
2. 消费端订阅路径零校验：`DefaultMQPushConsumerImpl.subscribe` → `FilterAPI.buildSubscriptionData` 只校验 tag 表达式，**不校验 topic 名**；rocketmq-spring `DefaultRocketMQListenerContainer`（2.3.3）`consumer.subscribe(topic, selectorExpression)` 注解 topic 直接透传。
3. 生产端 `Validators.checkMessage`/`isNotAllowedSendTopic` 仅约束**发送**目标不得为系统 topic（RMQ_SYS_*）——重投目标是原业务 topic（TX_APPROVAL_CREATE/APPROVAL_EVENT_NOTIFY），无涉。
4. broker 端 DLQ 读权限：**本机 RocketMQ Dashboard 已实证可读 %DLQ%**（同 push 订阅姿势），方案注记不再单独探针。

运行时残余风险（不可静态消除的）：broker 侧 watch 组首次订阅尚未存在的 DLQ topic 的路由空转行为——预期静默空转（见 D4 口径），B7 联调冒烟顺带实证（发坏消息造死信→观察 watch 落库），不作为独立卡点门。

## 3. 决策记录

### D1 落位：cloud-system 服务内实现，不下沉 starter

- watch 监听器 + DAO/mapper + service/controller + job 全落 cloud-system（`mq/`、`mapper/`、`service/`、`controller/manage/`、`job/` 包），贴用户落点要求「在 system 服务中实现」。
- 不下沉 cloud-common-rocketmq-starter 的理由：mq_dlq_log 是**管理面业务表**（页面查询/权限/留痕），非 starter 通用契约（tx_log 回查/dedup 幂等是消息正确性组件，治理台账是运营功能）；starter 不应感知业务表。跨服务订阅无障碍（g_system_dlq_watch_* 订阅 bpmn 组的 DLQ——MQ 订阅按组不按服务，name-server 同源）。将来出现第二个管理面需求时再评估下沉，记移交备忘。
- bpmn 侧零改动（拍板维持）：bpmn 组的死信由 system 的 watch 组跨服务订阅捕获。

### D2 DLQ 读取机制：push 监听落库（拍板确认）

- 每条死信由 watch 消费组实时消费落 mq_dlq_log；页面查库（与既有列表页同模式）；突破 broker 默认 72h 保留期永久审计；重投留痕有挂载点。
- 备选否决记录：admin API（rocketmq-tools DefaultMQAdminExt）查询时直连——新依赖、逐次查询打 broker、72h 窗口外不可查、无审计；仅在 push 路线实证失败时启用（触发条件：B7 冒烟 watch 无法订阅/消费 DLQ——按 §2 静态证据预期不触发）。
- **运维语义变化（显著记档）**：watch 消费掉 DLQ 消息后 **Dashboard 上 DLQ 堆积将恒为零**——死信发现与处置入口收敛到平台页面（这正是本轮目的），"Dashboard 巡检 DLQ 堆积"的旧习惯作废；Dashboard 仍保留两个用途：watch 自身递归死信的兜底处置、消息轨迹排查。

### D3 watch listener 形态：注解薄壳 × N + yml placeholder 配置

- 结构：抽象基类 `AbstractMqDlqWatchListener`（onMessage=组装实体+insert+DuplicateKey 吸收）+ 两个薄壳（各持 `@RocketMQMessageListener` 注解，3 行差异）。清单：

| 薄壳类 | topic（placeholder） | consumerGroup（placeholder） | 默认值（application.yml 仓库化） |
|---|---|---|---|
| MqDlqWatchCreateListener | `${cloud.system.mq-dlq.watch.create.dlq-topic}` | `${cloud.system.mq-dlq.watch.create.group}` | `%DLQ%g_bpmn_approval_create` / `g_system_dlq_watch_create` |
| MqDlqWatchEventListener | `${cloud.system.mq-dlq.watch.event.dlq-topic}` | `${cloud.system.mq-dlq.watch.event.group}` | `%DLQ%g_system_approval_event` / `g_system_dlq_watch_event` |

- **watch 组名全新拟定**（拍板红线）：`g_system_dlq_watch_create`/`g_system_dlq_watch_event`，与既有消费组零重名——同 group 订阅关系不一致是 RocketMQ 明令禁止的 rebalance 事故源。配置键走 yml（非密钥仓库化，沿投影轮先例），改组名/换组免改码；**DLQ topic 名与来源组名的派生关系**（`%DLQ%` 前缀 strip）在落库时由代码派生 born_group，不重复配置（减漂移面）。
- 为什么不是编程式 DefaultMQPushConsumer 配置循环建 N 实例（与拍板"配置化"字面的差异，明示）：a) rocketmq-spring 注解是本仓唯一消费惯例（两个既有消费者同模式），编程式自管 lifecycle（SmartLifecycle start/shutdown）+ 绕开 `@RocketMQMessageListener` 容器管理；b) 守护规则 v6（@RocketMQMessageListener 必须走幂等双层）按注解源码扫描，编程式成守护盲区需另立规则；c) 新消费组接入本来就要写消费者类，同一 PR 顺手复制一个 3 行 watch 壳零额外成本；d) "不硬编码 2 组"的实质诉求是扩展零成本扩散——DAO/页面/重投全通用零改动，watch 壳是唯一增量（半配置化折中）。配置文件驱动的完全体记移交备忘。
- 幂等合规：watch 消费动作=insert mq_dlq_log，`uk_msg_id` 即幂等约束（DuplicateKey=已落库 ACK 吸收）——语义等价 L2 业务 uk，薄壳标注 `@UkIdempotentListener`（守护规则 v6 合规，注解 value 写明 uk 依据）。
- maxReconsumeTimes=3：watch 落库失败（DB 挂）重试 3 次后进 `%DLQ%g_system_dlq_watch_*` 递归死信——Q5 拍板口径：不递归监听自己的死信（终止递归），Dashboard 人工兜底（broker 保留期内可见），记档此为已知边界。
- messageModel=CLUSTERING、consumeMode=CONCURRENTLY（注解默认，显式认知不显式声明）。

### D4 watch 消费起点与空转口径（记档）

- **offset 起点 = CONSUME_FROM_LAST_OFFSET（默认）**：watch 只捕获上线后的新死信，上线前历史死信不回放（避免上线瞬间灌入存量）。生产上线时记档处置：上线前经 Dashboard 一次性清掉存量 DLQ，或接受历史不回放。
- **DLQ topic 尚不存在**（该组从未产生死信）时：push consumer 从 namesrv 拿不到路由，静默空转无异常；broker 在首条死信产生时自动创建 DLQ topic，watch 在下次路由刷新（默认 30s 级）后开始消费——无需预建 topic，启动零阻断。

### D5 mq_dlq_log DDL 与索引（详见 §4）

- 独立新表（否决复用 mq_tx_log：生产回查表 vs 消费死信台账语义混轴、列需求全不同）。
- **无 deleted 列、不继承 BaseEntity**：沿 mq_tx_log/mq_consume_dedup 两表先例（MQ 审计表物理清理、无逻辑删除语义；处置操作人语义由 operate_account 列承载，非 create_by）。
- **重投代际链**：重投产生的新消息新 msgId，再次死信落**新行**（uk_msg_id 按 msgId 不按 KEYS——刻意设计：同 msg_key 多行即重投代际证据链，页面按 msg_key 过滤可见；若按 msg_key upsert 会抹掉代际信息）。
- **msg_key 列口径与 DedupRocketMQListener.resolveMsgKey 完全一致**（KEYS 优先、缺省退化 msgId）：重投时清 dedup 行的键 (born_group, msg_key) 与 L1 表内行天然对齐；重发时是否恢复 KEYS header 由 `msg_key != msg_id` 运行时判定（相等 ⇒ 原消息无 KEYS，退化场景），零冗余列。
- **失败原因不落库**：DLQ 消息内不含原始异常栈（消费端 catch 时未写入消息属性）；页面提示按 msgId/KEYS 检索服务日志。把异常摘要透传进消息属性需改消费端（范围外，拍板"不动消费端"），记移交备忘。
- **组装口径 B7 实证修正（2026-10-11 实现轮记档，两处）**：
  - **born_topic 不得取 `MessageExt.getTopic()`**（计划 B3 原文为文本 bug）——DLQ 消息的 topic 已被 broker 改写为 `%DLQ%组名`，照抄会把重投目标发进 DLQ 主题；正确口径=**userProperty `RETRY_TOPIC` 优先**（broker 在重试/死信转投时写入原 topic）**、缺省退化当前 topic**（普通消息场景兜底）。B7 落行 born_topic=TX_APPROVAL_CREATE 实证正确，重投 destination 正确指向原 topic。
  - **reconsume_times 实测值=4 非 3**：broker 透传 `getReconsumeTimes()` 为"投递计数"口径（1 首发+3 重试），死信时恒 4——DDL 列注释与契约例值已同步修正（int 透传，前端仅展示不受影响）。

### D6 处置状态机（拍板③展开）

```
                 ┌──────────────────────────────────┐
                 │  0 待处理 PENDING（watch 落库态）  │
                 └────┬───────────────┬─────────────┘
            resend（≤5）│               │ ignore
                 ┌────▼─────┐   ┌─────▼──────┐
                 │ 1 已重投  │   │ 2 已忽略    │（终态）
                 │ RESENT   │   │ IGNORED    │
                 └────┬─────┘   └─────▲──────┘
      resend_count≥5 │ 自动置         │ reset（admin）
                 ┌───▼──────────┐    │
                 │ 3 已放弃      ├────┘（→ 0，resend_count 清零）
                 │ ABANDONED    │（终态）
                 └──────────────┘
```

- **已重投（1）态可继续重投**（count<5，拍板上限语义落地：同一行最多 5 次重投）；第 6 次尝试（count=5）时自动置 3 已放弃并拒（3041）。
- **忽略仅作用于 0 态**（已重投行不允许忽略——语义互斥：忽略=这条不需要处理，已重投=已产生新投递）；已忽略/已放弃为终态，仅 reset 可回。
- reset 同时清零 resend_count（否则回 0 后立刻又触上限，失去重置意义）。
- 跨行防循环说明（诚实记档）：上限防的是**单行**反复重投；重投后再死信是新行、计数重新计——若底层 bug 未修，同 msg_key 代际链会持续延长（每代最多 5 次）。这是设计选择而非缺陷：自动跨行封顶需按 msg_key 聚合计数（复杂化且误伤「修复后重投成功再无死信」的正常链），人工在页面看到同 KEYS 多行即应停手修码——页面列表对同 msg_key 多行做显著展示（F 章需求）。

### D7 重投编排：非事务三步 + 幂等论证（规范「多表写 @Transactional」的例外记档）

- 顺序：状态校验 → 清 dedup 行 → syncSend → UPDATE 行状态。**不包 @Transactional**，理由：中间夹外部调用（MQ 发送）本不可原子化；各步独立幂等——清行删 0 行无害、发送失败行不动（dedup 已删无害，下次重投再删一次幂等）、发送成功后行更新失败的窗口=该行可再点一次重投（至多重发一次，消费端 L1/L2 幂等层兜底——这正是消息体系设计好的安全网）。最坏情况有界且无害，不引入分布式事务复杂度。
- producer 名义注记（拍板指定）：system 的 RocketMQTemplate producer group=p_system_tx（Nacos）；向 TX_APPROVAL_CREATE 重发本就是该 topic 生产组；**向 APPROVAL_EVENT_NOTIFY 重发以 p_system_tx 名义**——生产组与消费无绑定（仅发送方逻辑分组），正确性无影响，仅审计面上 event topic 出现 p_system_tx 组的发送记录，语义瑕疵接受（备选第二个 template 过度设计，记档不取）。
- 消息恢复完整度：body（UTF-8 bytes 原文）、tag（destination 拼接）、KEYS（msg_key≠msg_id 时恢复）、TX_NO userProperty（tx_no 非空时恢复）——消费端不感知消息为重投（无 marker 属性，刻意：零消费端改动，拍板维持）。新 msgId、maxReconsumeTimes 从零重计。

### D8 已放弃终态解除：admin reset 端点（拍板留裁定的裁定）

- **裁定：提供 reset 端点**（`POST /system/mq-dlq/{id}/reset`，权限 `system:mqDlq:reset` 独立标识，仅已忽略/已忽略外之已放弃可回 0 并清零计数），**否决"仅 DB 直改"**。理由：a) DB 直改违背本轮价值主张（权限收口+留痕+防误操作正是要消灭的绕页面姿势）；b) 实现成本一个方法+一个按钮；c) operate_account/time 自然留痕谁在何时解除。安全边界：reset 权限默认仅 admin 角色映射，与重投权限分离。
- reset 一并覆盖"误忽略恢复"（已忽略→待处理），语义统一为"解除终态"。

### D9 权限/菜单/字典/错误码

- 权限标识（`<svc>:<entity>:<action>`，实体名 camel 对齐 dataPerm 先例）：

| 标识 | 端点 | 说明 |
|---|---|---|
| system:mqDlq:list | page/detail | 菜单级 |
| system:mqDlq:resend | resend | 按钮级（高危） |
| system:mqDlq:ignore | ignore | 按钮级 |
| system:mqDlq:reset | reset | 按钮级（admin 解除终态） |

- 菜单种子（幂等格式沿先例，admin 绑定走显式 id 列表）：系统管理目录（id=10）下 `17 死信治理`（C，path=/system/mq-dlq，perms=system:mqDlq:list，sort=7，icon=Message）+ 按钮 `171 重投`/`172 忽略`/`173 重置`。落库后 admin 需重新登录（权限快照时效，头注提示沿 bpmn-deploy-menu 先例）。
- 字典种子：`mq_dlq_status`（0 待处理/1 已重投/2 已忽略/3 已放弃，is_builtin=1）——页面状态标签全字典驱动。
- 错误码（system 段 3038 起接续，账本至 3037）：

| code | msg（逐字） | 场景 |
|---|---|---|
| 3038 | 死信记录不存在 | resend/ignore/reset 查行缺失 |
| 3039 | 消息重投发送失败 | syncSend 异常（log.error 根因后转译） |
| 3040 | 死信状态不允许该操作 | 状态机拒绝（终态 resend/非 0 态 ignore/非终态 reset） |
| 3041 | 重投次数已达上限，已置为已放弃 | resend_count≥5 自动置 3 后拒 |

### D10 清理任务：dlqLogCleanJobHandler（xxl-job 任务 id 10）

- system 服务 `job/` 包新薄壳（沿 CloudDemoJobHandler/approvalProjectionReconcileJobHandler 先例，@XxlJob 注解、@Scheduled 禁令合规）。**不并入 starter 的 mqTableCleanJobHandler**：mq_dlq_log 是 system 业务表，starter（通用组件）不得感知业务表（分层）；且 starter job 由两库各自宿主执行，dlq 表仅 cloud_system 有。
- 语义：`DELETE FROM mq_dlq_log WHERE status IN (1,2,3) AND dlq_time < NOW()-N 天`——**待处理行（0）永不清**（未处置死信被清=丢告警）；保留天数默认 30 天（对齐 mq_tx_log 先例），走 `cloud.system.mq-dlq.retention-days` 配置（Nacos 可覆盖，非密钥仓库化默认值）。
- 种子：xxl_job 库 id 10、job_group=3（cloud-system）、CRON `0 30 3 * * ?`（错峰 03:00 两表清理）、handler `dlqLogCleanJobHandler`、trigger_status=1；基线 2026-10-09-xxl-job-init.sql 同步追加（不可重放头注沿先例）。
- 删除行数 log.info 记档（沿 MqTableCleanJob 口径）。

### D11 配置落位

```yaml
# cloud-system application.yml（仓库化，架构口径非密钥）
cloud:
  system:
    mq-dlq:
      watch:
        create:
          dlq-topic: "%DLQ%g_bpmn_approval_create"   # watch 订阅的死信主题（与来源组名手工同步）
          group: g_system_dlq_watch_create            # watch 消费组（全新拟定，禁与业务消费组重名）
        event:
          dlq-topic: "%DLQ%g_system_approval_event"
          group: g_system_dlq_watch_event
      resend-max: 5          # 单行重投上限（超限自动置已放弃）
      retention-days: 30     # 终态行清理保留天数
```

- 绑定 `@ConfigurationProperties("cloud.system.mq-dlq")` 对象（规范 6：同前缀多值对象封装，禁散装 @Value）。
- watch 组与 DLQ topic 的手工同步风险（yml 值与业务消费组声明一致性）无法静态守护（跨模块注解值 vs yml），记评审规则：新增消费组接入死信面时，watch 壳+yml 两处同 PR 变更，评审核对 `%DLQ%` 前缀拼接正确。

### D12 测试策略（分层；造死信手段是本轮特有问题）

| 层 | 覆盖 |
|---|---|
| 单测（system） | watch listener（落库组装/DuplicateKey 吸收/异常抛出重试）、resendDlq（3038/3040/3041 分支、dedup 清行调用、send 异常 3039、成功后行更新）、状态机（ignore/reset 边界）、pageList 过滤与排序、清理 job SQL 参数 |
| B7 联调（真链路，起服） | **造死信手段**：java 单文件 producer 直发 TX_APPROVAL_CREATE 一条 body 非 JSON 的坏消息（带 KEYS）→ ApprovalCreateConsumer jsonPayloads.parse 异常 → ×3 → %DLQ%g_bpmn_approval_create → **watch 落库断言**（库表新行/页面可见）→ 页面重投坏消息 → 再死信 → **新行出现（代际链直证）**；SQL 造一行合法 body（ApprovalCreateInnerRequest JSON、businessKey 用 e2e 前缀）→ 页面重投 → bpmn 建单成功 + CREATE_RESULT 事件 → 投影收敛（**成功重投闭环直证**）；ignore/reset 页面操作断言 |
| e2e（cloud-e2e） | 页面断言面（数据前提=B7 预置行，本机库共享）：菜单出现/列表渲染/过滤/详情弹窗 body 展示/状态标签字典/按钮 disabled 逻辑（终态无重投按钮）/忽略操作状态流转。**重投不做 e2e**（真链路 B7 已证；e2e 无法自行造死信——需 MQ 客户端或停服注错，黑盒纪律外），记档取舍 |
| 回归 | 全量 clean install 零守护红 + cloud-e2e 既有场景零 diff |

## 4. DDL 与索引（每列 COMMENT、取舍注明）

```sql
-- 落 cloud_system 库（watch 宿主与治理面同库；与 mq_consume_dedup 同库使重投清行零跨库）
CREATE TABLE mq_dlq_log (
    id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    msg_id           VARCHAR(64)  NOT NULL COMMENT '死信原消息 msgId（broker 全局唯一；uk 防 watch 重复投递双行；重投产生的新消息为新 msgId 落新行=重投代际链）',
    tx_no            VARCHAR(64)  NULL     COMMENT '事务消息流水号（原消息 userProperty TX_NO，可空；重投时恢复该属性；溯源 mq_tx_log 半消息审计）',
    born_topic       VARCHAR(64)  NOT NULL COMMENT '原 topic（重投目标，TX_APPROVAL_CREATE/APPROVAL_EVENT_NOTIFY）',
    born_group       VARCHAR(64)  NOT NULL COMMENT '死信来源消费组（DLQ topic 名 %DLQ% 前缀 strip 派生；重投前清 mq_consume_dedup 残留行的分组键）',
    watch_group      VARCHAR(64)  NOT NULL COMMENT 'watch 消费组（g_system_dlq_watch_*，落库行来源定位）',
    tag              VARCHAR(64)  NULL     COMMENT '消息 tag（可空；重投时拼 destination=topic:tag）',
    msg_key          VARCHAR(190) NOT NULL COMMENT '消息幂等键（与 DedupRocketMQListener.resolveMsgKey 同口径：KEYS 优先、缺省退化 msgId；重投清 dedup 行与恢复 KEYS header 的判定依据：msg_key≠msg_id 即原始 KEYS）',
    body             TEXT         NULL     COMMENT '消息体原文（UTF-8 解码；超 60000 字节截断——契约消息均为小 JSON，防御上限）',
    reconsume_times  INT          NOT NULL DEFAULT 0 COMMENT '死信时重试投递计数（broker 透传 getReconsumeTimes；maxReconsumeTimes=3 时死信恒 4=1 首发+3 重试）',
    born_time        DATETIME     NOT NULL COMMENT '消息产生时间（bornTimestamp，业务发生时间）',
    dlq_time         DATETIME     NOT NULL COMMENT '进死信时间（storeTimestamp，DLQ 落 broker 时间，列表主排序与清理锚点）',
    status           TINYINT      NOT NULL DEFAULT 0 COMMENT '处置状态（0待处理 1已重投 2已忽略 3已放弃——resend_count 达上限自动置 3；实体 StatusEnum）',
    resend_count     INT          NOT NULL DEFAULT 0 COMMENT '重投次数（上限 resend-max=5）',
    last_resend_time DATETIME     NULL     COMMENT '最近重投时间',
    operate_account  VARCHAR(64)  NULL     COMMENT '处置操作人（重投/忽略/重置操作账号；watch 落库不填——非处置动作）',
    operate_time     DATETIME     NULL     COMMENT '最近处置时间',
    create_time      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（watch 落库时间）',
    update_time      DATETIME     NULL     COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_msg_id (msg_id),
    KEY idx_status_dlq_time (status, dlq_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 死信治理台账（watch 监听 %DLQ% 落库；人工重投/忽略/重置留痕；终态行 N 天后定时清理，待处理行永不清）';
-- 索引取舍：uk_msg_id 命中 watch 落库幂等（每次消费一次插入冲突判定，uk 即约束即索引）；
--   idx_status_dlq_time 命中列表主路径 WHERE status=? ORDER BY dlq_time DESC（默认待处理+时间倒序）
--   与清理 DELETE WHERE status IN (1,2,3) AND dlq_time<?（复合索引首列 IN 列表多 range 扫描，MySQL 5.7 支持）；
--   born_group/msg_key 过滤走回表（人工级行数，量级远低于分页扫描代价，不单独建索引）；
--   无按 create_time 查询路径不建（沿 mq_tx_log 先例）。uk 单列 64*4=256B 无长度风险。
-- 逻辑删除：无 deleted 列——沿 mq_tx_log/mq_consume_dedup 先例（MQ 审计表物理清理，无 @TableLogic 语义）。
```

## 5. 错误处理汇总

| 故障 | 行为 | 用户可见 |
|---|---|---|
| watch 落库失败（DB 挂） | 抛出重试 ×3 → %DLQ%g_system_dlq_watch_*（递归死信，Dashboard 兜底，Q5 口径） | 页面缺该行；Dashboard 可见 watch 死信 |
| watch 重复投递（broker 重投） | uk_msg_id DuplicateKey 吸收 ACK | 无感 |
| 重投时 MQ 不可达 | syncSend 异常 → log.error → 3039，行状态不动 | 提示重投失败可重试 |
| 重投后行更新失败（发送成功的窗口） | 行滞后可再点重投（至多重发一次，L1/L2 幂等兜底） | 无感（页面重拉即最新） |
| dedup 残留行未清干净（清行也失败） | 重投消息被 L1 ACK 吞（假重投）——与现状同底线，概率双重 DB 故障级 | 行状态已置已重投；记日志排查 |
| 确定性 bug 类死信重投 | 再死信落新行（代际链），5 次后自动封死 | 页面同 KEYS 多行显著提示 |
| 待处理行被误清理 | 不发生（清理只删终态行） | — |
| watch 上线前历史死信 | LAST_OFFSET 不回放（D4 记档） | Dashboard 一次性处置 |

## 6. 风险与未知

| # | 风险 | 处置 |
|---|---|---|
| R1 | rocketmq-spring 注解订阅 `%DLQ%` 运行时行为与静态源码结论不符（如 broker 拒绝订阅） | §2 静态证据链已消除主要不确定性；B7 冒烟先行实证；退路=admin API 方案（D2 记档触发条件），三文档不需返工（表/页面/状态机全复用，仅读取层替换） |
| R2 | watch 组与业务组意外重名（配置错误） | 组名全新命名段 `g_system_dlq_watch_*`；yml 注释警示；评审规则记档（D11） |
| R3 | 坏消息重投风暴（人工连点） | 单行 5 次上限 + 确认弹窗 + 权限单列；页面同 KEYS 多行显著展示 |
| R4 | 大消息 body 落库截断丢失 | 60000 字节截断记档（契约消息 <2KB，防御上限）；截断后重投 body 不完整属极端场景，详情页展示截断标记（F 端按 body 长度提示） |
| R5 | watch 组自身递归死信无人发现 | D2 记档：Dashboard 保留此兜底用途；告警通道记移交备忘 |
| R6 | yml 中 DLQ topic 与业务组名漂移（手改错） | 评审规则记档（D11）；错误表现为该组死信不再落库（静默），定期核对清单记移交 |

## 7. 测试与验收口径（e2e 断言零破坏核对）

- 既有场景零接触：本轮不改任何既有接口/表/消费者（bpmn 零文件改动、system 既有端点零改动）——全量 e2e 零 diff 是硬验收。
- 新页面 e2e（mq-dlq 场景）：数据前提由 B7 预置（本机库共享）；断言面见 D12。
- 消息不删纪律沿既有：断言锚 e2e 前缀（B7 造死信 businessKey 用 `e2e-dlq-<ts>` 前缀）。

## 8. 移交后续阶段的备忘

1. **告警通道**：死信新增（watch 落库即知）天然具备触发点——接 webhook/邮件通知（本轮页面人工巡检）。
2. **watch 配置完全体**：新消费组接入若频繁，评估编程式 consumer 或 starter 化（多管理面需求出现时一并重估 D1 落位）。
3. **消费端异常摘要透传**：消费 catch 时把异常摘要写消息 userProperty（进 DLQ 后 watch 可落库展示失败原因）——需动消费端（本轮拍板不动），收益是页面免查日志。
4. **自动重投评估**：若未来死信量大且均为可恢复型，评估「按失败分类白名单自动重投 + 冷却退避」——当前人工确认是安全基线（拍板）。
5. **跨行防循环**：按 msg_key 聚合的历史重投代数上限（D6 记档的设计取舍）——真实出现代际链滥用时重估。
6. **mq_dlq_status 字典扩项**：若增加新处置状态（如"处理中"），字典+实体枚举+前端按钮可见性三处同步。
7. **retention-days 治理集中化**：是否并入 admin 任务参数（对齐 mq 两表清理移交备忘 3 同款议题）。
