# xxl-job 分布式调度整合 技术方案（2026-10-09）

> 拍板结论（用户 4 项 + 主控既定口径，全部为设计输入不再讨论）：①封装路线=A1 新增 `cloud-common-xxljob-starter`（第 8 个 common starter）；②admin 容器宿主端口=**18081**；③DDL=官方 8 表原样保留 + 文件尾部追加本项目种子段（两执行器组 + 两 demo job），落 `scripts/sql/` 日期前缀；④demo job=验收后保留为模板 + starter 门控单测（服务侧守护规则下一轮随对账迁移落）。
> 主控既定口径：属性前缀连接键沿用官方 `xxl.job.*`；starter enabled 默认关；appname=`spring.application.name` 同名（cloud-system/cloud-bpmn）；executor 端口 **system=19202 / bpmn=19203**；accessToken=种子段写官方公开默认值 `default_token` 字面量；docker run 命令记档不落脚本；Nacos 共享配置放公共项 + per-service 放差异项、仓库 application.yml 留注释块；logpath Windows 显式；本轮不动任何 `@Scheduled`。
>
> **架构裁量两处（主控授权定夺，明示理由）**：D1 门控键定名 `cloud.common.xxljob.enabled`（组件行为开关挂 `cloud.common.*` 前缀对齐 rocketmq 先例，默认值取 false 对齐 projection 先例——接入面依赖外部 admin 基础设施，未起容器时不应占端口起线程）；D10 backend-spec 本轮**不增补**（无 CRUD/Controller/模板面，@XxlJob 任务模板与守护规则下一轮随对账迁移同批落，记移交备忘 1）。

## 1. 架构总览（文字架构图）

### 1.1 现状（本轮不动）

```
定时任务两处，均为 Spring @Scheduled（每实例各跑、无管控面、无调度审计）：
  ApprovalProjectionReconciler.reconcileActive()  fixedDelay 60s  cloud-bpmn-api projection 包，宿主 cloud-system
  MqTableCleanJob.cleanExpired()                  cron 每日 03:00  cloud-common-rocketmq-starter，宿主 system+bpmn
@EnableScheduling 已由 rocketmq/projection 两个 AutoConfiguration 开启（幂无害共存）
```

### 1.2 整合后拓扑（本轮落位部分）

```
┌─ xxl-job-admin 容器（xuxueli/xxl-job-admin:3.5.0，Docker Desktop）───────────────┐
│  宿主 18081 → 容器 8080（8080 已被 rocketmq-dashboard 占用，映射错开）            │
│  PARAMS 注入数据源：jdbc:mysql://host.docker.internal:3306/xxl_job（Docker       │
│  Desktop 内置宿主别名；本机容器连宿主原生 MySQL 首例，实测项 M2）                 │
│  库 xxl_job：官方 8 表原样 + 本项目种子（组 id 3/4、任务 id 5/6，D4）             │
│  UI http://127.0.0.1:18081/ 登录 admin/123456（3.5.0 context-path=/，无          │
│  /xxl-job-admin 前缀——与 2.4.x 不同，addresses 与浏览器均不带前缀）              │
└──────────────▲───────────────────────────────▲──────────────────────────────┘
      注册/心跳 │ http://127.0.0.1:18081        │ 调度触发（回调注册地址）
      （容器→宿主方向：admin 按 xxl_job_registry 注册值回调 executor，
        多网卡机器自动探测不可用——两方案实测预案见 D5）
┌──────────────┴──────────┐        ┌───────────┴──────────────┐
│ cloud-system :9202       │        │ cloud-bpmn :9203         │
│  executor 19202          │        │  executor 19203          │
│  appname cloud-system    │        │  appname cloud-bpmn      │
│  job/CloudDemoJobHandler │        │ job/CloudDemoJobHandler  │
│   @XxlJob("cloudDemoJob- │        │  @XxlJob("cloudDemoJob-  │
│   Handler") 模板保留      │        │  Handler") 模板保留      │
└──────────────────────────┘        └──────────────────────────┘
       ▲ 两服务共同引 cloud-common-xxljob-starter（引 jar 即得 XxlJobSpringExecutor 装配，
         cloud.common.xxljob.enabled=true 显式启用，默认关）
```

