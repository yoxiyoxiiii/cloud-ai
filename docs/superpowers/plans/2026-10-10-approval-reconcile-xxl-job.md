# 审批投影对账任务迁移 xxl-job 实施计划（2026-10-10）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `ApprovalProjectionReconciler.reconcileActive()` 从 `@Scheduled fixedDelay 60s` 迁到 xxl-job CRON 调度（调度节奏治理权 Nacos → admin 控制台），对账业务语义零变化。

**Architecture:** @XxlJob 薄壳 handler 落 cloud-bpmn-api projection 包（job 落 api jar、宿主 cloud-system——用户「业务服务零代码、框架层承载」原始形态），bean 经既有 `ApprovalProjectionAutoConfiguration` 注册（同 `cloud.bpmn.projection.enabled` 门控）；Reconciler 仅摘 `@Scheduled` 注解（吞异常口径不动：admin 恒成功、失败观察走服务日志 log.error——语义与 @Scheduled 时代逐字等价）。同批落守护两规则 + backend-spec @XxlJob 模板（xxl-job 整合轮移交备忘 1 全项）。

**Tech Stack:** xxl-job-core 3.5.0（starter 已在）、既有投影框架组件、ArchitectureGuardTest 文本扫描。

**输入：** xxl-job 整合设计 `docs/superpowers/specs/2026-10-09-xxl-job-integration-design.md`（D1-D11 与移交备忘）；投影组件现状（cloud-bpmn-api projection 包五件 + AutoConfiguration）。

**边界：** 不动 REST 契约（无 e2e 回归）；不动 `MqTableCleanJob`；不动事件监听/按需对账路径；不做双模开关（单模式直迁）；`handleFail` 失败可见性增强记移交（本轮 admin 恒成功）。

**首验点（本轮最大不确定）：** api jar 内 AutoConfiguration 注册的 bean，其方法级 `@XxlJob` 注解能否被宿主 `XxlJobSpringExecutor` 收集——机制上 XxlJobSpringExecutor.afterSingletonsInstantiated 遍历 beanFactory 全部 bean 定义找方法注解（与 @Component/@Bean 注册方式无关），理论可行，联调实证。**备用方案**：收集失败（触发报 job handler not found）→ 薄壳降级移 `cloud-system` `job/` 包（@Component + 调 Reconciler 公有方法），api jar 形态记档移交。

---

## B1 薄壳 handler + Reconciler 摘 @Scheduled + Properties 键退役

**Files:**
- Modify: `cloud-base/cloud-api/cloud-bpmn-api/pom.xml`（+xxl-job-core 依赖，版本走根 dependencyManagement）
- Create: `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/projection/ApprovalProjectionReconcileJobHandler.java`
- Modify: `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/projection/ApprovalProjectionReconciler.java`（摘 @Scheduled 注解与 import，javadoc 更新）
- Modify: `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/projection/ApprovalProjectionProperties.java`（删 reconcileIntervalMs 字段）
- Modify: `cloud-base/cloud-api/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/projection/ApprovalProjectionAutoConfiguration.java`（+handler @Bean；@EnableScheduling 处置见步骤 5）

- [x] **步骤 1：api 模块 pom 加 xxl-job-core**（compile 传递——消费方 system 已引 xxljob-starter 双保险；注解与 XxlJobHelper 均需此类路径）

```xml
<!-- xxl-job 对账薄壳（2026-10-10 对账迁移轮：@XxlJob 注解 + XxlJobHelper 执行日志留痕；版本走根 dependencyManagement） -->
<dependency>
    <groupId>com.xuxueli</groupId>
    <artifactId>xxl-job-core</artifactId>
</dependency>
```

- [x] **步骤 2：新建薄壳 handler**

