# 后端全服务容器化技术方案（2026-10-22）

> 结论先行：4 个 Java 服务（gateway/sso/system/bpmn）+ cloud-base-ai 全部容器化，仓库根一份 `docker-compose.yml`（项目名 `cloud-app`）统一编排；跨栈连接全走 `host.docker.internal` 已发布端口（复用 xxl-job-admin→MySQL、nacos→MySQL 两条已验证通路）；配置注入全部走 compose environment 环境变量，**Nacos 配置中心零改写**（规避双租户改写风险）；宿主 `java -jar`/`uvicorn` 直起作为非官方应急通道保留（env 对称设计天然支持）。本轮零 API 面，无契约文档。

## 0. 背景与拍板结论

本方案承接 2026-10-22《需求分析》与用户拍板：

| # | 拍板项 | 结论 |
|---|---|---|
| 1 | 网络模型 | 应用 compose 单网络；跨栈（Nacos/Redis/RMQ/xxl/MySQL/Milvus/MinIO/Ollama）全走 host.docker.internal 已发布端口 |
| 2 | 配置注入 | compose environment 全量 env 注入；零 Nacos 改写；宿主直起仍吃默认 127.0.0.1 |
| 3 | 构建形态 | Java=宿主 mvn package + COPY jar；AI=python:3.12-slim + uv sync --frozen；基础镜像经 docker.m.daocloud.io 拉取回打 tag |
| 4 | 内存 | 显式 JAVA_OPTS -Xmx 逐服务定值 + compose mem_limit 兜底；langfuse 栈已停用（重启路径记移交备忘） |
| 5 | 应急通道 | 保留 env 对称设计，宿主直起为非官方应急通道；容器为唯一常态运行态 |
| 6 | milvus | etcd raft 损坏，拍板清空数据卷；设计按「验收前修复完成、空库」前提写；向量重灌记移交备忘 |
| 7 | 调度 | 实施排在 dataperm/ProTable 两轮提交之后（两轮都重启后端服务，串行约束） |

分析阶段盘出的关键既有事实（本方案直接采用）：xxl executor 注册地址在 Nacos 中已是 `address: http://host.docker.internal:1920x` 形态（容器发布端口后零改动）；sso/gateway/system 三服务 Redis 无显式配置（吃 Spring Boot 默认 localhost）；`xxl.job.*` 由 xxljob-starter 以 `@ConfigurationProperties(prefix = "xxl.job")` 绑定（env relaxed binding 可覆盖）；Redis 连接工厂走 Spring Boot 标准装配（`spring.data.redis.*`）。

## 1. 目标与非目标

**目标**：
1. 5 个后端服务镜像化 + compose 一键编排（构建/启停/日志/健康检查统一入口）
2. 全链路在容器态验收通过：网关路由、Nacos 注册、xxl 回调、RocketMQ 事务消息、MCP 工具面、AI RAG
3. 冷启动自愈：Docker Desktop 重启后 compose 一键拉起（或 restart 策略自动拉起）全链路恢复
4. 宿主应急直起通道可用（文档化）

**非目标**（明确不做）：
- 基础容器（nacos/redis/rocketmq/milvus/minio/xxl-admin 等）不搬进 compose、不重构其网络
- MySQL 不容器化；前端 cloud-web 与 cloud-e2e 工程不容器化（端口发布保持即零改动）
- 不做 K8s / CI / 镜像仓库推送；不复活 rocketmq-dashboard；不动 langfuse 栈（已停用）
- 无新 API、无数据库变更、无契约文档

## 2. 总体架构

### 2.1 三元拓扑