### 1.3 数据与配置面

| 面 | 内容 |
|---|---|
| DB（新库 xxl_job） | 官方 8 表原样（xxl_job_group/registry/info/log/log_report/logglue/lock/user）+ 种子段（D4）；**零自定义表、零列改造、零自定义索引** |
| Nacos（不进仓库） | 共享 cloud-common.yaml：addresses/logpath/logretentiondays；per-service：enabled/appname/port/ip(或 address)/accessToken（D6 矩阵） |
| 仓库 | 新模块 cloud-common-xxljob-starter；system/bpmn pom + application.yml 注释块；scripts/sql 初始化脚本；CLAUDE.md 记档 |
| 不动 | 全部 @Scheduled（Reconciler 与 MqTableCleanJob 双轨期照旧）、全部既有 REST 契约、cloud-web/cloud-e2e、sso/gateway 不接入 |

## 2. 决策记录

### D1 starter 形态与门控（cloud-common-xxljob-starter，第 8 个 common starter）

- **组件清单**（包 `com.cloudai.common.xxljob.config`）：
  - `CommonXxlJobProperties`——`@ConfigurationProperties(prefix = "xxl.job")`，**键名与官方零转译**（官方 sample 逐键镜像）：嵌套 `Admin{addresses, timeout}`、`Executor{enabled, appname, accessToken, ip, port, address, logPath, logRetentionDays, excludedPackage, glueEnabled}`。类名带 Common 对齐本仓 `CommonRocketMqProperties` 命名惯例（类名本仓惯例、键名官方惯例，两者不矛盾——注释记档）。
  - `CommonXxlJobAutoConfiguration`——`@AutoConfiguration` + `@EnableConfigurationProperties(CommonXxlJobProperties.class)` + **`@ConditionalOnProperty(prefix = "cloud.common.xxljob", name = "enabled", havingValue = "true")`（默认关，无 matchIfMissing）** + `@Bean @ConditionalOnMissingBean XxlJobSpringExecutor`（Properties→setter 逐项映射，`@ConditionalOnMissingBean` 可覆盖惯例对齐）。裸 `@Bean` 注册即官方 sample 形态——`XxlJobSpringExecutor implements DisposableBean`（3.5.0 源码核实），容器关闭自动 destroy（内嵌 netty server 停止 + 注销注册），**无需 destroyMethod 声明**。
  - `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 增一行。
- **门控默认 false 理由**（与 rocketmq 的 enabled 默认 true 相反，与 projection 一致）：executor 启动会**绑定端口 19202/19203**，绑定失败（afterSingletonsInstantiated 抛出）会阻断服务启动——admin 容器是独立基础设施，未起/CI/单测环境不应被连坐；显式启用让接入面可控（sso 不配即永不接入）。错误处理表记运维注意。
- **glueEnabled 默认 false**（偏离官方 sample 的 true，记档）：GLUE 任务=控制台注入代码执行面，本项目无需求且是攻击面，默认关；需要时 Nacos 显式开。
- **依赖最小面**：`xxl-job-core` + `spring-boot-autoconfigure`（版本均走根 dependencyManagement），**不依赖 core-starter**（executor 纯注册组件，无 R/ObjectMapper 面）。
- **@XxlJob 任务扫描**：XxlJobSpringExecutor 启动时收集宿主 Spring 容器全部 @XxlJob bean（含 jar 内自动装配与组件扫描 bean）——下一轮对账迁移「job 落 cloud-bpmn-api、宿主 cloud-system」形态天然支持（本轮 hello world 即在验证该装配链）。

### D2 版本矩阵与镜像对齐纪律

- 已核实事实（设计前置，非待验证）：`com.xuxueli:xxl-job-core:3.5.0` 在 Maven Central（当前最新正式版）；xxl-job 3.0.0（2025-02）起调度中心 SB3+JDK17、镜像 JDK17 构建（本地镜像 inspect 实证 JDK 17.0.20）——与本项目 JDK17/SB 3.3.4 同代；xxl-job-core 依赖全 jakarta 系（netty-codec-http/gson/xxl-tool/groovy/spring-context provided），无 javax 残留；2.x 属 javax 世代不兼容 SB3，无版本选择空间。
- **版本接入**：`cloud-base/pom.xml` properties 增 `<xxl-job.version>3.5.0</xxl-job.version>`；dependencyManagement 增 `com.xuxueli:xxl-job-core:${xxl-job.version}` 与内部模块 `cloud-common-xxljob-starter` 两条；`cloud-common/pom.xml` modules 增一行。
- **对齐纪律**：admin 镜像 tag ≡ xxl-job-core 版本，**同批升级**（调度协议两端一致前提）；升级流程见移交备忘 6。
- **依赖收敛实测（M1）**：`dependency:tree` 抽查 netty-codec-http/gson/groovy 传递版本与 spring-boot-dependencies 管理版本的交叠（服务模块原本无 netty/gson 直依赖，网关 reactor-netty 不涉——网关不引本 starter）；发现冲突则在根 dependencyManagement 显式收敛并记档。

### D3 admin 容器形态（记档不落脚本，对齐 nacos/redis 基础设施现状）

```
docker run -d --name xxl-job-admin --restart unless-stopped \
  -p 18081:8080 -e TZ=PRC \
  -e PARAMS="--spring.datasource.url=jdbc:mysql://host.docker.internal:3306/xxl_job?useUnicode=true&characterEncoding=UTF-8&autoReconnect=true&serverTimezone=Asia/Shanghai --spring.datasource.username=root --spring.datasource.password=" \
  xuxueli/xxl-job-admin:3.5.0
