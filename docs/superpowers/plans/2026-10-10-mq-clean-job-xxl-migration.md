# MqTableCleanJob 迁移 xxl-job（@Scheduled 存量清零收官）实施计划（2026-10-10）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 最后一个 `@Scheduled` 任务 `MqTableCleanJob`（rocketmq-starter 两表清理，每日 03:00）迁到 xxl-job——**全仓 @Scheduled 清零收官**，调度节奏治理权归 admin 控制台。

**Architecture:** 沿 2026-10-10 对账迁移轮已实证的模式：@Bean 注册的 bean 方法级 `@XxlJob` 可被宿主 XxlJobSpringExecutor 收集（无需薄壳新类——MqTableCleanJob 本身即任务类，cleanExpired() 直接换注解）。**两组各一任务**（id 8 组 3 cloud-system / id 9 组 4 cloud-bpmn，handler 同名 `mqTableCleanJobHandler`——两组同名合法，demo 任务已实证）：语义=每实例清自己库的表，精确匹配（分片广播暗示数据集分片语义，不采用）。方法体一字不动（两库均有 mq_tx_log、bpmn dedup 段装配期判空跳过——无 SQL 报错面，异常语义无差异）；不引入 XxlJobHelper 调用（log.info 已留痕，starter 保持零 xxl 运行时强依赖）。

**Tech Stack:** xxl-job-core 3.5.0、rocketmq-starter 既有组件。

**输入:** 对账迁移轮先例 `docs/superpowers/plans/2026-10-10-approval-reconcile-xxl-job.md`（执行结论含 CRON 步进陷阱/admin REST 口径/trigger_status 显式列等全部记档）；`MqTableCleanJob.java` / `CommonRocketMqAutoConfiguration.java` / `CommonRocketMqProperties.java` 现状。

**边界:** 不动 REST 契约（无 e2e）；不动 cleanExpired 业务逻辑与保留天数键（txlog/dedup retention 仍走 Nacos 配置）；不动 dedup 段装配判空逻辑。

**预验证（已由主控完成）:** 全仓 `@Scheduled` 仅此一处；引 rocketmq-starter 的模块 = system/bpmn/bpmn-api（均已有 xxl-job-core 类路径，传递零冲突）；两库均有 mq_tx_log 表（`2026-10-09-rocketmq-tx-approval.sql` 头注：cloud_system 流1 + cloud_bpmn 流2，mq_consume_dedup 仅 cloud_system）；守护规则 v8 天然覆盖（`rocketmq/job/` 包路径含 `/job/` 且不在服务模块扫描面）。

---

## B1 starter 迁移（注解替换 + 键退役 + @EnableScheduling 收官摘除）

**Files:**
- Modify: `cloud-base/cloud-common/cloud-common-rocketmq-starter/pom.xml`（+xxl-job-core 依赖）
- Modify: `cloud-base/cloud-common/cloud-common-rocketmq-starter/src/main/java/com/cloudai/common/rocketmq/job/MqTableCleanJob.java`（换注解）
- Modify: `cloud-base/cloud-common/cloud-common-rocketmq-starter/src/main/java/com/cloudai/common/rocketmq/config/CommonRocketMqProperties.java`（删 cleanCron）
- Modify: `cloud-base/cloud-common/cloud-common-rocketmq-starter/src/main/java/com/cloudai/common/rocketmq/config/CommonRocketMqAutoConfiguration.java`（摘 @EnableScheduling）

- [x] **步骤 1：starter pom 加 xxl-job-core**（compile；消费方 system/bpmn 均已引 xxljob-starter 双保险；仅 @XxlJob 注解引用，无 XxlJobHelper 运行时调用——未接 xxl-job 的宿主引本 jar 无副作用，注解缺失时无人反射读取即无感知）