```
┌─────────────────────────── Docker Desktop (WSL2, 数据盘 D:\DockerDesktopWSL) ───────────────────────────┐
│                                                                                                          │
│  ┌── 新建 compose 网络 cloud-app-net (172.28.0.0/24, 静态 IPAM) ──┐      ┌── 既有基础容器（独立管理）──┐   │
│  │                                                              │      │  nacos       :8848/9848/9849 │   │
│  │  cloud-gateway   172.28.0.2  发布 127.0.0.1:18080 ────────────┼──┐   │  redis       :6379           │   │
│  │  cloud-sso       172.28.0.3  发布 127.0.0.1:9201              │  │   │  redis-cluster :7000/17000   │   │
│  │  cloud-system    172.28.0.4  发布 0.0.0.0:9202 + 19202 ◄──────┼──┤   │  xxl-job-admin :18081        │   │
│  │  cloud-bpmn      172.28.0.5  发布 127.0.0.1:9203 + 19203      │  │   │  rmqnamesrv/rmqbroker        │   │
│  │  cloud-base-ai   172.28.0.6  发布 127.0.0.1:8010              │  │   │  milvus      :19530 等       │   │
│  │                                                              │  │   │  minio       :9100/9101     │   │
│  └──────────────────────────────────────────────────────────────┘  │   └──────┬───────────────────────┘   │
│                                    容器间调用走 172.28.0.x 直连     │          │ 既有网络：默认 bridge 等     │
│                                     （网关 lb / AI→system 可选）   │          │ （无容器 DNS，仅端口发布）   │
└──────────────────────────────────────────────────────────────────┼──────────┼──────────────────────────────┘
                                                        host.docker.internal（宿主回环通路）
                                                                   ▼          ▼
                                                    ┌────────────────────────────────┐
                                                    │ Windows 宿主                    │
                                                    │  MySQL 原生服务 127.0.0.1:3306  │
                                                    │  Ollama        127.0.0.1:11434 │
                                                    │  vite dev server :5173 → 18080 │
                                                    │  构建工具链 mvn/JDK17           │
                                                    └────────────────────────────────┘
```

要点：
- **同一 compose 网络内**：容器名 DNS 可用、172.28.0.x 互访（网关 lb:// 到四服务、AI 到 system 的 MCP 调用即走此通路或宿主发布端口，见 D13）
- **跨栈**：所有容器 → `host.docker.internal:<宿主发布端口>` → 目标容器/原生服务（Docker Desktop 全容器可解析该别名；xxl-job-admin、nacos 两容器连宿主 MySQL 已是该通路先例）
- **前端/e2e 零改动**：流量形态不变（5173 dev proxy → 宿主 18080 → gateway 容器发布端口）

### 2.2 服务间四条链路推演（容器态）

| 链路 | 通路 | 依据 |
|---|---|---|
| 网关 lb://cloud-sso 等 | Java 服务注册各自 172.28.0.x 进 Nacos（容器单网卡自动探测，宿主多网卡坑不复现）；gateway 容器同网络直连容器 IP | Nacos 客户端自动注册 |
| AI 服务进网关路由 lb://cloud-base-ai | AI 注册固定 ip=172.28.0.6（CLOUD_BASE_AI_NACOS__IP 注入，python sdk 无探测）；gateway 容器直连 172.28.0.6:8010 | D2 静态 IPAM |
| xxl-admin 容器 → executor 回调 | Nacos 现值 `address: http://host.docker.internal:19202/19203` **零改动**；system/bpmn 容器发布 19202/19203 到宿主 | 分析实测 Nacos 现值 |
| AI 容器 → system /mcp（直连，刻意不走网关） | `CLOUD_BASE_AI_MCP__BASE_URL=http://host.docker.internal:9202/mcp`；system 容器发布 9202 | D13 |

其余依赖通路（全部 host.docker.internal + 既有发布端口）：Nacos 8848/9848/9849、Redis 6379、RocketMQ 9876（namesrv）+ 10911 等（broker，见 R7 验证项）、xxl-admin 18081、MySQL 3306、Milvus 19530、MinIO 9100、Ollama 11434。

## 3. 决策记录（本文档内编号 D1–D14）