```

- **端口**：宿主 18081（拍板，贴近网关 18080 管控段）→ 容器 8080（镜像唯一暴露口；宿主 8080 已被 rocketmq-dashboard 占用是硬约束）。
- **数据源**：`PARAMS` 环境变量注入 Spring 参数（镜像 entrypoint `java -jar /app.jar $PARAMS`，inspect 实证）；`host.docker.internal` 为 Docker Desktop for Windows 内置宿主别名——**本机容器连宿主原生 MySQL 首例（实测项 M2）**；root 空密码显式传空串（`--spring.datasource.password=` 尾等号保空值）；时区参数对齐 GMT+8（镜像自带 TZ=PRC）。
- **连通验证**：`docker logs xxl-job-admin` 无 datasource 报错 + `curl http://127.0.0.1:18081/` 登录页可达 + UI admin/123456 登录成功（种子口令，sha256("123456") 官方种子行实证）。
- **M2 前置核对**：`netstat -ano | grep :3306` 确认 MySQL 监听 0.0.0.0（若仅绑 127.0.0.1，容器侧 LAN IP 直连不可行，host.docker.internal 为唯一通路）；不通时排查 Docker Desktop 版本（18.03+ 内置该别名）。
- **记档位置**：CLAUDE.md「Windows / 本机环境陷阱」段 + 本文档；不落脚本/compose（项目无此惯例）。

### D4 DDL 与种子（官方原样 + 追加段）

- 文件：`cloud-base/scripts/sql/2026-10-09-xxl-job-init.sql`，结构=官方 v3.5.0 `doc/db/tables_xxl_job.sql` **逐字原样**（CREATE DATABASE xxl_job + 8 表 + 官方 4 条种子 INSERT + commit，共 16 语句）+ 分隔横幅注释 + 本项目种子段 + commit。
- **官方原样的理由**（拍板）：升级 xxl-job 版本时与官方新 tag 的 DDL 直接 diff；官方种子行（示例组 id 1/2、示例任务 id 1-4、admin 用户、调度锁）保留无害。
- **追加种子段**（本项目）：