```java
package com.cloudai.bpmn.api.projection;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 审批投影定时对账 xxl-job 薄壳（2026-10-10 对账迁移轮：@Scheduled fixedDelay → admin CRON 调度，
 * 节奏治理权移交 admin 控制台，Nacos reconcile-interval-ms 键退役）。
 * 语义零变化：编排与吞异常全在 {@link ApprovalProjectionReconciler#reconcileActive()}——
 * admin 侧恒成功（失败观察走服务日志 log.error，与 @Scheduled 时代逐字等价；handleFail
 * 可见性增强记移交）。job 落 api jar、宿主 cloud-system（业务服务零代码，框架层承载）。
 * <p>bean 经 ApprovalProjectionAutoConfiguration 注册（同 enabled 门控）；
 * XxlJobSpringExecutor 收集容器全部 @XxlJob 方法（含自动装配 bean）。
 * admin 任务：id 7（执行器组 cloud-system）CRON 0/60 * * * * ?（脚本种子 B3）。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ApprovalProjectionReconcileJobHandler {

    private final ApprovalProjectionReconciler reconciler;

    /** 单轮对账薄壳（零业务逻辑；XxlJobHelper.log 留 admin 执行日志痕） */
    @XxlJob("approvalProjectionReconcileJobHandler")
    public void approvalProjectionReconcileJobHandler() {
        XxlJobHelper.log("approval projection reconcile round start");
        reconciler.reconcileActive();
        XxlJobHelper.log("approval projection reconcile round end");
    }
}
```

- [x] **步骤 3：Reconciler 摘 @Scheduled**——删除 `@Scheduled(fixedDelayString = "${cloud.bpmn.projection.reconcile-interval-ms:60000}")` 行与 `import org.springframework.scheduling.annotation.Scheduled;`；方法 javadoc 改为「单轮对账入口（xxl-job CRON 调度：薄壳 ApprovalProjectionReconcileJobHandler → 本方法；异常全吞语义不变——下轮再试）」。**方法体与 try-catch 一字不动。**

- [x] **步骤 4：Properties 删 interval 字段**——删除 `reconcileIntervalMs` 字段及其 javadoc（`reconcileBatchSize` 保留，按需对账分批仍用）。

- [x] **步骤 5：AutoConfiguration 两处**——①新增 handler @Bean（放 Reconciler bean 之后）：

```java
@Bean
@ConditionalOnMissingBean
public ApprovalProjectionReconcileJobHandler approvalProjectionReconcileJobHandler(ApprovalProjectionReconciler reconciler) {
    return new ApprovalProjectionReconcileJobHandler(reconciler);
}
```

②**@EnableScheduling 处置（先核实后动）**：grep `cloud-common-rocketmq-starter` 源码是否有 `@EnableScheduling`——有 → projection AutoConfiguration 的 `@EnableScheduling` 摘除（收敛：projection 包已无 @Scheduled 方法，rocketmq 侧自保障 MqTableCleanJob）；没有 → 保留并 javadoc 记「保障面已移交 rocketmq starter 缺位场景」。核实结论记入回报。

- [x] **步骤 6：模块编译回归**——`$MVN -f cloud-base/pom.xml clean install -pl cloud-api/cloud-bpmn-api -am` 全绿（bpmn-api 本模块无单测目录，引用 interval 键的仅 Properties/Reconciler 两个 main 源文件已核实——投影框架 28 例在下游 cloud-system/cloud-bpmn 模块，B5 全量构建覆盖回归）。

## B2 守护两规则（ArchitectureGuardTest，探针防假绿）

**Files:**
- Modify: `cloud-base/cloud-system/src/test/java/com/cloudai/system/ArchitectureGuardTest.java`

- [x] **步骤 1：加两规则**（沿既有文本扫描模式；api jar 框架组件不在服务模块源码，天然不在扫描面）