- **D1 网络模型=单 compose 网络 + host.docker.internal 跨栈**（用户拍板）。不挂外部网络（rocket_rocketmq 等）：多宿主容器会引入 Nacos 注册网卡选择歧义，且 broker 通告地址是宿主 IP、namesrv 可达不等于 broker 可达，收益打折。
- **D2 compose 网络显式命名 `cloud-app-net`，子网 172.28.0.0/24，五服务静态 IP**（.2–.6）。已核实与既有网络（bridge 172.17 / rocketmq 172.18 / rocket_rocketmq 172.19 / langfuse 172.20 / minio 172.21）无冲突。静态 IP 的必要性：AI 的 Nacos 注册 ip 是固定配置值（无探测），必须可预知；Java 服务顺带获得稳定 IP（注册值可预期，便于 Nacos 控台排查）。
- **D3 端口发布清单见 §4**。绑定口径：跨容器回调链路上的端口（9202/19202/19203）绑 0.0.0.0（与 nacos、xxl-admin 既有容器一致——host.docker.internal 从容器到宿主，回环绑定不可达是常态预期）；纯本机消费端口（18080/9201/9203/8010）绑 127.0.0.1（不暴露局域网）。
- **D4 Java Dockerfile 每服务一份（4 份内容相同）+ 同目录 docker-entrypoint.sh（4 份相同）**。弃单份参数化方案的原因：compose 的 build.dockerfile 不支持 context 外路径（BuildKit 拒绝），而 context 提升到 cloud-base/ 根需白名单式 .dockerignore 兜住整个 monorepo——两方案都引入边缘行为；4×(5 行 Dockerfile + 15 行 entrypoint) 重复成本趋零，且保留将来单服务差异化自由度。context=各服务目录（仅含 Dockerfile/entrypoint/target）。
- **D5 entrypoint 探测循环**：起 JVM 前等 Nacos(8848) 与 MySQL(3306) 可达（bash /dev/tcp，最多 120 次×2s）。根因：`optional:nacos:` import 在 Nacos 不可达时静默跳过且**不会重试**——服务会带残缺配置（Redis 落回 localhost、rocketmq 键缺失）「假活」；Flowable 的 schema 检查（database-schema-update: true）启动即连库。探测循环把外部就绪顺序问题挡在 JVM 之前，冷启动确定性由此保证。AI 容器同款探测 8848（AI 注册失败会降级且不重试，探测防降级）。
- **D6 AI 镜像=python:3.12-slim + pip 装 uv（清华 PyPI index）+ `uv sync --frozen --no-dev` 分层**。弃 `COPY --from=ghcr.io/astral/uv`：ghcr 直连不通且 daocloud 不代理 ghcr。分层：先 COPY pyproject+uv.lock 装依赖（`--no-install-project`），后 COPY src 再补一次 sync（只装项目自身，秒级）——迭代重建只动源码层。
- **D7 env 注入矩阵见 §5**。优先级链：compose `environment` > `env_file`（cloud-base-ai/.env，仅凭据类）> 代码默认值。端点类全部由 compose 钉死——即使用户本地 .env 写了端点值也会被覆盖（**意图：.env 只承载凭据，端点归 compose 统一治理**）。Spring 侧机制依据：OS 环境变量优先级高于 config data import（含 Nacos 导入），relaxed binding 对 `@ConfigurationProperties` 与标准 `spring.data.redis.*` 均生效。
- **D8 JVM 内存定值 + mem_limit 兜底（拍板）**：显式 `-Xms/-Xmx/-XX:MaxMetaspaceSize`（表见 §8）+ compose `mem_limit` 硬顶（防宿主 OOM；Java17 容器感知为第二道保险）。Python 容器仅 mem_limit。
- **D9 健康检查**：Java=容器内 `bash /dev/tcp/127.0.0.1:<port>` 端口探测（temurin JRE 无 curl/wget，不为此加包；Tomcat 端口监听≈启动完成）；AI=`python -c urllib` 打 `/health`。`start_period` 90s（Spring 启动 + entrypoint 探测窗口）。gateway `depends_on` 其余四服务 `service_healthy`——「网关最后起」从手工约定变为编排事实。
- **D10 restart: unless-stopped 全服务**（对齐基础容器口径）+ 冷启动自愈演练为验收项（§10）。
- **D11 基础镜像经 docker.m.daocloud.io 拉取回打 tag**（nacos 轮先例）：`eclipse-temurin:17-jre`、`python:3.12-slim`（官方镜像在 daocloud 路径带 `library/` 前缀）。失败 fallback：备选镜像源 → 最后手段用宿主 JDK 打自有基础镜像。
- **D12 应急直起通道保留（拍板）**：env 对称设计下，宿主 `java -jar`（不传 env 吃仓库默认 127.0.0.1）与 uvicorn 直起天然可用，文档声明为非官方应急通道（CLAUDE.md 更新任务内落档）。
- **D13 AI→system MCP 通路选 host.docker.internal:9202 而非 compose DNS**：与全部其它跨栈连接形态统一（单一心智模型），且混合态（部分容器 + 部分应急直起）下依然成立——system 应急直起时宿主 9202 由宿主进程监听，AI 容器连接不断。代价：依赖 9202 发布（本就为 MCP 冒烟脚本而发布）。
- **D14 Ollama 宿主绑定适配（本轮新发现）**：AI 的 embedding 走宿主 Ollama（默认 `http://127.0.0.1:11434`，模型 bge-large-zh-v1.5 实测在位）。env 注入 `CLOUD_BASE_AI_EMBEDDING_LLM__BASE_URL=http://host.docker.internal:11434`。**主控规格审查实测（2026-10-22）：本机 Ollama 已监听 0.0.0.0:11434，且经 nacos 容器 `curl http://host.docker.internal:11434` 返回正常——容器可达已成立，无需宿主变更**（原预判「默认只绑 127.0.0.1 需设 OLLAMA_HOST=0.0.0.0」与实机不符，据此解除实施前置；若未来 Ollama 重装/升级后回退为 127.0.0.1 绑定，再按原方案设 `OLLAMA_HOST=0.0.0.0` 并重启 Ollama，局域网暴露取舍届时知情）。