```sql
-- ================== 本项目种子段（2026-10-09 xxl-job 整合，设计 D4；官方段勿改，本段在官方 commit 之后追加）==================
-- 执行器组：appname 与 spring.application.name 同名（自动注册 address_type=0）；
-- access_token 用官方公开默认值 default_token（拍板：公开默认值非真凭据，消解「种子可复现」与「凭据不进仓库」矛盾；生产化换强 token 见移交备忘 4）
-- 修正记档（2026-10-09 主控裁定）：官方 v3.5.0 xxl_job_info 种子实为 4 条占 id 1-4（demoJobHandler/Ollama/Dify/OpenClaw），本设计原文 id 2/3 系笔误，demo 任务 id 顺延 5/6——job id 无外部引用面，触发按任务名/handler 锚定。
-- 修正记档2（B7 实测）：原文 glue_updatetime/child_jobid 植 NULL 会使 3.5.0 JobTrigger.processTrigger 对 glue_updatetime 解引用 NPE（触发卡 pending），对齐官方种子形态改 now()/''。
INSERT INTO `xxl_job_group`(`id`, `app_name`, `name`, `address_type`, `address_list`, `access_token`, `update_time`)
VALUES (3, 'cloud-system', 'cloud-system执行器', 0, NULL, 'default_token', now()),
       (4, 'cloud-bpmn',   'cloud-bpmn执行器',   0, NULL, 'default_token', now());

-- demo job（hello world 模板，拍板验收后保留）：schedule_type=NONE 仅手工触发（不误调度）；handler 对应 B5 @XxlJob("cloudDemoJobHandler")
INSERT INTO `xxl_job_info`(`id`, `job_group`, `name`, `add_time`, `update_time`, `author`, `alarm_email`,
                           `schedule_type`, `schedule_conf`, `misfire_strategy`, `executor_route_strategy`,
                           `executor_handler`, `executor_param`, `executor_block_strategy`, `executor_timeout`,
                           `executor_fail_retry_count`, `glue_type`, `glue_source`, `glue_remark`, `glue_updatetime`,
                           `child_jobid`)
VALUES (5, 3, 'system-demo-hello-world', now(), now(), 'cloudai', '', 'NONE', '',
        'DO_NOTHING', 'FIRST', 'cloudDemoJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), ''),
       (6, 4, 'bpmn-demo-hello-world',   now(), now(), 'cloudai', '', 'NONE', '',
        'DO_NOTHING', 'FIRST', 'cloudDemoJobHandler', '', 'SERIAL_EXECUTION', 0, 0, 'BEAN', '', '', now(), '');

commit;
```

- **索引口径**（「出 DDL 必出索引」义务的满足方式记档）：官方 8 表索引随官方 DDL 原样（关键者：`xxl_job_registry` uk `i_g_k_v` 命中注册心跳 upsert 与失效清理扫描；`xxl_job_group` uk `i_app_name` 命中按 appname 定组与回调鉴权；`xxl_job_log` 索引命中调度日志按任务+时间清理）——本项目**零自定义索引、零列改造、零新查询路径**，种子段仅 INSERT 无查询面。
- **执行方式与陷阱**：无 mysql 客户端 → java 单文件源码 + mysql-connector-j（沿 rocketmq-tx 落库先例）：UTF-8 显式读文件（GBK 环境陷阱）、连接 URL 带 characterEncoding=utf8、按 `;` 切分逐语句执行（官方文件已核实 16 语句、**字符串字面量内无 ASCII 分号**，朴素切分安全）。
- **幂等口径**：官方 CREATE TABLE 无 IF NOT EXISTS、种子固定主键——脚本**不可重放**；头注记档「重放需先 DROP DATABASE xxl_job（开发库可整库重建）」。

### D5 执行器注册地址：两方案实测预案（M3，backend-agent 实测取其一）

背景：本机多网卡（192.168.152.1 虚拟网卡，Nacos 注册 IP 同源坑）——xxl 自动探测 IP 不可用；且 **admin 在容器内回调 executor**，注册 `127.0.0.1` 不可达（那是容器自身回环）。

