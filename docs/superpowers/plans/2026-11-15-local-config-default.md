# 实施计划：local 配置完整化与默认 local 启动（2026-11-15）

> 设计依据：`docs/superpowers/specs/2026-11-15-local-config-default-design.md`（机制、POC 实证、决策代号 D1-D7 均定义于该文档——本文引用时附带一句语义）。纯后端/脚本/文档轮，零 API 契约变化，不进 e2e 必选回归。

## 第一章 给 backend-agent 的任务清单

**执行顺序**：T1 → T2 → T3 → T4 → T5 → T6（T1/T2 可并行；T3 依赖 T2 的产物形态；T5 依赖 T1-T4 全部）。

**开工前读**：spec 全文（重点 §4 机制与 §9 POC 记档——所有机制已真机验证过，照实证形态实现，不另行发明）。

**红线**：
- nacos-local/ 与所有 application-local.yml 含密钥真值，gitignored 绝不提交；任何提交物（yml/脚本/文档）不得出现密钥真值。
- 零 git 提交（主控统一提交）。
- 生成文件勿手改——一切经备份脚本再生成。

### T1 六服务 application.yml：Nacos import 门控重构

把 `spring.config.import` 块从主文档挪到文件末尾新增的 `on-profile: "!local"` 门控文档——local 激活时跳过 import（离线声明的 config 面）。spec §4.1 有完整示意与注意点（`!local` 加引号；保持各服务原 import 顺序与注释；门控文档禁含 spring.profiles.active）。

文件清单（六处同构改动，四胞胎模板级同步 + aggregate + gateway）：
- `cloud-base/cloud-sso/src/main/resources/application.yml`
- `cloud-base/cloud-system/src/main/resources/application.yml`（import 顺序特殊：服务 yaml 在前、common 在后，带接线注释，原样迁移）
- `cloud-base/cloud-bpmn/src/main/resources/application.yml`
- `cloud-base/cloud-task/src/main/resources/application.yml`
- `cloud-base/cloud-aggregate/src/main/resources/application.yml`（import 顺序同 system 特殊，注释原样迁移）
- `cloud-base/cloud-gateway/src/main/resources/application.yml`

验收标准：
1. `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install` 全量绿（守护测试与单测无破坏）。
2. 抽查一个服务（建议 sso）双形态构建产物：jar 内 application.yml 含 `on-profile: "!local"` 文档且 import 在其中。
3. 六文件门控文档结构逐字同构（仅 import 条目与注释随服务差异）。

### T2 备份脚本扩展：离线段生成 + 网关模板 + 头注释改写

文件清单：
- `cloud-base/scripts/nacos/backup_nacos_config.py`

改动点：
1. **离线段常量**（spec §4.2 的三件套）：`spring.cloud.nacos.config.import-check.enabled=false` + `spring.cloud.nacos.discovery.enabled=false` + `spring.cloud.discovery.client.simple.instances` 七条静态实例表（五 Java :9201-9205 + cloud-base-rag :8011 + cloud-base-agent :8012，表驱动常量单点维护——spec 决策 D7）。追加写进每份生成的 application-local.yml 尾部（`---` 分隔新文档）。
2. **网关生成分支**：gateway 无 dataId，按内置模板生成 `cloud-base/cloud-gateway/src/main/resources/application-local.yml` = 头注释 + cloud-common.yaml 快照段 + 离线段（网关验签必需 cloud.jwt.secret——spec §4.3/POC P7 的教训，漏掉即带 token 请求全挂且形态为 200+code500 极易误诊）。
3. **头注释改写**：现行「健康环境勿带 local」反转——新语义：默认启动即带 local（见 dev-services.sh 与 CLAUDE.md）；local = 离线模式（不 import Nacos、不注册发现，lb 走静态表）；本文件由备份脚本生成，改 Nacos 后下次启动自动重生成；勿手改勿提交。
4. 网关分支与既有服务分支共用 current/ 快照读取逻辑，双租户取 default 副本（与现状一致）。

验收标准：
1. 跑 `C:/Users/jy-root/.local/bin/uv.exe run --project cloud-ai-common python cloud-base/scripts/nacos/backup_nacos_config.py` 后：7 份 application-local.yml 就位（5 服务含快照+离线段、aggregate 含 pool-size 快照+离线段、gateway 新建）；重复跑幂等。
2. 每份 local 面含七条静态实例表与两个开关；gateway local 面含 cloud-common 快照段。
3. stdout 仍只打键名/md5 不打内容（安全口径不回退）。