## 4. 端口发布硬性清单

| 服务 | 容器端口 | 宿主绑定 | 级别 | 消费方 |
|---|---|---|---|---|
| cloud-gateway | 18080 | 127.0.0.1 | 硬性 | vite proxy、cloud-e2e、冒烟 |
| cloud-system | 9202 | **0.0.0.0** | 硬性 | AI 容器 MCP 调用（D13）、宿主 MCP 冒烟脚本 |
| cloud-system | 19202 | **0.0.0.0** | 硬性 | xxl-admin 容器回调（Nacos 现值零改动的依赖面） |
| cloud-bpmn | 19203 | **0.0.0.0** | 硬性 | 同上 |
| cloud-base-ai | 8010 | 127.0.0.1 | 硬性 | 直连调试、AI 冒烟脚本 |
| cloud-sso | 9201 | 127.0.0.1 | 兼容 | 调试观测（流量本走网关） |
| cloud-bpmn | 9203 | 127.0.0.1 | 兼容 | 调试观测 |

## 5. env 注入矩阵（compose environment 全量）

### 5.1 Java 通用（四服务同）

| env | 值 | 说明 |
|---|---|---|
| NACOS_ADDR | host.docker.internal:8848 | 仓库 yml 占位符 `${NACOS_ADDR:127.0.0.1:8848}` 接收 |
| TZ | Asia/Shanghai | 日志时间戳可读（Jackson GMT+8 为代码级配置，不受影响） |

### 5.2 逐服务

**cloud-gateway**：`SPRING_DATA_REDIS_HOST=host.docker.internal`（在线会话检查；原为隐式默认 localhost）+ JAVA_OPTS（§8）

**cloud-sso**：`SPRING_DATA_REDIS_HOST=host.docker.internal`（会话 Redis；原为隐式默认）+ JAVA_OPTS

**cloud-system**：
| env | 值 |
|---|---|
| SPRING_DATASOURCE_URL | jdbc:mysql://host.docker.internal:3306/cloud_system?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true |
| SPRING_DATA_REDIS_HOST | host.docker.internal |
| ROCKETMQ_NAME_SERVER | host.docker.internal:9876 |
| XXL_JOB_ADMIN_ADDRESSES | http://host.docker.internal:18081 |
| XXL_JOB_EXECUTOR_LOGPATH | /app/logs/xxl-job（覆盖 Nacos 里的 Windows 路径 D:/applogs/xxl-job） |

**cloud-bpmn**：同 system，差异项——
| env | 值 |
|---|---|
| SPRING_DATASOURCE_URL | jdbc:mysql://host.docker.internal:3306/cloud_bpmn?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&nullCatalogMeansCurrent=true&allowPublicKeyRetrieval=true |

> URL 串的基底规则：以**该键当前生效值**（system 取仓库 yml、bpmn 取 Nacos cloud-bpmn.yaml）为基底仅替换 host——env 会整体覆盖原值，参数必须带全。

**cloud-base-ai**（环境变量全量 + `env_file: ./cloud-base-ai/.env` 承载凭据六键）：
| env | 值 |
|---|---|
| CLOUD_BASE_AI_DB__HOST | host.docker.internal |
| CLOUD_BASE_AI_MINIO__ENDPOINT | host.docker.internal:9100 |
| CLOUD_BASE_AI_MILVUS__URI | http://host.docker.internal:19530 |
| CLOUD_BASE_AI_REDIS__HOSTS | host.docker.internal（端口 7000 吃默认） |
| CLOUD_BASE_AI_MCP__BASE_URL | http://host.docker.internal:9202/mcp |
| CLOUD_BASE_AI_NACOS__SERVER_ADDR | host.docker.internal:8848 |
| CLOUD_BASE_AI_NACOS__IP | 172.28.0.6（D2 静态 IP，注册进 Nacos 供网关 lb 直连） |
| CLOUD_BASE_AI_EMBEDDING_LLM__BASE_URL | http://host.docker.internal:11434（D14，依赖宿主 Ollama 绑定调整） |
| CLOUD_BASE_AI_APP_PORT | 8010（显式声明，吃默认亦可） |