| | 方案一：钉 ip | 方案二：注册地址别名 |
|---|---|---|
| 配置 | `xxl.job.executor.ip: <宿主局域网 IP>`（Nacos per-service） | `xxl.job.executor.address: http://host.docker.internal:19202`（/19203） |
| 注册值 | `http://<lan-ip>:19202/`（xxl 拼 ip:port） | 配置字面量原样进 registry |
| 可达路径 | 容器→宿主 LAN IP（WSL2 出向 NAT，预期通） | 容器内 host.docker.internal 解析宿主（Docker Desktop 内置，与 M2 同机制） |
| 风险 | 依赖容器对宿主 LAN IP 的 NAT 出向可达性（实测） | registry 接受主机名字面量、admin 原样回调（实测）；文档未承诺此形态 |
| 优点 | xxl 标准用法，最少偏离 | 不依赖宿主网卡选点，与数据源通路同机制 |

- **建议实测顺序**：先方案一（标准形态）→ 不通换方案二；两案判定标准一致：**admin 执行器页 OnLine（注册地址正确）+ 手工触发任务调度成功**（回调可达即证）。最终采纳方案与配置值记入联调回报与移交备忘 5。
- 两案均不通的兜底排查序：容器内 `ping/curl host.docker.internal`、宿主防火墙对 19202/19203 入站放行（Windows Defender 首次监听弹窗确认）、executor netty 实际绑定面核对。

### D6 配置管理分工（Nacos 矩阵 + 仓库注释块）

| 键 | 落点 | 本地开发值 |
|---|---|---|
| `xxl.job.admin.addresses` | Nacos 共享 cloud-common.yaml | `http://127.0.0.1:18081` |
| `xxl.job.executor.logpath` | 同上 | `D:/applogs/xxl-job`（Windows 显式，勿落 Unix 默认 `/data/applogs`；目录 xxl 自动创建；两服务同目录安全——日志文件按全局 logId 命名无碰撞） |
| `xxl.job.executor.logretentiondays` | 同上 | `30`（默认值显式记档） |
| `cloud.common.xxljob.enabled` | per-service yaml | `true`（默认关，见 D1） |
| `xxl.job.executor.appname` | per-service yaml | `cloud-system` / `cloud-bpmn`（字面量=spring.application.name；不采用共享配置占位符 `${spring.application.name}`——字面量直白，占位符跨配置源解析顺序无谓风险） |
| `xxl.job.executor.port` | per-service yaml | `19202` / `19203`（服务端口+10000 望文知服务） |
| `xxl.job.executor.ip` 或 `.address` | per-service yaml | D5 实测采纳值 |
| `xxl.job.executor.accessToken` | per-service yaml | `default_token`（与 DB 种子组行一致，D4） |

- **共享配置接线（本轮唯一 application.yml 实质改动）**：现服务 `spring.config.import` 仅 `optional:nacos:${spring.application.name}.yaml`，共享配置未接线——两服务 import 追加 `optional:nacos:cloud-common.yaml`（optional 容缺：Nacos 无该配置时零影响，向后安全）。若实测 import 链有解析问题，退化为 per-service yaml 各写全量公共项（Nacos 侧重复两份，不进仓库，验收口径不变）。
- **仓库 application.yml 注释块**（rocketmq 先例形态，两服务同款）：

```yaml
# xxl-job 分布式调度（cloud-common-xxljob-starter，2026-10-09 整合）——连接配置走 Nacos 不进仓库：
#   共享 cloud-common.yaml: xxl.job.admin.addresses / executor.logpath / logretentiondays
#   cloud-system.yaml: cloud.common.xxljob.enabled=true
#                      xxl.job.executor.{appname=cloud-system, port=19202, ip|address=D5 采纳值, accessToken}
# enabled 未配（默认关）时 starter 零装配——admin 容器未起不阻断服务启动。
```

### D7 hello world job 形态（拍板：验收后保留为模板）