### T3 dev-services.sh：启动前自动快照 + 默认 local 参数

文件清单：
- `scripts/dev-services.sh`

改动点：
1. `cmd_start` 在起第一个 Java 服务前跑备份脚本（uv 绝对路径 + `--project cloud-ai-common`，与 find_uv 候选探测逻辑一致）；失败（如 MySQL 不可达）打 `[WARN]` 后继续用既有 local 面起服，不阻断——双重故障（MySQL+nacos 同挂且快照陈旧）属声明边界（spec §6）。
2. 五条 java 命令（SERVICES 三条 + GATEWAY_CMD）追加 `--spring.profiles.active=local`。
3. AI 两服务与前端命令**不动**（.env/vite 无 profile 概念）。
4. 头注释「前置假设」段改写：nacos 容器移出默认路径依赖（编辑时依赖——改配置才需要它运行）；快照经 MySQL 直读。

验收标准：
1. `./scripts/dev-services.sh start` 在 Java 前输出备份完成行（nacos/MySQL 健康时）。
2. 手动制造备份失败（如临时停 MySQL——注意 WAMP 服务陷阱，验后必须恢复）验证 WARN 降级不阻断。
3. `status` 子命令不受影响。

### T4 CLAUDE.md 口径改写（四处）

文件清单：
- `CLAUDE.md`

改动点：
1. **Commands 段**：六条 `java -jar` 启动命令统一带 `--spring.profiles.active=local`；补一行说明「默认即离线模式；不带 local = 传统路径（import Nacos + 注册发现）」。
2. **「Nacos 配置本地备份」段**：改写为默认启动路径语义——start 前自动快照、改 Nacos → 下次重启生效（脚本自动重生成 local 面）、备份失败 WARN 降级、local = 显式离线（不 import 不注册）、静态实例表接管 lb、nacos 容器为编辑时依赖（快照直读 MySQL）；「健康环境勿带 local」口径删除。
3. **sso Redis 隐式依赖记档**：sso 无显式 redis 配置，依赖 Boot 默认 127.0.0.1:6379（行为不变）。
4. **「端口」段**：local 模式语义补充——默认路径停 nacos 全栈可用（lb 走静态表），nacos 控制台无实例属预期。

验收标准：文档描述与 T1-T3 实际行为一致（含拍板四结论的无歧义表述）；不含任何密钥真值。

### T5 两幕演练验收（spec §7 全文为准）

幕一（停 nacos 全栈）：
1. `docker stop nacos` → `./scripts/dev-services.sh start`（全栈含 AI 与前端）。
2. 网关冒烟全过：带 token `GET /system/demo/ping` 200；`POST /sso/auth/login`（admin/admin123）200 返回双 token；AI 面经网关一端点（如 `/ai/rag/` 下任一管理端点，应用层响应即穿透，milvus 停机时业务错误码也算通）；`GET /aggregate/workbench` 整体 200 段式。
3. 五 Java 服务日志零 nacos 连接/重连 ERROR（静态日志适配 INFO 属可接受残留——POC P1 口径）。

幕二（nacos 健康默认路径 + 传统路径保留）：
1. `docker start nacos` → 脚本默认起 → 同套冒烟通过。
2. 离线声明验证：服务未注册进 nacos。
3. 同步链验证：Nacos 改 cloud-common 的 `access-token-ttl`（如 7200→7199，验后改回）→ restart → 登录响应 expiresIn 反映新值（快照自动重生成实证）。
4. 传统路径抽查：手动不带 local 起一个服务（建议 sso）→ import 正常、注册 nacos、网关经 nacos 发现路由通；验完停掉。

验收标准：两幕全绿，结果记入第六章移交备忘（含任何与预期的偏差）；演练后现场恢复（服务全停或按需保留、nacos 容器恢复运行、改过的 Nacos 键复原）。

### T6 收尾核对

1. `git status` 全量核对：除 T1-T4 的六个 yml、两个脚本、CLAUDE.md 与 docs 两文档外无其他改动；`cloud-base/nacos-local/`、各 application-local.yml 不出现在 status（gitignore 生效复核）。
2. mvn 全量 install 复跑绿；`cd cloud-base-ai` 三工程 pytest 不受本轮影响（零 Python 侧改动，抽查 common 守护绿即可——注意 pytest 须 cd 进工程目录跑）。
3. 计划第六章移交备忘按实况回填。

