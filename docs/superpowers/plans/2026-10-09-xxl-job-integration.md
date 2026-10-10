# xxl-job 分布式调度整合实施计划（2026-10-09）

> 输入：设计 `docs/superpowers/specs/2026-10-09-xxl-job-integration-design.md`（决策 D1-D11 唯一依据）。**纯后端轨道、A 级、无契约变更**（无 contracts 文档；无前端章）。
> 本计划不含 git 操作（提交由主控执行）。工作区基线：commit 5040fd0。
> 服务进程全生命周期归 backend-agent（java -jar 绝对路径起服；联调+冒烟通过即 kill 交还，不滞留人工审核）。xxl-job-admin 容器为本机基础设施（对齐 nacos/redis 口径）：B2 启动后**保持常驻**（--restart unless-stopped），不随联调结束回收。
> 实测项登记（设计期标记的验证点，随任务落地）：
> | # | 实测项 | 落点 |
> |---|---|---|
> | M1 | xxl-job-core 传递依赖（netty/gson/groovy）与 SB 3.3.4 收敛 | B3 |
> | M2 | 容器 → 宿主原生 MySQL（host.docker.internal）连通，本机首例 | B2 |
> | M3 | executor 注册地址两方案（ip=LAN IP vs address=host.docker.internal）实测取一 | B7 |

## 后端章（cloud-base/ + 本机基础设施，backend-agent，B1→B9）

### B1 SQL：xxl_job 库初始化脚本落仓与执行

- 文件清单：
  - `cloud-base/scripts/sql/2026-10-09-xxl-job-init.sql`（新）：头注（来源=官方 v3.5.0 `doc/db/tables_xxl_job.sql` 逐字原样（含官方 4 条种子与结尾 commit）+ 本项目种子段；执行方式=java 单文件 + mysql-connector-j；**不可重放**——重放需先 `DROP DATABASE xxl_job`；生产化换 token 见设计移交备忘 4）+ 官方 16 语句原样 + 分隔横幅注释 + 本项目种子段（设计 D4 原文：组 id 3=cloud-system/4=cloud-bpmn，access_token='default_token'，address_type=0；任务 id 5/6 schedule_type='NONE' 仅手工触发（官方 v3.5.0 种子任务实占 id 1-4，设计 D4 原文 id 2/3 系笔误顺延，主控裁定记档），executor_handler='cloudDemoJobHandler'，路由 FIRST，retry 0，BEAN）+ commit
- 执行：java 单文件源码 + mysql-connector-j（jar 从 `~/.m2/repository/com/mysql/mysql-connector-j/` 取本地实际版本）——UTF-8 显式读文件、URL 带 `characterEncoding=utf8`、按 `;` 切分逐语句执行（官方文件已核实字符串字面量内无 ASCII 分号）、空语句跳过、`USE xxl_job` 后同连接执行到底（先例：2026-10-09-rocketmq-tx-approval.sql 落库）
- 验收：执行零异常退出；回查 `SELECT id,app_name,address_type,access_token FROM xxl_job_group`（4 行=官方 2 + 本项目 2，token=default_token）、`SELECT id,job_group,executor_handler,schedule_type FROM xxl_job_info`（6 行=官方示例 4 + demo 2（id 5/6），handler=cloudDemoJobHandler、schedule_type=NONE）、`SHOW CREATE TABLE xxl_job_registry`（uk i_g_k_v 在）；官方段与上游 tag 逐字 diff 核对记档（仅追加段差异）。

### B2 admin 容器启动与连通验证（M2）