```java
/** 服务模块禁自建 executor：XxlJobSpringExecutor 只能来自 cloud-common-xxljob-starter 装配（xxl-job 整合设计 D1） */
private static final Pattern NEW_XXL_EXECUTOR = Pattern.compile("new\\s+XxlJobSpringExecutor");

@Test
void service_module_must_not_new_xxl_executor() throws IOException {
    List<String> violations = scan(MAIN_JAVA, "*.java", NEW_XXL_EXECUTOR);
    assertThat(violations).as("服务模块禁止 new XxlJobSpringExecutor——executor 装配归 cloud-common-xxljob-starter").isEmpty();
}

@Test
void xxljob_handler_class_must_live_in_job_package() throws IOException {
    // 服务模块本地 @XxlJob 任务类落位约定 job/ 包；api jar 框架组件（projection 对账薄壳）不在服务模块源码，天然豁免
    List<String> violations = new ArrayList<>();
    for (Path java : listFiles(MAIN_JAVA, "*.java")) {
        String content = Files.readString(java, StandardCharsets.UTF_8);
        if (content.contains("@XxlJob(") && !java.toString().replace('\\', '/').contains("/job/")) {
            violations.add(java.getFileName() + " (@XxlJob outside job/ package)");
        }
    }
    assertThat(violations).as("@XxlJob 任务类必须落 job/ 包（本地任务模板约定；api jar 框架组件豁免）").isEmpty();
}
```

- [x] **步骤 2：探针验证两规则防假绿**——临时在 `cloud-system/src/main/java/com/cloudai/system/service/` 放一个含 `new XxlJobSpringExecutor()` 的探针类 → 跑规则①必红；在非 job 包放含 `@XxlJob("x")` 的探针类 → 跑规则②必红；删探针 → 全绿。探针红绿两态输出记回报。

- [x] **步骤 3：`-pl cloud-system` test 全绿。**

## B3 SQL 种子增量 + 基线同步

**Files:**
- Create: `cloud-base/scripts/sql/2026-10-10-approval-reconcile-xxl-job.sql`
- Modify: `cloud-base/scripts/sql/2026-10-09-xxl-job-init.sql`（种子段追加 id 7 行——环境重建基线完整）

- [x] **步骤 1：写增量脚本**（头注含用途/不可重放口径/CRON 语义）