- 每服务 `job/` 包一个类：`com.cloudai.system.job.CloudDemoJobHandler` / `com.cloudai.bpmn.job.CloudDemoJobHandler`，`@XxlJob("cloudDemoJobHandler")`（两组同名 handler 合法——handler 名作用域=执行器组）。
- 处理逻辑（证据链最小完整）：`XxlJobHelper.getJobParam()` 取参数 → `XxlJobHelper.log` + `log.info` 回显（含服务名/appname 标识，admin 执行日志与 service 日志双证）→ 无异常即成功（xxl 缺省成功口径，不显式 handleFail）。
- javadoc 注明「新增 job 参照本模板 + admin 建任务（handler 名/路由 FIRST/调度 NONE 或 CRON）」——模板语义即下一轮对账任务的落位先例。
- 验收形态（B7）：执行器页两组 OnLine；两任务各手工触发一次（带参数），调度日志「执行结果=成功」、执行日志含参数回显；admin 容器重启后 90s 内 OnLine 自动恢复（registry 心跳 30s × 3）。

### D8 门控单测（拍板：对齐 ApprovalProjectionAutoConfigurationTest 先例）

`CommonXxlJobAutoConfigurationTest`（starter 模块，ApplicationContextRunner）：①enabled 未配 → 上下文无 XxlJobSpringExecutor bean（默认关直证，同时覆盖 imports 注册路径生效）；②enabled=true + `xxl.job.*` 样例值 → bean 存在且 appname/port/addresses/accessToken 断言（Properties→setter 映射正确性）。不强求 demo handler 单测（静态 ThreadLocal 上下文 mock 价值低于联调覆盖，handler 逻辑即回显）。

### D9 守护规则（本轮零新增，拍板）

本轮无既定规范可机械化（starter 门控由 D8 单测守护）；服务侧规则（候选：服务模块禁 `new XxlJobSpringExecutor` 直配、@XxlJob 任务类落位约定）下一轮随对账迁移与 backend-spec 增补同批落——记移交备忘 1。

### D10 文档落位

- **CLAUDE.md**：①拓扑 cloud-common 列表 + `cloud-common-xxljob-starter` 一行（XxlJobSpringExecutor 装配，enabled 默认关）；②关键约定增 xxl-job 段（接入模板=引依赖 + Nacos 两层配置 + enabled；admin http://127.0.0.1:18081（context-path 无）；镜像 tag≡core 版本同批升级）；③端口现状 18081/19202/19203；④Windows 陷阱段增 host.docker.internal 首例记档。
- **backend-spec**：本轮不增补（架构裁量，见文首）；下一轮 @XxlJob 任务模板 + 守护规则同批。

### D11 风险与实现时验证项

| # | 风险/不确定 | 处置 |
|---|---|---|
| R1 | host.docker.internal → 宿主 MySQL 连通（本机容器首例，M2） | B2 首步 netstat 核对 3306 绑定面；不通排查 Docker Desktop 版本/设置 |
| R2 | 注册地址两案可达性（M3） | D5 实测预案：先 ip 后 address，判定=OnLine+触发成功；兜底排查序已列 |
| R3 | CVE-2026-94145（stored XSS，影响 ≤3.5.0，JobInfoController） | 开发单机 + admin 不经网关对外、UI 仅本机访问——可接受记档；生产化前升级修复版（移交备忘 3） |
| R4 | xxl-job-core 传递依赖与 SB 3.3.4 收敛（netty/gson/groovy） | M1：dependency:tree 抽查记档；冲突则根 dependencyManagement 显式收敛 |
| R5 | 空密码 + 中文注释 SQL 的编码链路 | runner UTF-8 显式读文件 + URL characterEncoding=utf8（先例沿承）；官方文件 16 语句无字面量内分号（已核实） |
| R6 | 3.5.0 发布较新（2026 年）、社区踩坑资料少于 2.4.x | 排错以官方文档与 tag 源码为准；关键机制（DisposableBean/键名/seed 口令）已在设计期源码级核实 |
| R7 | executor 端口绑定失败阻断服务启动（与 @Scheduled 行为不同） | D1 默认关门控隔离；运维注意记 CLAUDE.md；起服日志可判 |
| R8 | 双轨期口径：xxl-job 与 @Scheduled 共存 | 零冲突（executor 自带线程体系，不涉 Spring scheduling）；本轮不动两任务（拍板），迁移路线记移交备忘 1 |