```xml
<!-- xxl-job 调度注解（2026-10-10 迁移轮：MqTableCleanJob @Scheduled → @XxlJob，@Scheduled 存量清零；
     仅注解引用无 XxlJobHelper 调用，未接 xxl-job 的宿主无运行时依赖；版本走根 dependencyManagement） -->
<dependency>
    <groupId>com.xuxueli</groupId>
    <artifactId>xxl-job-core</artifactId>
</dependency>
```

- [x] **步骤 2：MqTableCleanJob 换注解**——imports：删 `org.springframework.scheduling.annotation.Scheduled`，加 `com.xxl.job.core.handler.annotation.XxlJob`；类 javadoc 与方法注解替换：

```java
/**
 * 两表定时清理（2026-10-10 迁移轮起 xxl-job 调度：admin 任务 id 8（组 cloud-system）/ id 9（组 cloud-bpmn）
 * CRON 每日 03:00 0 0 3 * * ?，handler 名两组同名——每实例清自己库的表；clean-cron 配置键退役）：
 * tx_log 保 N 天（回查依据 + 审计）、dedup 保 N 天（幂等窗口）。dedup 表仅事件消费方库存在——
 * 无 Dedup 消费者上下文时跳过该段。保留天数（txlog/dedup retention days）仍走
 * cloud.common.rocketmq.* 配置（Nacos），仅调度节奏治理权移交 admin 控制台。
 */
```

```java
    /** 每日低峰清理（默认 03:00；删除行数 log.info 记档；不抛异常即成功——xxl 缺省成功口径） */
    @XxlJob("mqTableCleanJobHandler")
    public void cleanExpired() {
```

**方法体一字不动**（34-39 行原样）。

- [x] **步骤 3：Properties 删 cleanCron 字段**（连同 javadoc「两表清理调度 cron…」行；enabled/dedupRetentionDays/txlogRetentionDays 保留）。

- [x] **步骤 4：AutoConfiguration 摘 @EnableScheduling**——删注解与 import `org.springframework.scheduling.annotation.EnableScheduling`；类 javadoc 末补记档行：

```
 * 2026-10-10 迁移轮：@EnableScheduling 摘除——全仓 @Scheduled 已清零（新定时任务必须 xxl-job，
 * 见 backend-spec 步骤 9）；宿主将来若自建 @Scheduled 需自行开调度（当前规范禁止）。
```

- [x] **步骤 5：模块构建**——`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-rocketmq-starter -am` 全绿；随后全量 `clean install`（预期 404 维持零回归，无新增用例——守护规则 v8 已覆盖本形态无需扩展）。

## B2 SQL 种子（两组各一任务）+ 落库

**Files:**
- Create: `cloud-base/scripts/sql/2026-10-10-mq-clean-xxl-job.sql`
- Modify: `cloud-base/scripts/sql/2026-10-09-xxl-job-init.sql`（种子段追加 id 8/9）

- [x] **步骤 1：增量脚本**（沿用对账轮已修正形态：trigger_status 显式列、glue_updatetime=now()、CRON 6 段无步进陷阱）

