# local 配置完整化与默认 local 启动——技术方案（2026-11-15）

> 术语约定（本文档内使用）：
> - **local 面**：`<服务>/src/main/resources/application-local.yml`——gitignored，由备份脚本自动生成的本地配置文件；激活条件为启动参数 `--spring.profiles.active=local`。
> - **jar 静态面**：打进 jar 的 `application.yml`——git 跟踪，环境中性（不含密钥、不含环境判定）。
> - **Nacos 面**：Nacos 配置中心的 6 个 data_id（cloud-common + 5 个服务 yaml）。
> - **离线声明**：local 激活时服务不 import Nacos 配置、不注册 Nacos 发现——显式的「本进程不依赖 Nacos」。
> - **静态实例表**：`spring.cloud.discovery.client.simple.instances.*`——手写的服务名→地址映射，替代 Nacos 服务发现。

## 1. 背景与拍板结论

现状：local 面是备份脚本（`cloud-base/scripts/nacos/backup_nacos_config.py`）从 Nacos 库导出的快照合并物，作为 Nacos 故障时的兜底（2026-11-11 对 sso 单服务实证过配置面启动），但**不是默认启动路径**，且兜底只覆盖配置面——Nacos 停机时服务发现不可用，仅支持直连服务端口，网关 lb:// 路由与跨服务 Feign 全断。

用户需求（2026-11-15）：local 的 yml 要有完整配置、默认启动使用 local、local 配置要能完整启动服务。

拍板结论（四项全按架构倾向，另采纳两条低风险建议）：

| # | 结论 |
|---|---|
| 1 | 「默认 local」落点 = **脚本层**：dev-services.sh 与 CLAUDE.md Commands 段启动命令统一带 `--spring.profiles.active=local`；jar 内 application.yml 保持环境中性不写死 |
| 2 | 真值源 = **Nacos 唯一编辑入口 + 启动前自动快照**：dev-services.sh start 起服前自动跑备份脚本重生成 local 面；改 Nacos → 下次重启即生效；备份失败容忍降级用既有快照继续起服并打 WARN，不阻断 |
| 3 | 「完整启动」口径 = **全栈可用**：网关 18080 lb 路由通 + 跨服务 Feign 通 + 前端可用，停 nacos 可全栈起；机制 = simple discovery 静态实例表 + 网关 local 面；AI 两服务（cloud-base-rag :8011 / cloud-base-agent :8012）纳入网关静态表 |
| 4 | local 语义 = **显式离线模式**：local 激活即同时关 Nacos config import 与 discovery——是明确的离线声明，不是优先级之争 |
| 5 | （主控代采纳）备份体系随本轮补独立 spec/plan 记档（即本文档与配套 plan）；AI 基座 .env 体系不动 |

### 1.1 默认路径对依赖面的改变（本轮最重要的架构后果）

拍板 3+4 联动的直接后果，必须显式记档：

- **默认启动路径（脚本带 local）不再依赖 nacos 容器**——服务不 import Nacos 配置（离线声明）、不注册 Nacos 发现（静态实例表接管）。MySQL / Redis / RocketMQ / xxl-job-admin 容器照旧是硬依赖。
- **自动快照只读 MySQL 的 nacos_config 库（pymysql 直连），不经 Nacos API**——所以默认路径的依赖清单里是「MySQL（含 nacos_config 库）」而非「nacos 容器」。nacos 容器降级为**编辑时依赖**：只有改配置时需要它运行。
- Nacos 的职责在默认路径下收敛为：配置编辑入口 + 快照数据源（库）。服务发现职责由静态实例表承担。
- 副产物：nacos 控制台服务列表在默认路径下无实例属**预期形态**，不是故障。

## 2. 现状盘点：配置三面的分界（已实读全部配置源）

### 2.1 逐服务清单