`.env.example` 扩容：新增上述容器化键的注释段（说明容器态由 compose 注入、本地直起吃默认）。

### 5.3 配置面不变项（零改动声明）

- Nacos 配置中心全部 dataId 零改写（含 `cloud.mcp.client-secret`、xxl executor address 现值——后者本就是容器适配形态）
- 仓库 application.yml 零改动（NACOS_ADDR 占位符本就支持 env）
- JWT 密钥、MCP client-secret、LLM/MinIO 凭据真值不进镜像不进 compose 文件（.env 保持 git 忽略 + 镜像 .dockerignore 排除）

## 6. 镜像构建

### 6.1 Java 服务（每服务目录一份 Dockerfile + docker-entrypoint.sh，context=服务目录）

Dockerfile（以 cloud-system 为例，四份同构）：
```dockerfile
FROM eclipse-temurin:17-jre
ENV TZ=Asia/Shanghai
WORKDIR /app
COPY target/*.jar app.jar
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
ENTRYPOINT ["/docker-entrypoint.sh"]
```

docker-entrypoint.sh（四份相同，bash——ubuntu 基底自带；dash 不支持 /dev/tcp）：
```bash
#!/bin/bash
# 起 JVM 前等 Nacos 与 MySQL 可达：config import 不可达时静默跳过不重试、
# Flowable 启动即查 schema，两者都要求外部依赖先就绪（冷启动确定性）
wait_tcp() {
  local host=$1 port=$2 name=$3 i=0
  until (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; do
    i=$((i+1))
    if [ "$i" -gt 120 ]; then echo "[entrypoint] ${name} (${host}:${port}) unreachable, exit"; exit 1; fi
    sleep 2
  done
}
wait_tcp host.docker.internal 8848 nacos
wait_tcp host.docker.internal 3306 mysql
exec java $JAVA_OPTS -jar app.jar
```

每服务目录 `.dockerignore`：`*` `!target/*.jar` `!docker-entrypoint.sh`（context 只传 jar 与脚本；target 下 classes/ 等全部排除）。

### 6.2 cloud-base-ai（context=cloud-base-ai/）

```dockerfile
FROM python:3.12-slim
ENV TZ=Asia/Shanghai PYTHONUNBUFFERED=1
WORKDIR /app
RUN pip install --no-cache-dir --index-url https://pypi.tuna.tsinghua.edu.cn/simple uv
COPY pyproject.toml uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src ./src
RUN uv sync --frozen --no-dev
COPY docker-entrypoint.sh /docker-entrypoint.sh
RUN chmod +x /docker-entrypoint.sh
ENTRYPOINT ["/docker-entrypoint.sh"]
```

entrypoint：探测 host.docker.internal:8848 可达后 `exec .venv/bin/uvicorn cloud_base_ai.main:app --host 0.0.0.0 --port 8010`（**容器内必须绑 0.0.0.0**，宿主直起时的 127.0.0.1 形态不进镜像）。

`.dockerignore`（cloud-base-ai/）：排除 `.env`（凭据绝不进镜像——硬纪律）、`.venv`（宿主 conda 基座产物不可复用）、`tests`、`examples`、`scripts`、`__pycache__`。

### 6.3 体积预算（D 盘余 16G）

| 项 | 估算 |
|---|---|
| eclipse-temurin:17-jre | ~260MB（一次） |
| 4 Java 服务镜像（共享基础层；增量=jar 77/84/146/146MB） | ~1.15GB |
| python:3.12-slim | ~130MB |
| AI 镜像（langchain/deepagents/milvus 全家桶，**构建后实测记档**） | 1.5–2.5GB |
| build cache | 0.5–1GB |
| **合计新增** | **~4–5GB** |

纪律：迭代后 `docker builder prune`；WSL2 vhdx 只增不自动收缩（删镜像不还 D 盘空间，收缩手册记移交备忘）。