## 第二章 依赖与风险提示（给 backend-agent）

- **照 POC 实证形态实现**：spec §9 的 P1-P10 全部真机验证过——尤其 P2（import-check fail-fast 不解则服务起不来）与 P7（网关 local 面漏 common 快照的 200+code500 误诊形态）。实现偏离实证形态时先停下核对 spec。
- Windows 陷阱照 CLAUDE.md：停服 netstat 反查 PID + taskkill //F；mvn 绝对路径；uv 绝对路径；中文 JSON 别走 Git Bash curl。
- 停 nacos 演练期间 AI 服务日志会刷注册失败 ERROR——预期形态（P9），勿当故障修。
- 幕二第 3 步改 Nacos 键用 access-token-ttl 这类无害键，验完改回并重跑一次备份。

## 第三章 不在本轮范围（防蔓延）

配置中心替换、多环境 profile 体系、容器编排、AI 基座 .env 改造、数据源位置统一化、AI 注册日志静默、静态表健康检查、环境自检工具整合（均记档在 spec §8/§10）。

## 第四章 移交后续阶段的备忘（预置，T6 按实况回填）

1. AI 服务注册重试噪音静默选项——待 AI 侧轮次（spec §10.1）。
2. 静态实例表端口矩阵（备份脚本常量）与 CLAUDE.md 端口段的人工同步纪律（spec §10.2）。
3. 两幕演练如整合为自检脚本属后续工具轮（spec §10.3）。
4. **T5/T6 实况回填（2026-11-15，backend-agent）**：
   - **机制缺口修复（本轮最大偏差）**：spec §4.1「配置取 jar 内 application-local.yml」的加载假设与 D2/M4「免重打包、重启即生效」承诺矛盾——dev-services.sh 用预构建 jar，脚本再生成的 local 面进不了 jar，首次全栈演练五服务+网关全部 import-check fail-fast（P2 坑在组合层复现，POC 靠临时重打包掩盖了此缺口）。修法：Java 服务改从各服务 `src/main/resources` 目录启动——Spring 默认搜索位 `file:./` 命中再生成文件且优先级高于 jar 内副本（四层配置位中「jar 外 profile 文件」最高），改 Nacos 重启即生效。落点：dev-services.sh SERVICES 增第 4 列启动目录 + GATEWAY_DIR 同款，CLAUDE.md 启动命令同步改写。幕二同步链实证：7200→7199 重启即生效（登录 expiresIn="7199"）、还原 7200 复验。**遗留可选项**：jar 内仍打包构建时刻的陈旧 local 面（恒被 file:./ 压制、无害），后续可经 pom 资源排除 application-local.yml 使 jar 完全环境中性。
   - **dev-services.sh 补 task/aggregate 两行**：计划 T3 括号「SERVICES 三条 + GATEWAY_CMD」与 spec §7 幕一「五 Java local 激活」矛盾（脚本原本缺这两服务，工作台冒烟依赖 aggregate）——按 spec 口径补齐，Java 命令实为 5+1 条带 local。
   - 计划 T2 验收「7 份 application-local.yml」系计数笔误：实为 6 份（5 个有 dataId 服务 + 网关模板）。
   - 演练过程偏差：幕一首次脚本运行被系统内存压力保护中止于 Java 等待段（非命令失败，五 Java 已起），AI 两服务与网关按脚本同命令同目录手动补起后冒烟全过；冒烟首发 500 系请求体字段笔误（username→account），机制无涉。
   - 佐证留档：默认路径 nacos public 命名空间 Java 零注册（仅 cloud-base-rag/agent——Python 侧恒注册，预期形态）；传统路径抽查 sso+gateway 注册成功、`/sso/demo/ping` 经 nacos 发现路由 200、sso 日志 NacosConfigDataLoader 加载行在案；AI 侧注册噪音实测 rag 45 行 / agent 48 行（nacos 停机数分钟累计，P9 形态）。
5. 主控审查调整（2026-11-15）：T3 验收第 2 条原「临时停 MySQL 验证 WARN 降级」改为**临时副本脚本把备份命令指向坏端口（如 3307）验证同一分支**——停真 MySQL 会触发 xxl-job-admin 连接池死亡不自愈的记档坑（恢复需 docker restart，违反容器红线），效果等价且零基础设施扰动。