- 无文件改动（命令记档，设计 D3 原文）：`docker run -d --name xxl-job-admin --restart unless-stopped -p 18081:8080 -e TZ=PRC -e PARAMS="--spring.datasource.url=jdbc:mysql://host.docker.internal:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai --spring.datasource.username=root --spring.datasource.password=" xuxueli/xxl-job-admin:3.5.0`
- 前置核对：`netstat -ano | grep :3306` 确认 MySQL 监听 0.0.0.0（仅 127.0.0.1 则 LAN IP 直连不可行，host.docker.internal 为唯一通路——记档）；宿主 18081 空闲核对。
- 验收：`docker ps` 状态 Up；`docker logs xxl-job-admin` 无 datasource 连接报错（**M2 通过即证容器→宿主 MySQL 通路**，失败处置：核对 Docker Desktop 版本/别名解析 `docker exec xxl-job-admin ping -c1 host.docker.internal` 不可用则改查文档口径，回报主控）；`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:18081/` 返回 200；浏览器或 curl 登录 admin/123456 成功取证（3.5.0 无 /xxl-job-admin 前缀，注意 URL 形态）。
- 容器**保持常驻**（基础设施口径，不随联调回收）。

### B3 cloud-common-xxljob-starter 模块 + 版本矩阵（可与 B1/B2 并行起步）

- 文件清单：
  - `cloud-base/pom.xml`（改）：properties 增 `<xxl-job.version>3.5.0</xxl-job.version>`；dependencyManagement 增 `com.xuxueli:xxl-job-core`（${xxl-job.version}）与 `com.cloudai:cloud-common-xxljob-starter`（${project.version}）两条
  - `cloud-base/cloud-common/pom.xml`（改）：modules 增 `cloud-common-xxljob-starter`
  - `cloud-base/cloud-common/cloud-common-xxljob-starter/pom.xml`（新，沿 rocketmq-starter pom 模板）：parent=cloud-common；依赖 `com.xuxueli:xxl-job-core` + `org.springframework.boot:spring-boot-autoconfigure`（版本均免写）；**不依赖 core-starter**（设计 D1）
  - `.../src/main/java/com/cloudai/common/xxljob/config/CommonXxlJobProperties.java`（新：`@ConfigurationProperties("xxl.job")`，键名官方零转译——嵌套 Admin{addresses,timeout} / Executor{enabled,appname,accessToken,ip,port,address,logPath,logRetentionDays,excludedPackage,glueEnabled}；**glueEnabled 默认 false**（偏离官方 sample 的 true，注释记安全理由）；其余默认值对齐官方 sample）
  - `.../src/main/java/com/cloudai/common/xxljob/config/CommonXxlJobAutoConfiguration.java`（新：`@AutoConfiguration` + `@EnableConfigurationProperties` + `@ConditionalOnProperty(prefix="cloud.common.xxljob", name="enabled", havingValue="true")`（默认关，无 matchIfMissing）+ `@Bean @ConditionalOnMissingBean XxlJobSpringExecutor`（Properties→setter 逐项映射；裸 @Bean 即官方 sample 形态，DisposableBean 生命周期已核实，勿加 destroyMethod）；类 javadoc 记门控理由（executor 绑端口失败会阻断服务启动，默认关隔离）)
  - `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（新：一行）
- 验收：`$MVN -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-xxljob-starter -am` 全绿；**M1**：`$MVN -f cloud-base/pom.xml dependency:tree -pl cloud-common/cloud-common-xxljob-starter` 记录 netty-codec-http/gson/groovy/xxl-tool 传递版本，与 spring-boot-dependencies 3.3.4 管理版本比对——冲突（服务模块已有同 groupId 不同版本者）则根 dependencyManagement 显式收敛并回报记档，无冲突记「M1 通过：无交叠」。

### B4 双服务接入 + Nacos 配置

- 文件清单：
  - `cloud-base/cloud-system/pom.xml`、`cloud-base/cloud-bpmn/pom.xml`（改：各增 cloud-common-xxljob-starter 依赖）
  - `cloud-base/cloud-system/src/main/resources/application.yml`、`cloud-base/cloud-bpmn/src/main/resources/application.yml`（改：`spring.config.import` 追加 `optional:nacos:cloud-common.yaml`（共享配置接线，optional 容缺，设计 D6）+ xxl-job 注释块（设计 D6 原文，两服务各按本服务改差异键示例））
  - Nacos 配置落位（控制台或 OpenAPI，沿 rocketmq 配置落位先例；键值矩阵=设计 D6 表）：
    - 共享 `cloud-common.yaml`（无则新建）：`xxl.job.admin.addresses: http://127.0.0.1:18081`、`xxl.job.executor.logpath: D:/applogs/xxl-job`、`xxl.job.executor.logretentiondays: 30`
    - `cloud-system.yaml`：`cloud.common.xxljob.enabled: true`、`xxl.job.executor.appname: cloud-system`、`xxl.job.executor.port: 19202`、`xxl.job.executor.ip: <宿主局域网 IP>`（B7 方案一起始值）、`xxl.job.executor.accessToken: default_token`
    - `cloud-bpmn.yaml`：同上，appname=cloud-bpmn、port=19203
    - 共享 import 若实测解析异常（占位符/顺序问题）→ 退化全量写 per-service（设计 D6 退路），回报记档