| 服务 | jar 静态面（application.yml，git 跟踪） | Nacos 面（→ local 面快照段） | 备注 |
|---|---|---|---|
| sso :9201 | 端口/名称/nacos addr/Feign 超时熔断 | common（cloud.jwt.secret + 两 TTL、xxl admin/logpath）+ sso（inner.shared-secret） | 无 DB；**Redis 无任何显式配置，吃 Boot 默认 127.0.0.1:6379**（隐式依赖，本轮仅记档不改动） |
| system :9202 | 端口/名称/**数据源（含账密，在仓库）**/MCP server 名/业务开关键/mybatis | rocketmq、cloud.mcp.client-secret、xxljob executor 19202、inner+rotation、redis | |
| bpmn :9203 | 端口/名称/multipart 上限 | **数据源**、redis、flowable、rocketmq、xxljob 19203、inner | 数据源位置与 system/task 不一致（历史形态，本轮不统一） |
| task :9204 | 端口/名称/**数据源（在仓库）**/任务引擎参数 | redis、xxljob 19204、inner | |
| aggregate :9205 | 端口/名称/pool-size | pool-size 同值双写 | 无 DB/Redis/MQ |
| gateway :18080 | 端口/名称/**全部路由 + CORS** | **无服务专属 dataId，但 import 消费 cloud-common.yaml（cloud.jwt.secret 网关验签必需）** | 本轮关键修正：CLAUDE.md「gateway 无 Nacos 配置」的说法不精确，gateway local 面必须含 common 快照段（见 §4.3） |
| rag :8011 / agent :8012 | .env 体系（cloud-base-rag / cloud-base-agent 工程） | 无 | 不依赖 Nacos 配置；Nacos 仅作注册（默认路径下注册静默失败，服务面不受影响） |

### 2.2 备份脚本现状

`backup_nacos_config.py`：导出 Nacos 库全租户配置到 `cloud-base/nacos-local/`（snapshots/ + current/，gitignored），并为有 dataId 的仓库内服务生成 `<svc>/src/main/resources/application-local.yml`（内容 = 头注释 + cloud-common.yaml + 服务 yaml 多文档合并）。**按 dataId 驱动**——gateway 无 dataId 所以从未生成过 local 面；生成物也无任何离线声明段。

## 3. 总体设计

```
【编辑时】                          【启动时（默认路径）】                     【运行期】
Nacos 控制台（唯一编辑入口）           dev-services.sh start                    服务进程（离线模式）
   │ 发布配置                          │ 1. 跑 backup_nacos_config.py            │ 不 import Nacos 配置
   ▼ 持久化                            │    （pymysql 直读 nacos_config 库）      │ 不注册 Nacos 发现
MySQL nacos_config 库  ──────────────►│ 2. 重生成 7 份 application-local.yml    │ lb:// 解析走
（真相源，双租户）                      │    （5 服务快照+离线段，网关模板）        │ 静态实例表
                                      │ 3. 全部 java -jar 带 --spring.profiles.active=local
                                      ▼
                                      服务起（local 激活）
```

设计决策（代号仅在本文档内使用）：

- **D1 默认落点=脚本层**：`--spring.profiles.active=local` 出现在 dev-services.sh 五条 java 命令与 CLAUDE.md Commands 段；jar 静态面不含 profile 判定。手动不带 local 启动 = 传统路径（import Nacos + 注册发现），完整保留。
- **D2 真值源=Nacos + 启动前自动快照**：见上图。配合「全仓无 @RefreshScope」的事实——Nacos 改配置本来就要重启生效——自动快照使「改 Nacos → 下次重启生效」与今天语义等价，遮蔽窗口收敛为「绕过脚本裸启动且快照陈旧」一种场景。
- **D3 local=显式离线**：三件套——import 门控（jar 静态面重构，§4.1）+ discovery 关闭 + import-check 关闭（后两者在 local 面离线段，§4.2）。
- **D4 静态实例表落 local 面（脚本生成）**：七服务全表（五 Java + AI 两服务）由备份脚本写入每份 local 面；网关与业务服务同构全表（多余条目无害——simple 表只是静态数据）。不落 jar 静态面：避免与 nacos discovery 双供实例（LoadBalancer 会合并多 DiscoveryClient 同名实例，导致半数流量打到 Nacos 注册 IP——多网卡机器不可达风险）。单一来源防漂移。
- **D5 网关 local 面=common 快照+离线段**：脚本内置网关模板（无服务 dataId，但消费 cloud-common.yaml）。
- **D6 遮蔽自检不做 md5 对比**：启动前自动快照已保证「local 面与 Nacos 同源」，再对比 md5 无增量价值；备份失败时打 WARN 即陈旧信号。取舍记档。
- **D7 端口矩阵单点维护**：静态实例表（服务名→host:port）以常量内置备份脚本，与生成逻辑同文件——端口变更改一处。

## 4. 关键机制设计

### 4.1 M1：Nacos config import 的 profile 门控（jar 静态面重构）

**问题**：`spring.config.import` 写在 application.yml 主文档里，在 config data 处理早期（profile 文件加载前）就会执行——把 `spring.cloud.nacos.config.enabled=false` 写进 application-local.yml 来不及阻止第一波 import（2026-11-11 演练实证：local 激活时 import 照跑、gRPC 重连 ERROR 刷屏）。

**机制**：import 是 per-document 属性。把 import 块挪进文件末尾的 `on-profile: "!local"` 门控文档——local 激活时该文档不生效，import 随之跳过；非 local 时照常 import。六服务（四胞胎 + aggregate + gateway）同构重构，模板级同步。

重构后 application.yml 尾部形态（示意，以 sso 为例）：

```yaml
spring:
  application:
    name: cloud-sso
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:127.0.0.1:8848}
    # ...（其余原有内容不动）

# Nacos 配置 import 门控：local 激活时跳过 import（本地离线模式——配置取备份脚本生成的
# application-local.yml，从本 src/main/resources 目录启动才命中 file:./ 搜索位，不触碰 Nacos）；
# 非 local 启动照常从 Nacos 拉取。
---
spring:
  config:
    activate:
      on-profile: "!local"
    import:
      - optional:nacos:cloud-common.yaml
      - optional:nacos:${spring.application.name}.yaml
```

注意事项（实现时逐条核对）：
- 各服务 import 条目**保持原有顺序与注释**：system 与 aggregate 是「服务 yaml 在前、common 在后」并带接线注释，迁移时原样搬移。
- `!local` 必须加引号（YAML 中 `!` 开头未加引号会被解析为 tag）。
- `spring.profiles.active` 不能出现在门控文档（Spring Boot 禁止，启动即报错）——本方案不涉及。
- application-local.yml（profile-specific 文件）**禁止**含 `spring.config.import`（Spring Boot 禁止）——离线段不含它。

> **实证修正（2026-11-15 实施验收）**：上方注释里「配置取 jar 内 application-local.yml」的加载假设不成立——启动脚本用预构建 jar，备份脚本再生成的 local 面进不了 jar，首次全栈演练五服务+网关全部触发 import-check fail-fast（下方「POC 实证记档」章的验证流程含「生成后立即 mvn 重打包」一步，恰好掩盖了此缺口）。修正口径：Java 服务从各服务 `src/main/resources` 目录启动，Spring 的 `file:./` 搜索位命中再生成文件且优先级高于 jar 内打包副本——改 Nacos 免重打包、重启即生效。过程与幕二实证详见实施计划文档第四章第 4 条回填。

### 4.2 M2：local 面离线段（discovery 关闭 + import-check 关闭 + 静态实例表）

local 面在既有快照内容之外追加一个文档：

```yaml
---
# local 离线段：不注册 Nacos 服务发现，lb:// 解析改走下表（声明即真相——
# 条目指向的实例未启动时流量会打到死端口，属本模式已知语义）。
# import-check 关闭：local 模式下 import 无 nacos 条目是预期，SCA 检查器会 fail-fast。
spring:
  cloud:
    nacos:
      config:
        import-check:
          enabled: false
      discovery:
        enabled: false
    discovery:
      client:
        simple:
          instances:
            cloud-sso: [{uri: 'http://127.0.0.1:9201'}]
            cloud-system: [{uri: 'http://127.0.0.1:9202'}]
            cloud-bpmn: [{uri: 'http://127.0.0.1:9203'}]
            cloud-task: [{uri: 'http://127.0.0.1:9204'}]
            cloud-aggregate: [{uri: 'http://127.0.0.1:9205'}]
            cloud-base-rag: [{uri: 'http://127.0.0.1:8011'}]
            cloud-base-agent: [{uri: 'http://127.0.0.1:8012'}]
```

（上表为示意——实际生成格式以脚本常量为准，七条目全表写入每一份 local 面。）

三个开关的时序依据（POC 实证，详见 §9）：
- `discovery.enabled=false` 与 `import-check.enabled=false` 都是**运行期读取**的属性，profile 文件已加载后才被消费——写在 application-local.yml 生效。
- 静态实例表只配在 local 面（D4）：非 local 模式下它不存在，nacos discovery 独供实例，无双供风险。

### 4.3 M3：网关 local 面（脚本内置模板）

网关无服务 dataId，但 import 消费 cloud-common.yaml——**网关 AuthGlobalFilter 验签依赖 cloud.jwt.secret**（POC 实证：local 面缺 common 快照时，带 token 请求在验签处 NPE，兜底为 HTTP 200 + body code 500，极易误判为下游业务错误）。

网关 local 面 = 头注释 + **cloud-common.yaml 快照段** + **离线段**（同 §4.2，含七条静态表——网关是静态表最重的消费方）。由备份脚本新增网关生成分支产出，与快照同源刷新。

### 4.4 M4：启动前自动快照与失败容忍（dev-services.sh）

- `cmd_start` 在起第一个 Java 服务前跑 `backup_nacos_config.py`（经 cloud-ai-common 环境）；成功则 local 面与 Nacos 同源刷新，失败（如 MySQL 不可达）打 `[WARN]` 后用既有 local 面继续起服，不阻断。
- 五条 java 命令追加 `--spring.profiles.active=local`（AI 两服务与前端命令不动——.env/vite 体系无 profile 概念）。
- 双重故障声明：MySQL 与 nacos 同时不可达**且**既有快照陈旧时，local 面可能带过期配置起服——属声明的已知边界（此场景本身意味着基础设施大面积故障），WARN 日志是唯一线索。
- 脚本头注释的前置假设段同步改写：nacos 容器从默认路径依赖中移除（编辑时依赖）。

### 4.5 M5：头注释与文档口径

生成文件头注释现行文案「健康环境勿带 local」按拍板反转，改写为「默认启动即带 local（见 dev-services.sh / CLAUDE.md）；local = 离线模式；改 Nacos 后由脚本在下次启动自动重生成本文件」。CLAUDE.md 改写点见 plan T4。

## 5. 各服务 local 面最终构成

| local 面 | 构成（自上而下） |
|---|---|
| sso / bpmn / task | 头注释 + common 快照段 + 服务 yaml 快照段 + 离线段 |
| system / aggregate | 同上（aggregate 的服务 yaml 段仅 pool-size，双写无害） |
| gateway | 头注释（网关模板版）+ **common 快照段** + 离线段（无服务 yaml 段） |

全部 gitignored、含密钥真值、每次备份整体重生成（幂等，勿手改）。

## 6. 错误处理与降级矩阵

| 场景 | 行为 | 依据/实证 |
|---|---|---|
| 备份脚本失败（MySQL 不可达） | `[WARN]` + 沿用既有 local 面继续起服 | D2 失败容忍 |
| nacos 容器停机（默认路径） | 无影响——不 import、不注册 | POC 幕一 |
| 静态表条目在、实例未启动 | 流量打到死端口，网关错误兜底；不自动摘除 | POC 实证；声明即真相语义，注释记档 |
| 新环境无 local 文件 | profile 激活但文件缺失不报错；脚本 start 会先跑备份生成（MySQL 可达即成） | Spring 语义 + M4 |
| AI 服务 nacos 注册失败 | 服务面不受影响；注册重试 ERROR 持续刷日志（Python 侧形态，已知噪音） | POC 实证（rag 停 nacos 下 8011 正常服务） |
| 双重故障（MySQL+nacos 同挂且快照陈旧） | 可能以过期配置起服，WARN 是唯一线索 | 已知边界声明 |

## 7. 测试与验收

两幕演练（plan T5 执行，全栈含 AI 两服务与前端）：

**幕一：停 nacos 全栈**
1. `docker stop nacos` → `./scripts/dev-services.sh start`
2. 全部服务起（五 Java local 激活 + AI + 前端）
3. 网关冒烟：`GET /system/demo/ping`（带 token）200；`POST /sso/auth/login` 200 返回双 token（实证网关 lb→sso 与 sso Feign lb→system 两链）；AI 面经网关一端点（rag，应用层响应即穿透）；`GET /aggregate/workbench` 段式 200
4. 五个 Java 服务日志**零 nacos 连接/重连 ERROR**（静态日志适配 INFO 属可接受残留）

**幕二：nacos 健康默认路径 + 传统路径保留**
1. `docker start nacos` → 脚本默认起（仍带 local）→ 同套冒烟通过
2. 验证离线声明：服务未注册进 nacos（控制台无实例或无注册日志）
3. 验证同步链：Nacos 改一个无害键（如 cloud-common 的 access-token-ttl）→ restart → 新值生效（快照自动重生成）
4. 传统路径抽查：手动不带 local 起一个服务 → import 正常、注册 nacos、网关经 nacos 发现路由通

回归面：零 API 契约变化轮，不进 e2e 必选回归；mvn 全量构建+测试须绿（yml 结构重构不破坏既有装配/守护测试）。

## 8. 边界与已知取舍

**不做**：配置中心替换；多环境 profile 体系（dev/test/prod）；容器编排（MySQL/Redis/RocketMQ/xxl-job-admin 仍是声明式外部前提）；AI 基座 .env 改造；数据源配置位置统一化（system/task 在仓库、bpmn 在 Nacos、sso 无——历史形态另立轮）。

**已知取舍记档**：
1. 默认路径下 Nacos 服务发现职能被静态表替代——静态表无健康检查（实例 down 不摘除），本地固定端口场景可接受。
2. AI 服务注册重试日志噪音：nacos 停机期间 rag/agent 日志持续刷注册失败 ERROR，服务面不受影响；如需静默属 AI 侧后续可选项（不在本轮）。
3. sso 的 Redis 隐式依赖（无显式配置，吃 Boot 默认 127.0.0.1:6379）：行为不变，CLAUDE.md 记档。
4. 绕过脚本裸启动（不带 local）+ 快照陈旧的遮蔽场景仍存在——文档口径（CLAUDE.md）承担暴露责任，不做代码级自检（D6）。
5. 四胞胎模板 + aggregate + gateway 六处 yml 同构改动——后续新增服务沿用同构（plan T1 验收含此口径）。

## 9. POC 实证记档（2026-11-15，本方案的全部机制均经真机验证）

载体：sso + system + gateway 三模块临时改造（门控 import + 离线段 + 网关 local 面），mvn 重打包后停 nacos 实跑；验毕全部恢复（git 零残留、生成物重跑备份复原、jar 重打包、nacos 重启）。

| # | 验证点 | 结果 |
|---|---|---|
| P1 | `on-profile: "!local"` 门控文档跳过 import | 成立——system 日志零 nacos 行（nacos client 完全不初始化）；sso/gateway 仅 5 行静态日志适配 INFO，无连接 |
| P2 | import-check 陷阱 | 实证 fail-fast：配了 server-addr 但 import 无 nacos 条目 → APPLICATION FAILED TO START；`spring.cloud.nacos.config.import-check.enabled=false` 写 application-local.yml 可解（检查器晚于 profile 文件加载） |
| P3 | discovery 关闭（profile 文件内） | 生效——零注册、零重连 ERROR |
| P4 | 静态实例表喂网关 lb（WebFlux） | 成立——`GET /system/demo/ping` 经网关 200 |
| P5 | 静态实例表喂 Feign lb | 成立——登录链 200 返回 accessToken/refreshToken（网关→sso + sso Feign→system /inner + inner 密钥头 + Redis 会话全链） |
| P6 | local 快照配置生效 | 成立——system 在 nacos 停机下 MQ producer（p_system_tx）init、5 个 listener 注册、Redis/xxl-job 正常（全来自 application-local.yml） |
| P7 | 网关 local 面缺 common 快照的故障形态 | 实证——JWT secret 为 null → 验签 NPE → 网关兜底 HTTP 200 + body code 500（易误诊为下游业务错误）；补 common 快照段后恢复 |
| P8 | AI 面经网关 | 成立——`/ai/rag/**` 经静态表命中 :8011，返回 FastAPI 应用层响应 |
| P9 | AI 服务（Python）停 nacos 形态 | 服务面正常（8011 监听、请求响应正常）；nacos 注册重试 ERROR 持续刷屏（527 行/数分钟），不阻断 |
| P10 | 静态表死条目语义 | 实证——cloud-base-agent 条目在、实例未起，流量打到死端口由网关兜底 |

## 10. 移交后续阶段的备忘（预置，plan 收尾按实况回填）

1. AI 服务注册重试噪音静默选项（Python 侧 nacos client 日志级别/注册开关）——待后续 AI 侧轮次。
2. 静态实例表端口矩阵与 CLAUDE.md 端口段的同步是人工纪律（单点在备份脚本常量，D7）——端口变更时两处同改。
3. e2e 回归不覆盖本机制（零契约变化）；后续若做「环境自检」类工具可整合两幕演练为脚本。