## 3. 错误处理汇总

| 故障 | 行为 | 观测/处置 |
|---|---|---|
| admin 容器未起/失联 | executor 内嵌 server 照常起，registry 后台线程周期重试注册，**服务启动不受阻** | 服务日志重试 warn；admin 起后 30s 内自动 OnLine |
| token 不匹配（DB 组行 vs executor 配置） | admin 拒绝注册/回调 | OnLine 空 + executor 日志 token 错误；两处值核对（D4/D6 同源） |
| 端口 19202/19203 占用 | executor 启动抛出 → **服务启动失败**（R7） | 起服日志 netty bind 异常；netstat 查占、换端口 |
| 任务执行异常 | handler 抛出 → 调度日志记失败（模板 retry=0 不重试） | admin 调度日志「失败」+ 执行日志异常栈 |
| admin 重启 | registry 心跳自愈重注册；期间触发失败由 admin 侧调度线程口径处置 | B7 验收④核对 90s 恢复 |
| logpath 不可写 | xxl 启动告警、执行日志缺失（调度不受阻） | 核对 D:/applogs 目录可写 |

## 4. 测试与验收口径

| 层 | 内容 |
|---|---|
| 单测（构建期） | D8 门控两用例；全量 357+ 既有单测零回归（本轮零业务代码改动，预期纯增量） |
| B2 容器验收 | logs 无数据源报错；登录页/登录成功取证 |
| B7 联调验收（真链路） | D5 实测取案 → 两组 OnLine → 两任务触发成功 + 参数回显双证（admin 执行日志 + 服务日志）→ admin 重启恢复核对 |
| 冒烟收口（B8） | 全栈起服（sso/system/bpmn/gateway）→ 网关 `curl /system/demo/ping` + 既有 leave 链路抽测 1-2 条，证 xxl 接入零破坏 |
| e2e | **本轮不涉**（无契约/前端改动面，纯后端轨道规则止于 curl）；主控裁量是否跑全量回归兜底 |

## 5. 移交后续阶段的备忘

1. **对账迁移路线（下一轮主目标）**：`ApprovalProjectionReconciler.reconcileActive` 从 @Scheduled → @XxlJob 薄壳 handler（调公有化后的 reconcileByCursor）；届时同批落：服务侧守护规则（D9 候选）+ backend-spec @XxlJob 任务模板增补 + `cloud.bpmn.projection.reconcile-interval-ms` 配置键退役评估 + @EnableScheduling 面收敛评估（rocketmq 清理任务仍在则保留）。job 落 cloud-bpmn-api、宿主 cloud-system 的装配链本轮已验证。
2. **MqTableCleanJob 迁移候选**：每服务清各自库，迁 xxl 后按 appname 两组各一任务（或分片广播）——评估时注意两库保留期可差异配置。
3. **CVE-2026-94145 跟进**：官方出修复版后同批升级（镜像+core+DDL diff，流程同备忘 6）；生产化前置条件。
4. **token 生产化**：`default_token` 是公开默认值；生产换强 token 需 **DB 组行（UPDATE xxl_job_group）与 Nacos executor 配置双处同步**；admin 登录口令（种子 admin/123456）同步更换。
5. **admin 回调地址口径**：本轮采纳的注册方案与部署形态强绑定（单机 Docker Desktop + 宿主 java 进程）；服务多实例/容器化/上 K8s 时需重估（K8s Pod IP 自注册可去显式 ip/address）。
6. **镜像版本升级纪律**：admin 镜像 tag ≡ xxl-job-core 版本同批升；升级时 diff 官方新 tag `doc/db/tables_xxl_job.sql` 与仓内脚本官方段，DDL 有变则出增量 ALTER；本项目种子段重放核对（固定主键 id 3/4/2/3 冲突面）。
7. **告警通道**：admin alarm_email 未配置（种子空串），任务失败告警缺位——与 RocketMQ 监控移交同源，监控体系轮统一评估。