- 验收：两服务构建绿；起服（java -jar）日志出现 xxl-job executor 启动与 admin 注册线程日志（xxl-job executor init / registry start 字样）；enabled 未配场景核对（可选）：临时注释 Nacos enabled 键重启，服务正常起且无 executor 日志（门控默认关直证，单测已覆盖可免）。

### B5 demo job（两服务，拍板保留为模板）

- 文件清单：
  - `cloud-base/cloud-system/src/main/java/com/cloudai/system/job/CloudDemoJobHandler.java`（新：`@XxlJob("cloudDemoJobHandler")`；`XxlJobHelper.getJobParam()` → `XxlJobHelper.log("...param=%s, app=%s", param, "cloud-system")` + `log.info` 同步；无异常即成功（不显式 handleFail）；javadoc=「新增 xxl-job 任务参照模板：建类 @XxlJob 命名 → admin 任务管理建任务（组=本服务、handler 名、路由 FIRST、调度 NONE 手工/CRON）」）
  - `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/job/CloudDemoJobHandler.java`（新：同上，app 标识 cloud-bpmn；两组同名 handler 合法——作用域=执行器组）
- 验收：`-pl cloud-system,cloud-bpmn -am` 构建绿；handler 无单测要求（设计 D8 取舍记档——联调覆盖）。

### B6 门控单测

- 文件清单：
  - `cloud-base/cloud-common/cloud-common-xxljob-starter/src/test/java/com/cloudai/common/xxljob/config/CommonXxlJobAutoConfigurationTest.java`（新，ApplicationContextRunner）：①enabled 未配 → 无 XxlJobSpringExecutor bean（默认关+imports 注册路径双证）；②enabled=true + xxl.job.* 样例值 → bean 存在且 appname/port/adminAddresses/accessToken 断言（映射正确性）
- 验收：`-pl cloud-common/cloud-common-xxljob-starter` test 全绿；对齐 ApprovalProjectionAutoConfigurationTest 形态。

### B7 联调验收：注册地址实测（M3）+ hello world 全链路

- 前置：B2 admin Up + B4 起服（两服务带 executor）+ B5 handler 在。
- 步骤：
  1. **方案一起始**（Nacos 已配 ip=宿主局域网 IP）：admin UI 执行器管理页核对 cloud-system/cloud-bpmn 两组 **OnLine** 且注册地址=`http://<lan-ip>:19202|19203/`；多网卡注意——IP 取物理网卡地址（非 192.168.152.1 虚拟网卡，Nacos 同源坑）。
  2. OnLine 空或地址错 → **切方案二**：Nacos 改 `xxl.job.executor.address: http://host.docker.internal:19202`（bpmn 19203）、去 ip 键，重启两服务，再核对 OnLine。两案判定标准一致：OnLine + 步骤 3 触发成功（回调可达即证）。仍不通按设计 D5 兜底排查序（容器内别名解析/宿主防火墙 19202、19203 入站/netty 绑定面）处置并回报。
  3. 任务管理页两 demo 任务各「执行一次」（执行参数填 `cloud-hello`）：调度日志「执行结果=成功、调度备注=触发调度成功」；执行日志含参数回显与 app 标识；两服务本地日志同步出现 handler 输出（双证取证）。
  4. admin 容器 `docker restart xxl-job-admin` → 90s 内两组 OnLine 自动恢复（registry 心跳 30s）。
  5. **采纳结论记档**：最终采纳方案（一/二）与实际配置值，回报主控并写入 B9 文档（移交备忘 5 素材）。
