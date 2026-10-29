# 后端全服务容器化实施计划（2026-10-22）

> 结论先行：纯后端 + 基础设施轮，无前端章节、无契约文档（本轮零 API 面）。设计依据：`docs/superpowers/specs/2026-10-22-containerization-design.md`（下称「设计文档」，决策编号 D1–D14 均在其 §3 定义）。共 7 个任务：基础镜像 → Java/AI 镜像构建面 → compose 编排 → 拉起与链路冒烟 → 回归验收 → 文档更新。

## 0. 前置条件与全局纪律

**前置条件**（不满足不开工）：
1. dataperm 与 ProTable 两轮均已提交（两轮都重启后端服务，**串行约束**：本计划实施期间不得与其它重启服务的轮次并行）
2. milvus 修复完成且可连（主控执行中；aichat/KB 验收依赖）
3. ~~Ollama 宿主绑定调整~~ **已实测解除（2026-10-22 主控核查）**：本机 Ollama 实绑 0.0.0.0:11434，nacos 容器内 curl host.docker.internal:11434 返回正常——容器可达成立，无需设 OLLAMA_HOST（设计 D14 已同步更新；若未来 Ollama 重装后回退 127.0.0.1 绑定再按原方案处理）

**全局纪律**：
- 绝不 restart/rm/stop 任何既有容器（基础容器独立管理）；docker 命令新增仅限 build/compose up/down/logs/stats/images/inspect
- mvn 用绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；Git Bash 中文 JSON 坑照旧（冒烟入参用 ASCII）
- 凭据真值（LLM/MinIO key 等）绝不进镜像、不进 compose 文件、不进日志回报
- e2e 回归判读只认 `[FAIL]` 计数（exit 0 是假绿）

## 1. 后端章（唯一章）

### T1 基础镜像准备

**文件清单**：无新增文件（操作型任务）
**内容**：经 docker.m.daocloud.io 拉取并回打 tag（设计 D11）：
```bash
docker pull docker.m.daocloud.io/library/eclipse-temurin:17-jre
docker tag  docker.m.daocloud.io/library/eclipse-temurin:17-jre eclipse-temurin:17-jre
docker pull docker.m.daocloud.io/library/python:3.12-slim
docker tag  docker.m.daocloud.io/library/python:3.12-slim python:3.12-slim
```
**验收标准**：`docker images` 列出 `eclipse-temurin:17-jre`（~260MB）与 `python:3.12-slim`（~130MB）两个本地 tag。失败走设计 R8 fallback（备选镜像源→最后手段宿主 JDK 打基础镜像），fallback 选择记入回报。

### T2 Java 服务 Dockerfile（×4 同构）

**文件清单**（每服务目录两文件，四份内容相同；内容全文见设计文档 §6.1）：
- `cloud-base/cloud-gateway/Dockerfile` + `cloud-base/cloud-gateway/docker-entrypoint.sh` + `cloud-base/cloud-gateway/.dockerignore`
- `cloud-base/cloud-sso/` 同三件
- `cloud-base/cloud-system/` 同三件
- `cloud-base/cloud-bpmn/` 同三件