## 7. 编排与冷启动自愈

`docker-compose.yml`（仓库根，`name: cloud-app`）关键段示意（完整文件见实施计划任务清单）：

```yaml
name: cloud-app
services:
  cloud-system:
    build: { context: ./cloud-base/cloud-system }
    container_name: cloud-system
    environment:
      NACOS_ADDR: host.docker.internal:8848
      TZ: Asia/Shanghai
      JAVA_OPTS: -Xms256m -Xmx512m -XX:MaxMetaspaceSize=256m
      SPRING_DATASOURCE_URL: jdbc:mysql://host.docker.internal:3306/cloud_system?...
      SPRING_DATA_REDIS_HOST: host.docker.internal
      ROCKETMQ_NAME_SERVER: host.docker.internal:9876
      XXL_JOB_ADMIN_ADDRESSES: http://host.docker.internal:18081
      XXL_JOB_EXECUTOR_LOGPATH: /app/logs/xxl-job
    ports: ["0.0.0.0:9202:9202", "0.0.0.0:19202:19202"]
    volumes: [xxljob-log-system:/app/logs/xxl-job]
    mem_limit: 1g
    restart: unless-stopped
    healthcheck:
      test: ["CMD-SHELL", "bash -c 'exec 3<>/dev/tcp/127.0.0.1/9202'"]
      interval: 15s
      timeout: 5s
      retries: 5
      start_period: 90s
    networks:
      cloud-app-net: { ipv4_address: 172.28.0.4 }
  # ... sso/bpmn/gateway/cloud-base-ai 同构，差异见 §4/§5/§8
  cloud-gateway:
    depends_on:
      cloud-sso: { condition: service_healthy }
      cloud-system: { condition: service_healthy }
      cloud-bpmn: { condition: service_healthy }
      cloud-base-ai: { condition: service_healthy }
networks:
  cloud-app-net:
    name: cloud-app-net
    ipam: { config: [{ subnet: 172.28.0.0/24 }] }
volumes:
  xxljob-log-system:
  xxljob-log-bpmn:
```

**冷启动时序**（Docker Desktop 重启后）：基础容器 restart 策略自动拉起（nacos 等，现状已如此）→ compose 五服务 unless-stopped 自动拉起（或手动 `docker compose up -d`）→ 各 entrypoint 等 8848/3306 就绪 → 服务注册/消费恢复 → gateway 最后过健康门槛。原生 MySQL 是 Windows 服务（自启类），无需编排。演练脚本与角色分工见 §10。

## 8. 内存预算（Docker VM 上限 9.7G；langfuse 停用后基础容器约 2.1G，余约 7.6G）

| 服务 | -Xms | -Xmx | MaxMetaspace | mem_limit | 预期 RSS |
|---|---|---|---|---|---|
| cloud-gateway | 128m | 256m | 256m | 512m | ~400m |
| cloud-sso | 128m | 256m | 256m | 512m | ~400m |
| cloud-system | 256m | 512m | 256m | 1g | ~800m |
| cloud-bpmn | 256m | 512m | 256m | 1g | ~850m（Flowable 较重） |
| cloud-base-ai | — | — | — | 1g | 400–600m |

合计 limit 4g / 预期 RSS ~2.9g，余量充足。**RSS 实测值回写记档**（验收项），偏差大则调 -Xmx 与 limit。

## 9. 构建与迭代回路（开发流）

常态回路（容器唯一运行态）：改码 → `mvn -pl <svc> -am clean package`（宿主绝对路径 mvn，1–2min）→ `docker compose build <svc>`（COPY jar 秒级）→ `docker compose up -d <svc>`（entrypoint 探测 + Spring 启动，约 1–2min）。单次约 3–4min。AI 侧：源码层重建秒级 + 重启。

- 日志：`docker compose logs -f <svc>` 替代控制台（Java 纯 stdout 天然契合；xxl job 日志落 named volume）
- 服务生命周期：原 netstat+taskkill 的 PID 陷阱流程被 `compose up/down` 取代（开发期服务自管流程同步简化）
- 远程调试：本期不做 JDWP 发布（需要时后补，作为已知扩展点记档）
- 应急直起（非官方）：宿主 `java -jar` / uvicorn 直起不传 env 即吃默认 127.0.0.1——CLAUDE.md 更新时声明

## 10. 测试与验收策略