- 验收：上述 5 步全过；取证（UI 文字记录或截图路径 + 服务日志行）进任务回报。

### B8 全量构建 + 冒烟收口

- 步骤：`$MVN -f cloud-base/pom.xml clean install` 全绿（基线 357 + 新增门控 2 用例，零回归）；全栈起服（sso 9201 / system 9202 / bpmn 9203 / gateway 18080）；冒烟：`curl http://localhost:18080/system/demo/ping` + 既有 leave 链路抽测 2 条（登录→建单→列表，证 xxl 接入零破坏既有行为）；e2e 全量回归由主控裁量（纯后端无契约变更，非强制）。
- 验收：构建全绿 + 冒烟通过 + xxl 两组仍 OnLine（共存稳定性）；**停服交还**（kill 9201/9202/9203/18080 对应 java PID，netstat 找 PID 再 taskkill //F；xxl-job-admin 容器常驻不回收）。

### B9 文档更新

- 文件清单：
  - `CLAUDE.md`（改，四处）：①Architecture 模块拓扑 cloud-common 列表增 `cloud-common-xxljob-starter`（XxlJobSpringExecutor 装配，enabled 默认关、连接键 xxl.job.* 走 Nacos）；②「关键约定」增 xxl-job 段（接入模板=引依赖 + Nacos 共享/差异两层配置 + cloud.common.xxljob.enabled=true；admin http://127.0.0.1:18081（无 context 前缀）；镜像 tag≡xxl-job-core 版本同批升级；executor 端口 19202/19203）；③Commands 端口现状增 18081（xxl-job-admin）/19202/19203（executor）；④「Windows / 本机环境陷阱」增 host.docker.internal 首例记档（容器连宿主原生 MySQL 的通路）与 B7 采纳的注册地址口径
  - `docs/superpowers/plans/2026-10-09-xxl-job-integration.md`（本文件，改）：末段「移交后续阶段的备忘」补执行结论（采纳方案/实测项 M1-M3 结果/实际新增用例数）
- backend-spec：**本轮不增补**（设计 D10 裁量记档——@XxlJob 任务模板与守护规则下一轮随对账迁移同批落）。
- 验收：CLAUDE.md 更新与设计 D10 清单逐项对得上；无多余改动。

## 移交后续阶段的备忘（沿设计 §5，实现者完成后逐条核对并补执行结论）