**内容要点**：Dockerfile=FROM eclipse-temurin:17-jre + COPY target/*.jar + ENTRYPOINT entrypoint；entrypoint=bash /dev/tcp 探测 host.docker.internal 的 8848(nacos)+3306(mysql) 各至多 120×2s 后 `exec java $JAVA_OPTS -jar app.jar`（设计 D5）；.dockerignore=`*` + `!target/*.jar` + `!docker-entrypoint.sh`（context 只传 jar 与脚本）。
**注意**：先跑 `mvn -f cloud-base/pom.xml clean install` 保证四 jar 在位；entrypoint 必须 LF 行尾（bash 解释 CRLF 会报错）。
**验收标准**：任选一服务（建议 system）`docker build` 成功；镜像内 `docker run --rm <img> cat /docker-entrypoint.sh` 可读（或 build 日志无 CRLF 异常）；镜像体积 ≈ 基础镜像 + jar 体积。

### T3 AI 基座 Dockerfile

**文件清单**：
- `cloud-base-ai/Dockerfile`（全文见设计文档 §6.2：python:3.12-slim + pip 装 uv（清华 index）+ 两段 uv sync 分层 + entrypoint）
- `cloud-base-ai/docker-entrypoint.sh`（探测 8848 后 `exec .venv/bin/uvicorn cloud_base_ai.main:app --host 0.0.0.0 --port 8010`——**容器内必须 0.0.0.0**）
- `cloud-base-ai/.dockerignore`（排除 `.env`/`.venv`/`tests`/`examples`/`scripts`/`__pycache__`——`.env` 排除是硬纪律）
- `cloud-base-ai/.env.example`（扩容注释段：容器化 env 由 compose 注入、本地直起吃默认；不改既有键语义）

**验收标准**：`docker build` 成功（依赖层构建耗时与总体积记档，预计 1.5–2.5G——设计 §6.3 预算回填）；二次 build 仅改 src 时依赖层缓存命中（秒级完成依赖段）；镜像内不存在 `.env`（`docker run --rm <img> ls -la /app | grep -c "^-.*\.env"` 为 0 或直接 ls 目检）；uv sync 若失败走设计 R7 fallback（uv export + pip）并回报。

### T4 compose 编排文件

**文件清单**：
- `docker-compose.yml`（仓库根；`name: cloud-app`）

**内容要点**（完整 env 矩阵与端口表以设计文档 §4/§5/§7 为准，此处列差异骨架）：
- 五服务：cloud-gateway(.2)/cloud-sso(.3)/cloud-system(.4)/cloud-bpmn(.5)/cloud-base-ai(.6)，网络 `cloud-app-net` 172.28.0.0/24 静态 IPAM（设计 D2）
- 端口发布按设计 §4 硬性清单（9202/19202/19203 绑 0.0.0.0，18080/8010/9201/9203 绑 127.0.0.1）
- env 矩阵按设计 §5 逐服务全量（JAVA_OPTS 按设计 §8 定值）；cloud-base-ai 另挂 `env_file: ./cloud-base-ai/.env`
- healthcheck：Java=bash /dev/tcp 探测本服务端口、AI=python urllib 打 /health（start_period 90s，设计 D9）；gateway depends_on 其余四服务 service_healthy
- `mem_limit` 按设计 §8；`restart: unless-stopped`；xxl 日程卷 `xxljob-log-system`/`xxljob-log-bpmn` 挂 /app/logs/xxl-job

**验收标准**：`docker compose config` 渲染无错且静态 IP/端口/env 与设计清单逐项一致（人工核对一遍渲染产物）。

### T5 构建与全栈拉起 + 配置生效与链路冒烟

**文件清单**：无新增（验证型任务）
**内容与顺序**：
1. `docker compose build`（五镜像）→ `docker compose up -d`
2. `docker compose ps` 等五服务 healthy（entrypoint 探测 + Spring 启动，首拉起给足 3–5min）
3. 配置生效验证（设计 §10「配置生效」层）：日志无 localhost 拒连 / Windows 路径错误；actuator env 或行为侧写抽查 R1（xxl 键 env 覆盖）与 R9（env 优先级高于 Nacos import）
4. 链路冒烟：`curl http://localhost:18080/system/demo/ping`；Nacos 控台五实例 IP=172.28.0.x；xxl-admin 控台执行器 cloud-system/cloud-bpmn 在线 + 对账任务（admin 任务 id 7）恢复 tick（R3 由「执行器在线」一次性证明）；MCP 冒烟脚本（宿主→127.0.0.1:9202）：`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python cloud-base-ai/scripts/smoke/smoke_mcp_system.py`
5. 容器间链路：aichat e2e 单轨先跑（AI 容器注册 lb 路由 + AI→system MCP，R4 端到端证明）

**验收标准**：五服务 healthy；上列冒烟全绿；若 R5（RocketMQ broker 容器内可达）在 aichat 外未见异常，以 T6 全量 e2e 的审批流为准裁决。

### T6 回归验收与资源记档

**文件清单**：无新增；验收数据记入回报（不落报告文件）
**内容**：
1. `cd cloud-e2e && npm run e2e`（全量聚合，含 bpmn 审批流——R5 裁定点）+ `npm run e2e:aichat`（R4/R6）
2. `docker stats --no-stream` 采集五容器 RSS vs 设计 §8 预算表（偏差回写建议）
3. `docker images` 体积记档（AI 镜像实测回填设计 §6.3）
4. **冷启动演练**（设计 §10 末行）：`docker compose down` → 基础容器 stop（**主控/用户执行，backend-agent 绝不动**）→ Docker Desktop 重启（用户操作）→ 基础容器随 restart 策略/手动拉起（主控/用户）→ `docker compose up -d` 一键拉起 → 复跑 T5 步骤 4 冒烟全绿

**验收标准**：e2e `[FAIL]` 计数为 0；RSS/体积记档完成；冷启动演练全链路自愈。R6（Ollama）未就绪时 KB 向量相关验收项允许挂起并计入移交备忘，其余不得缺。

### T7 CLAUDE.md 更新

**文件清单**：
- `CLAUDE.md`（Commands 段 / Architecture 端口段 / Windows 陷阱段）

**内容要点**：
- Commands：`java -jar`/uvicorn 直启行退役为「应急直起通道（非官方，不传 env 吃默认 127.0.0.1）」；新增常态命令：`docker compose up -d --build <svc>`（单服务迭代）、`docker compose logs -f <svc>`、`docker compose down`；构建前置 `mvn -f cloud-base/pom.xml clean install`
- 端口段：补 compose 项目 `cloud-app`、cloud-app-net 172.28.0.0/24 静态 IP 表（.2–.6）、端口绑定口径（9202/19202/19203 绑 0.0.0.0 的原因一句话）
- 陷阱段：停服口径从 netstat+taskkill 改 compose down（历史 PID 陷阱记录保留并标注已被容器化取代）；新增 vhdx 只增不缩提醒
- 保持既有行文风格（每条陷阱一句话因果）

**验收标准**：三段更新完成；文档自洽（无常态直起残留表述）；「应急直起」与设计 D12 口径一致。

## 2. 任务依赖与排期

```
T1 ──► T2 ─┐
T1 ──► T3 ─┼─► T4 ──► T5 ──► T6 ──► T7
           ┘        （T7 可与 T6 并行收尾）
```
全链串行约束（前置条件 1）；T6 冷启动演练涉及用户/主控操作，节奏由主控协调。

## 3. 移交后续阶段的备忘

1. **milvus 向量集合重灌**：数据卷已清空（etcd raft 损坏拍板处置），chunk 元数据在 MySQL 完好——走既有上传/重切流重灌（依赖 R6 Ollama 就绪），另轮处理
2. **langfuse 重启路径**：`D:\source\langfuse\docker-compose.yml`（六容器项目；重启将占用约 2.9G 内存预算，见设计 §8）
3. **磁盘纪律**：迭代后 `docker builder prune`；vhdx 手动收缩（Docker Desktop GUI / diskpart）需要时执行——删镜像不自动还 D 盘空间
4. **milvus restart=no 策略修缮**：若本次修复未顺带调整，列入下次基础容器治理轮
5. **RSS 实测后内存参数回写**：T6 采集数据偏差大时调 compose 内 -Xmx/mem_limit
6. **JDWP 远程调试口**：需要时按设计 §9 扩展点后补

## 4. 给 backend-agent 的任务清单（主控整段贴发用）

> 角色：按 `docs/superpowers/specs/2026-10-22-containerization-design.md`（设计文档）与 `docs/superpowers/plans/2026-10-22-containerization.md`（本计划）实施后端全服务容器化。T1–T7 顺序执行，每任务验收标准见计划对应章节。

**纪律（高于一切）**：
- 绝不 restart/rm/stop 任何既有容器（基础容器独立管理，刚从事故恢复）；新增 docker 操作仅限 build / compose up|down|logs / stats / images / inspect
- 零 git 提交（主控统一提交）；凭据真值绝不进镜像、compose 文件、日志或回报
- mvn 绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；Git Bash 中文 JSON 用 ASCII；e2e 判读只认 `[FAIL]` 计数
- 前置确认：dataperm/ProTable 已提交、milvus 可连；不满足即回报主控待命

**T1 基础镜像**：按计划 T1 命令拉取回打 tag `eclipse-temurin:17-jre`、`python:3.12-slim`（daocloud 路径带 `library/` 前缀）；验收=`docker images` 两 tag 在位。
**T2 Java Dockerfile×4**：每服务目录新增 Dockerfile / docker-entrypoint.sh / .dockerignore（全文照设计文档 §6.1，四份同构）；先 `mvn -f cloud-base/pom.xml clean install` 出四 jar；entrypoint 用 LF 行尾；验收=任一服务 build 成功。
**T3 AI Dockerfile**：cloud-base-ai/ 新增 Dockerfile / docker-entrypoint.sh / .dockerignore + .env.example 扩容注释（照设计 §6.2；`.dockerignore` 必须排除 `.env`）；uvicorn 绑 0.0.0.0；验收=build 成功、二次 build 依赖层缓存命中、镜像内无 .env、体积记档。
**T4 compose**：仓库根 `docker-compose.yml`（name: cloud-app；静态 IP 172.28.0.2–.6；env 矩阵逐项照设计 §5；端口照设计 §4——9202/19202/19203 绑 0.0.0.0、其余 127.0.0.1；healthcheck/mem_limit/restart/卷照设计 §7/§8）；验收=`docker compose config` 渲染无错且与设计清单逐项核对一致。
**T5 拉起冒烟**：build 全部 → up -d → 五服务 healthy → 按计划 T5 步骤 3–5 逐项冒烟（ping/Nacos 五实例/xxl 执行器在线+id7 tick/MCP 冒烟脚本/aichat 单轨）；R1/R3/R9 验证点结论记回报。
**T6 回归验收**：全量 e2e + aichat；docker stats RSS 与镜像体积记档；冷启动演练（compose down 由你执行，基础容器 stop 与 Docker Desktop 重启由主控/用户执行——到该步先回报主控协调）；验收口径见计划 T6。
**T7 CLAUDE.md**：按计划 T7 三段更新（Commands/端口段/陷阱段）。
**完成回报**：每任务验收结论 + R1–R9 验证结果 + RSS/体积数据 + 遇到的 fallback 决策；移交备忘触发项（R6 未就绪等）显式列出。