| 层 | 内容 | 通过标准 |
|---|---|---|
| 构建 | 5 镜像 build；体积记档 | 全部成功；AI 镜像体积实测入档 |
| 配置生效 | actuator/env 或行为侧写确认 env 覆盖（Redis/RocketMQ/xxl/datasource） | 无「localhost 拒连」「Windows 路径」类错误 |
| 链路冒烟 | 网关 /system/demo/ping；Nacos 控台 5 实例（IP=172.28.0.x）；xxl 执行器在线 + 对账任务（admin 任务 id 7）恢复 tick；MCP 冒烟脚本（宿主→9202） | 全绿 |
| 容器间链路 | AI 容器→system 容器 MCP（D13 通路）；aichat e2e（走网关 lb 到 AI 容器） | 通过 |
| 回归 | e2e 全量聚合 + aichat 分轨（18080/5173 不变，cloud-e2e 零改动） | 只认 `[FAIL]` 计数口径（exit 0 假绿陷阱记档在案） |
| 资源 | docker stats RSS 采集 vs §8 预算 | 偏差回写调优 |
| 冷启动演练 | 基础容器 stop（主控/用户执行，agent 绝不动）+ `docker compose down` → Docker Desktop 重启（用户操作）→ `docker compose up -d` 一键拉起 → 全链路冒烟复跑 | 全链路自愈 |

前置条件：dataperm/ProTable 两轮已提交（串行约束）；milvus 修复完成（主控执行中，aichat/KB 验收依赖）；Ollama 宿主绑定调整（D14，KB/embedding 依赖，不阻断主链路）。

## 11. 风险与实现时验证清单

| # | 验证项 | 失败 fallback |
|---|---|---|
| R1 | XXL_JOB_ADMIN_ADDRESSES / XXL_JOB_EXECUTOR_LOGPATH 经 relaxed binding 覆盖 `@ConfigurationProperties("xxl.job")`（机制确定，日志确认一次） | 改用 SPRING_APPLICATION_JSON 注入 |
| R2 | `mem_limit` 在 docker compose（非 swarm）生效 | 换 `deploy.resources.limits.memory` |
| R3 | host.docker.internal 访问宿主 0.0.0.0 绑定端口（9202/19202/19203 三处同机制，xxl 执行器在线即证明） | 排查 Docker Desktop 版本行为 |
| R4 | AI 注册 172.28.0.6 后网关 lb 直连（aichat e2e 即端到端证明） | 换 compose service name 注册（nacos-sdk 收主机名需实测） |
| R5 | RocketMQ broker 通告地址 brokerIP1=172.30.80.1（宿主 WSL 适配器 IP，10909/10911/10912 已发布 0.0.0.0）容器内可达——审批流 e2e 验证 | 调 broker.conf 通告地址（宿主文件，主控面） |
| R6 | Ollama 容器内可达 | **已实测通过（2026-10-22，见 D14）**；若未来 Ollama 重装后回退 127.0.0.1 绑定：设 OLLAMA_HOST=0.0.0.0 或 KB 验收推迟 |
| R7 | uv sync 在 python:3.12-slim 内构建可行（清华 index 可达、uv_build 后端自举） | 改 `uv export` requirements.txt + pip 安装 |
| R8 | eclipse-temurin/python 基础镜像 daocloud 拉取可达 | 备选镜像源；最后手段宿主 JDK 打基础镜像 |
| R9 | Spring env 优先级高于 Nacos import（机制确定，actuator env 抽查一次） | SPRING_APPLICATION_JSON |

## 12. 移交备忘（实施计划同步收录）

1. **milvus 向量集合重灌**：数据卷已清空（etcd raft 损坏拍板处置），chunk 元数据在 MySQL 完好——走既有上传/重切流重灌，工作量另轮处理
2. **langfuse 重启路径**：`D:\source\langfuse\docker-compose.yml`（六容器项目，本轮验收不依赖；重启将占用约 2.9G 内存预算）
3. **磁盘纪律**：builder prune 常态化；vhdx 手动收缩手册（Docker Desktop GUI / diskpart）需要时执行
4. **milvus restart=no 策略修缮**：本次修复若未顺带调整，列入下次基础容器治理轮
5. **RSS 实测后内存参数回写**：偏差大时调 compose 内 -Xmx/mem_limit
6. **JDWP 远程调试口**：需要时按已知扩展点后补