```sql
-- =====================================================================
-- 审批投影对账任务种子增量（2026-10-10 对账迁移轮：Reconciler @Scheduled → xxl-job CRON）
-- 前置：2026-10-09-xxl-job-init.sql 已执行（库与执行器组存在）。
-- 不可重放：固定主键 id 7——重放需先 DELETE FROM xxl_job_info WHERE id = 7。
-- 语义：调度节奏自 Nacos cloud.bpmn.projection.reconcile-interval-ms（键已退役）移交 admin CRON；
--       trigger_status=1 启动即调度；glue_updatetime 必须 now()（3.5.0 JobTrigger 解引用无空防护）。
-- =====================================================================
USE `xxl_job`;

INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`)
VALUES (7, 3, 'approval-projection-reconcile', now(), now(), 'cloudai', '', 'CRON', '0/60 * * * * ?',
        'DO_NOTHING', 'FIRST', 'approvalProjectionReconcileJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '');

commit;
```

- [x] **步骤 2：基线 init 脚本种子段同步追加 id 7 同款行**（demo 任务 id 5/6 之后；注释注明 2026-10-10 增补）。
- [x] **步骤 3：java 单文件 + mysql-connector-j 执行增量脚本**（UTF-8 读、characterEncoding=utf8、分号切分；先例同 B1 xxl 轮）→ 回查 `SELECT id,job_group,executor_handler,schedule_type,schedule_conf,trigger_status FROM xxl_job_info WHERE id=7`（handler/CRON/RUNNING=1 七字段核对）。

## B4 Nacos 清理失效键

- [x] cloud-system.yaml **读出→删 `cloud.bpmn.projection.reconcile-interval-ms` 键（若在配）→写回→回读核对**（合并纪律：其余键一字不动；system 的 projection enabled/consumer-group 等保留）。

## B5 全量构建 + 联调验收

- [x] **步骤 1：全量构建**——`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install` 全绿（预期 404 = 上轮实测 402 + 守护 2 新用例，零回归）。
- [x] **步骤 2：起服**——admin 容器已在（18081 常驻）；起 9201→9202→9203→18080（网关最后）。
- [x] **步骤 3：CRON 自动调度验证**——admin 任务管理页核对 id 7「调度状态=运行中」；等 60-120s 调度日志新增轮次（trigger/handle 双 code=200，执行日志含 round start/end 痕）。
- [x] **步骤 4：手动触发验证（首验点）**——admin「执行一次」id 7 → 调度成功 + 服务日志出现对账轮次输出。**若报 job handler not found → 启用备用方案**（薄壳降级 cloud-system job/ 包 @Component 形态，api jar 形态记移交），处置记回报。
- [x] **步骤 5：按需对账回归**——leave 建单（curl 全 ASCII）→ 撤销 → `GET /system/leave/{id}` status 收敛 "3"（reconcileByBusiness 即时对账路径零影响）；page 读路径派生 status 抽测。
- [x] **步骤 6：收口**——kill 四服务（netstat 找 PID + taskkill //F //PID），admin 容器保留常驻。

## B6 文档更新

**Files:**
- Modify: `CLAUDE.md`（两处）
- Modify: `.claude/skills/backend-spec/SKILL.md`（@XxlJob 任务模板节）
- Modify: 本 plan（末尾补执行结论）

- [x] **步骤 1：CLAUDE.md**——①「事务消息与消费幂等」段投影口径更新：`Reconciler 60s 定时游标分批对账` → `Reconciler 单轮对账（xxl-job CRON 0/60 调度：cloud-bpmn-api 薄壳 handler + admin 任务 id 7，2026-10-10 迁移；Nacos interval 键退役）`，按需对账（撤销链路）不变；②xxl-job 段补一句「首个真实任务=审批投影对账（cloud-bpmn-api projection 包 @XxlJob 薄壳，宿主 cloud-system——api jar 框架组件 job 形态记档）」。
- [x] **步骤 2：backend-spec 增补 @XxlJob 任务模板节**——落位约定（服务本地任务 job/ 包，api jar 框架组件豁免）、模板代码（参照 CloudDemoJobHandler）、admin 建任务三要素（组=appname、handler 名一致、调度 NONE 手工/CRON 定时）、检查清单新条目（新定时任务必须 xxl-job 禁 @Scheduled、handler 类落 job/ 包或 api 框架包、守护两规则关联）。
- [x] **步骤 3：本 plan 补执行结论**（首验点结论/采纳形态/实测结果/用例计数）。

---

## 移交后续阶段的备忘（实现者完成后核对补执行结论）

1. **handleFail 可见性增强**：薄壳现恒成功（吞异常语义等价迁移）；后续可评估异常时 `XxlJobHelper.handleFail(msg)` 标失败（admin 可观测+可配重试），需 Reconciler 暴露非吞入口——失败可见性 vs 重试语义需一并设计。
2. **分片广播评估**：单实例宿主 FIRST 路由现状；system 多实例化后对账任务可评估 SHARDING_BROADCAST（Reconciler 游标天然可分片）。
3. **MqTableCleanJob 迁移候选**（沿 xxl-job 整合轮移交备忘 2）：system/bpmn 两组各一任务或分片广播。
4. **@Scheduled 禁令执行面**：backend-spec 已落「新定时任务必须 xxl-job」；守护规则机械化（服务模块禁新增 @Scheduled）待下个有 @Scheduled 改动的轮次一并评估。

---

## 执行结论（2026-10-10，backend-agent 实施补记）

**首验点结论：收集成功，主方案成立**——api jar 内 `ApprovalProjectionAutoConfiguration` `@Bean` 注册的 handler，其方法级 `@XxlJob` 被宿主 cloud-system 的 XxlJobSpringExecutor 正常收集（执行日志出现 `com.cloudai.bpmn.api.projection.ApprovalProjectionReconcileJobHandler#approvalProjectionReconcileJobHandler`，handleCode=200），未触发 job handler not found，**备用方案未启用**。

**采纳形态**：plan 主方案原样落地（薄壳在 cloud-bpmn-api projection 包 + @Bean 注册，业务服务零代码）。

**两处 plan 内部笔误的落地修正（语义不变，记档）**：
1. `trigger_status`：plan 的 INSERT 语句未含该列（表默认 0=停止），与其头注「trigger_status=1 启动即调度」及步骤 3 回查口径（RUNNING=1）矛盾——按注释意图补显式列 `trigger_status=1`（增量与基线两脚本同步）；手工 INSERT 的 trigger_next_time=0 由 admin 首扫按 misfire DO_NOTHING 自愈刷新（首轮不双跑，实测 09:56 起 CRON 每分钟整点轮全 200）。
2. **CRON 字面值**：plan 的 `0/60 * * * * ?` 在 xxl-job 3.5.0 非法（admin API 实证 500 "Increment >= 60 : 60"，且 admin 扫描算不出 next time 会把任务自动 schedulePause 三归零）——等价替换为 `0 * * * * ?`（每分钟第 0 秒，与 0/60 意图一致），两 SQL 脚本与 handler javadoc 同步修正。

**实测结果（B5 联调）**：
- CRON 自动调度：admin 任务管理页任务 7 状态 RUNNING（截图留档）；xxl_job_log 每分钟一行（logId 5-13+），trigger_code=200 / handle_code=200，executor=http://192.168.10.22:19202/；executor 执行日志含 round start/end 痕；system 服务日志每分钟 `ApprovalProjectionReconciler: 定时对账完成: batches=1`（xxl-job JobThread 驱动）。
- 手动触发：POST /jobinfo/trigger id=7 → 200，logId=8 双 code=200 + round 痕。
- 按需对账回归：leave 建单（e2e-rec job-<ts>，MQ 冷启动 3025 重试即愈既有已知）→ status "0" → PUT cancel → status 收敛 "3"；page 派生抽测（撤销单 "3"/在途单 "0"）正常。
- 收口：9201/9202/9203/18080 四端口 taskkill 清空，admin 容器 18081 保留常驻。

**用例计数**：全量 `clean install` 全绿，**404 = 402 + 守护 2 新用例**（service_module_must_not_new_xxl_executor / xxljob_handler_class_must_live_in_job_package），零回归。探针红绿两态取证过（24 例恰 2 红定位到探针行，删探针后 24 绿）。

**@EnableScheduling 处置（B1 步骤 5②）**：核实 cloud-common-rocketmq-starter `CommonRocketMqAutoConfiguration` 带 `@EnableScheduling` 且默认开（matchIfMissing=true），api 模块 pom 已依赖该 starter——按 plan 摘除 projection AutoConfiguration 的 `@EnableScheduling`（javadoc 记档收敛理由）。

**Nacos（B4）**：`cloud.bpmn.projection.reconcile-interval-ms` 在 public namespace 全部三个配置（cloud-common/cloud-bpmn/cloud-system.yaml）中**从未显式配置**（@Scheduled 一直走占位符默认 60000）——按「若在配」口径无可删，未写回（避免无谓覆盖），before/after diff 为空；仓库 application.yml 过时注释（提及 interval 默认）已注释级修正。

**运维记档**：admin 3.5.0 `jobinfo/pageList` REST 参数为 `jobGroup/triggerStatus/name/executorHandler/author/offset/pagesize`（非 2.x 的 jobDesc/start/length，旧参数组合报 400 Error 页）；UI 为 hash 路由 + iframe。