```sql
-- =====================================================================
-- MQ 两表清理任务种子（2026-10-10 迁移轮：MqTableCleanJob @Scheduled → xxl-job，@Scheduled 存量清零收官）
-- 前置：2026-10-09-xxl-job-init.sql 已执行。
-- 不可重放：固定主键 id 8/9——重放需先 DELETE FROM xxl_job_info WHERE id IN (8, 9)。
-- 形态：两组各一任务（每实例清自己库的表），handler 名两组同名（demo 任务已实证同名合法）；
--       CRON 保持原 @Scheduled 默认节奏每日 03:00；clean-cron 配置键退役（治理权移交 admin）。
-- =====================================================================
USE `xxl_job`;

INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`, `trigger_status`)
VALUES (8, 3, 'system-mq-table-clean', now(), now(), 'cloudai', '', 'CRON', '0 0 3 * * ?',
        'DO_NOTHING', 'FIRST', 'mqTableCleanJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1),
       (9, 4, 'bpmn-mq-table-clean',   now(), now(), 'cloudai', '', 'CRON', '0 0 3 * * ?',
        'DO_NOTHING', 'FIRST', 'mqTableCleanJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '',
        1);

commit;
```

- [x] **步骤 2：基线 init 脚本种子段同步追加 id 8/9 同款两行**（id 7 之后；注释注明 2026-10-10 迁移轮增补）。
- [x] **步骤 3：java 单文件 + mysql-connector-j 执行增量脚本落库** → 回查 `SELECT id,job_group,executor_handler,schedule_conf,trigger_status FROM xxl_job_info WHERE id IN (8,9)`（两行 handler/CRON/RUNNING=1 核对）。

## B3 Nacos 清理失效键

- [x] 三配置（cloud-common.yaml / cloud-system.yaml / cloud-bpmn.yaml）**读出核实** `cloud.common.rocketmq.clean-cron`——在配则删该键后**读出→合并→写回→回读核对**（其余键一字不动：JWT/Redis/RocketMQ/xxl.job.*/projection/retention 天数键全保留）；从未在配则不写回（对账轮同款处置，diff 为空记档）。

## B4 构建联调验收

- [x] **步骤 1：全量构建全绿**（404 零回归）。
- [x] **步骤 2：起服**（9201→9202→9203→18080，网关最后；admin 容器 18081 常驻已在）。
- [x] **步骤 3：双组手动触发（主证）**——admin「执行一次」id 8 与 id 9 → 两任务调度日志 trigger/handle 双 code=200；**双服务日志各现「mq_tx_log 清理完成: rows=」痕**（system 另有 dedup 段输出、bpmn 仅 tx_log 段——判空跳过实证）；admin 执行日志无 handler not found。
- [x] **步骤 4：CRON 配置态核验**——admin 任务管理页 id 8/9 调度状态 RUNNING、CRON 显示每日 03:00（自动轮次明晨首轮，不等待——手动触发已证链路）。
- [x] **步骤 5：MQ 链路冒烟零破坏**——leave 建单（curl 全 ASCII，e2e 前缀+时间戳）→ status 正常（事务消息链路与清理任务互不干扰；MQ 冷启动 3025 重试即愈为既有已知）。
- [x] **步骤 6：收口**——kill 四服务（netstat+taskkill），admin 容器保留。

## B5 文档更新

**Files:**
- Modify: `CLAUDE.md`（两处）
- Modify: `.claude/skills/backend-spec/SKILL.md`（步骤 9 表述更新）
- Modify: 本 plan（末尾补执行结论）

- [x] **步骤 1：CLAUDE.md**——①xxl-job 段末句「@Scheduled 双轨仅剩 rocketmq starter `MqTableCleanJob`（迁移候选见…）」→「@Scheduled 已**全量清零**（2026-10-10 MqTableCleanJob 迁移收官：两组各一任务 id 8/9 handler `mqTableCleanJobHandler`，@EnableScheduling 自 starter 摘除——新定时任务一律 xxl-job，守护/backend-spec 强制）」；②「事务消息与消费幂等」段两表口径处补一句「两表清理走 xxl-job（clean-cron 键退役，保留天数仍走 cloud.common.rocketmq.*）」。
- [x] **步骤 2：backend-spec 步骤 9**——「@Scheduled 存量仅剩 cloud-common-rocketmq-starter MqTableCleanJob，迁移候选记移交」→「@Scheduled 已全量清零（2026-10-10 收官），新任务唯一形态 xxl-job」；检查清单对应条目微调。
- [x] **步骤 3：本 plan 补执行结论**（双组触发证据/日志双证/计数/Nacos 结论/收口）。

---

## 移交后续阶段的备忘（实现者完成后核对补执行结论）

1. **admin 告警通道**（沿 xxl-job 整合轮移交备忘 7）：任务失败目前仅 admin 页面红记录可见，alarm_email 未配——生产化前接告警（邮件/webhook）。
2. **清理任务失败可见性**：cleanExpired 无 try-catch，xxl 下异常=handle 失败（admin 红记录）——较 @Scheduled 吞异常是**增强**（可见性）；若两库表结构漂移导致持续红，admin 侧可暂停任务止血。
3. **retention 天数配置面**：txlog/dedup 保留天数仍走 Nacos（cloud.common.rocketmq.*）——是否也治理权集中化（如任务参数 executor_param 传入）待生产化评估，当前 Nacos 即可。

---

## 执行结论（2026-10-10，backend-agent）

**全任务完成（B1→B5），全绿收口。**

- **代码改动**：rocketmq-starter 4 文件——pom.xml（+xxl-job-core compile，版本走根 dependencyManagement）/ MqTableCleanJob（@Scheduled → `@XxlJob("mqTableCleanJobHandler")`，**方法体一字不动**——git diff 自证仅 import 区/类 javadoc/方法 javadoc+注解行三段）/ CommonRocketMqProperties（cleanCron 字段与 javadoc 退役；类 javadoc 尾句「与调度」同步修正为「归 xxl-job admin」——删字段直接语义后果记档）/ CommonRocketMqAutoConfiguration（@EnableScheduling 摘除 + javadoc 记档行）。
- **构建与测试**：模块构建 SUCCESS；全量 `clean install` BUILD SUCCESS——surefire 合计 **404 例 0 失败**（上轮 404 维持，零回归、无新增用例，守护规则 v8 天然覆盖本形态）。SQL 种子落 xxl_job 库，回查 id 8/9 双行 handler=`mqTableCleanJobHandler`、CRON=`0 0 3 * * ?`、trigger_status=1。
- **Nacos**：三配置（cloud-common/cloud-system/cloud-bpmn.yaml）逐一读出核实——`cloud.common.rocketmq.clean-cron` **均从未显式配置**（@Scheduled 一直走占位符默认），按对账轮同款处置**不写回，diff 为空**，其余键零触碰。
- **联调验收（主证）**：四服务起序 9201→9202→9203→18080，双服务日志均现 `register jobhandler success, name:mqTableCleanJobHandler`（@Bean 注册的方法级 @XxlJob 被宿主 executor 收集复证）。admin 手动触发 id 8 与 id 9 双 `triggerCode=200/handleCode=200`（joblog id 104/105，executor 地址 19202/19203 各归其组）。**日志双证**：system 两行（`mq_tx_log 清理完成: cutoff=2026-09-10T11:34:45, rows=0` + `mq_consume_dedup 清理完成: cutoff=2026-10-03T11:34:45, rows=0`——dedup 段执行，system 是事件消费方）；bpmn 仅一行（`mq_tx_log 清理完成: cutoff=2026-09-10T11:34:56, rows=0`——**dedup 段装配期判空跳过实证**）。admin 任务管理页 id 8/9 双 RUNNING + CRON 0 0 3 * * ? 截图核验（`admin-jobinfo-system-id8-running.png` / `admin-jobinfo-bpmn-id9-running.png`）。
- **MQ 冒烟零破坏**：admin 登录建 leave 单 `e2e-mqclean-1791603726`（首次 3025 冷启动已知、重试 200），详情 approvalId=114 回填、status=0 审批中——事务消息链路与清理任务互不干扰。
- **收口**：9201/9202/9203/18080 四端口 taskkill 清空（netstat 复核零 LISTENING），xxl-job-admin 容器常驻未触碰（Up 12 hours）。
- **执行中发现的小记档**：①admin 3.5.0 REST `jobinfo/trigger` 必须带 `addressList=` 空串参数（缺省 400），`joblog/pageList` 必须带 `filterTime=`（同因），对账轮记档未覆盖此两点，已在本结论补录；②启动时序现象：system 11:33:00 首轮 CRON 对账时 bpmn 尚未注册完 Nacos，Feign 503 降级本轮放弃（对账轮已知口径），11:35:00 下一轮正常，与本轮无关。