1. **对账迁移路线（下一轮主目标）**：`ApprovalProjectionReconciler.reconcileActive` 从 @Scheduled → @XxlJob 薄壳（reconcileByCursor 公有化）；同批落服务侧守护规则（服务模块禁 `new XxlJobSpringExecutor` 直配、@XxlJob 任务类落位约定）+ backend-spec @XxlJob 模板增补 + reconcile-interval-ms 键退役评估 + @EnableScheduling 面收敛评估（rocketmq 清理任务仍在则保留）。本轮 hello world 已验证「job 类在服务模块、装配在 starter」链路；「job 落 api jar、宿主服务」链路（对账形态）依赖 XxlJobSpringExecutor 收集容器全部 @XxlJob bean——下一轮迁移时首先验证此点。
2. **MqTableCleanJob 迁移候选**：每服务清各自库——按 appname 两组各一任务或分片广播，评估时注意两库保留期差异配置。
3. **CVE-2026-94145**（stored XSS ≤3.5.0）：生产化前置条件——官方修复版发布后同批升级（流程同备忘 6）；开发单机可接受已记档。
4. **token 生产化**：default_token 为公开默认值；生产换强 token 需 DB 组行（UPDATE xxl_job_group）与 Nacos executor 配置**双处同步**；admin 登录口令（种子 admin/123456）同批更换。
5. **admin 回调地址口径**：本轮采纳方案（B7 结论）与「单机 Docker Desktop + 宿主 java 进程」部署形态强绑定；服务多实例/容器化/K8s 时重估（Pod IP 自注册可去显式 ip/address）。
6. **镜像版本升级纪律**：admin 镜像 tag ≡ xxl-job-core 版本同批；升级 diff 官方新 tag `doc/db/tables_xxl_job.sql` vs 仓内脚本官方段，DDL 变更出增量 ALTER；本项目种子段（固定主键 id 3/4、2/3）重放核对。
7. **告警通道**：alarm_email 未配置，任务失败告警缺位——与 RocketMQ 监控移交同源，监控体系轮统一评估。

## 执行结论（2026-10-10 backend-agent 补记，随 B9 收口）

- **B7 采纳方案**：方案一（钉 ip=192.168.10.22，物理网卡 WLAN；Nacos per-service `xxl.job.executor.ip`）——admin 容器按注册值回调宿主实测可达（两组手动触发调度+执行+回调全链成功），方案二（address 别名）未启用。
- **实测项结果**：M1 通过（xxl-job-core 3.5.0 传递依赖全树 netty 统一 4.1.113.Final（与既有 rocketmq netty-all 零分叉）、gson 2.10.1、groovy 4.0.23、xxl-tool 2.5.2 单版本无竞争——无需根 dependencyManagement 收敛）；M2 通过（host.docker.internal → 宿主原生 MySQL 3306 首例，容器日志 HikariCP Start completed 实证）；M3=方案一（见上）。
- **实际用例数**：全量 clean install **402 例全绿**（HEAD 5040fd0 基线 worktree 实测 400 + 本轮新增门控 2；计划原文「357+2」之基数沿用 commit message 记载值，与 surefire 实际计数不符，以本条为准）。
- **B1 修正两处（主控裁定/实测记档，specs D4 已同步）**：①demo 任务 id 顺延 5/6（官方 v3.5.0 种子实占 xxl_job_info id 1-4，D4 原文 id 2/3 系笔误）；②种子段 glue_updatetime/child_jobid 植 NULL 会使 3.5.0 JobTrigger.processTrigger 对 glue_updatetime 解引用 NPE（触发卡 pending），对齐官方种子形态改 now()/''。
- **B2 实测口径（3.5.0 UI 形态与 2.x 有差）**：登录页 GET `/auth/login`（根路径 302 跳转而至）、登录 POST `/auth/doLogin`（AJAX，成功 Set-Cookie `xxl_job_login_token`）；「无 /xxl-job-admin 前缀」结论不变。手动触发 API：POST `/jobinfo/trigger`（id/executorParam/addressList 三参必填，addressList 空=自动取注册地址）。
- **B8 冒烟**：gateway `/system/demo/ping` 200；leave 链路建单 200 → 投影事件回写 approvalId → 列表派生 status=0 审批中（MQ 事务消息+投影零破坏实证；建单首轮 3025 为既有 MQ 冷启动路由超时，重试即过——与 e2e MQ 预热纪律同源）；全栈运行期 xxl 两组保持 OnLine。
- **B6 单测注意**：用例②置官方内层 `xxl.job.executor.enabled=false` 使 SmartInitializingSingleton→start() 早退（防单测真绑端口/真注册，映射断言不受影响）；该路径 context close 时 xxl-job 3.5.0 destroy() 对未初始化 helper 无空防护打一条 Spring WARN（上游行为，已被 Spring 吞掉不影响绿测），生产路径（enabled=true）不触发。
