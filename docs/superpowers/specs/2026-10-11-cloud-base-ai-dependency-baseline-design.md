# cloud-base-ai 依赖基座设计（2026-10-11）

> 轨道：纯新增 Python 工程依赖铺设（无 Java 侧改动、无前端）。A 级流程阶段二产物，阶段一需求分析与用户拍板结论见本文 §2。

## 1. 背景与目标

monorepo 首个 Python 工程 `cloud-base-ai`（FastAPI + uv 0.13.0 + src layout，Python 3.11.17，AI 业务服务落点）铺设依赖基座：AI 技术栈三组（langchain / deepagents / langchain-openai）+ 基础设施三件套（Nacos SDK / ORM 框架 / Redis 客户端）。目标 = 依赖入 pyproject 并经 uv.lock 冻结、静态导入冒烟全绿、对既有基础设施三连**只读**活体验证通过（Nacos 为尝试性，见 D2）、fastapi hello world 回归不破。

**契约记档：本轮零 API 变更、零表变更，不建 contracts 文件；无错误码与权限标识分配；无 DDL 与索引设计。**

## 2. 拍板结论（阶段一呈报后用户拍板，2026-10-11）

1. **ORM = SQLAlchemy 2.x + Alembic + aiomysql（运行时）+ PyMySQL（迁移）**，全盘采纳候选 A。
2. **范围 = 方案 A**：仅添加依赖，不做接入骨架（pydantic-settings / 连接生命周期 / Nacos 封装全部留后续立项）。
3. **Nacos 本轮仅定位读配置**；活体三连只读 = Redis PING + MySQL SELECT + Nacos 读既有公共配置；红线 = 不注册实例、不建库建表、零写入基础设施。
4. **版本策略**：uv add 默认下限约束、不手动钉上界、lock 冻结；langchain / langchain-openai 显式声明（不依赖传递可达）。

## 3. 最终依赖清单与版本事实（PyPI 实查 2026-10-11）

### 3.1 直接依赖（uv add 共 9 包，两组）

| 组 | 包 | 当前最新 | requires-python | 关键约束 |
|---|---|---|---|---|
| AI 栈 | langchain | 1.4.4（1.x 稳定线） | >=3.10 | langchain-core>=1.6.9,<2；langgraph>=1.2.11,<1.3；pydantic>=2.7.4,<3 |
| AI 栈 | deepagents | 0.7.23 | **>=3.11** | **硬依赖** langchain>=1.4.3,<2 + langchain-anthropic>=1.7.5 + langchain-google-genai>=4.4.0 |
| AI 栈 | langchain-openai | 1.7.0 | >=3.10 | langchain-core>=1.6.8,<2；openai>=2.45,<4 |
| 基础设施 | nacos-sdk-python | 3.2.0 | >=3.10 | aiohttp>=3.10.11；**grpcio>=1.66.1**；pydantic>=2.10.4 |
| 基础设施 | sqlalchemy[asyncio] | 2.1.4 | **>=3.11** | greenlet 自 2.1 起移入 [asyncio] extra（非默认连带）——async 通路运行时硬需，以 extra 形态声明（主控修正 2026-10-11） |
| 基础设施 | alembic | 1.20.0 | >=3.10 | 迁移工具，运行时同步走 PyMySQL |
| 基础设施 | aiomysql | 0.3.2 | >=3.9 | 纯 Python（依赖 PyMySQL），零编译 |
| 基础设施 | pymysql | 1.2.3 | >=3.9 | 同步驱动，Alembic 迁移用 |
| 基础设施 | redis | 8.1.0 | >=3.10 | 核心零强制依赖，asyncio 内置（redis.asyncio） |

requires-python 最严者为 deepagents 与 sqlalchemy（均 >=3.11），本工程 3.11.17 满足；既有 `fastapi[standard]>=0.143.0` 不动。

> **主控实施期修正记档（2026-10-11）**：初版清单为裸 `sqlalchemy`，实测 SQLAlchemy 2.1.4 元数据将 greenlet 改为 `extra == "asyncio"` 条件依赖（uv.lock 零 greenlet），async 引擎 import 即 `ModuleNotFoundError: greenlet`（实施回报步骤 7 FAIL 证据）。修正为 `sqlalchemy[asyncio]`——SQLAlchemy 官方报错信息推荐形态，与 D1「async 通路」决策意图一致，属事实性修正非设计变更。

### 3.2 连带依赖记档（验收时在版本报告与 uv tree 输出中如实呈现）

- **deepagents → langchain-anthropic + langchain-google-genai（硬依赖，不可拆）**：用户未点名但必装，属依赖树膨胀记档项，无害。
- **nacos-sdk-python → grpcio + aiohttp**：即使只走配置读取（HTTP 通路）grpcio 也会装入；两者 Windows py3.11 均有官方 wheel，无编译风险。aiohttp 与 fastapi[standard] 的 httpx 并存无冲突。
- **nacos-sdk-python 顶层包名为 `v2`**（import 形态 `from v2.nacos import ...`，wheel 实查确认）：包设计瑕疵，当前依赖树无其他顶层 `v2` 包故无冲突；记档，将来引入其他含顶层 v2 的包时需评估。
- **pydantic 收敛无冲突**：fastapi>=2.9.0、langchain>=2.7.4,<3、nacos-sdk>=2.10.4 的约束交集非空，uv 解析为单一 2.x 最新版（验收口径见 §7）。

## 4. D1：ORM 决策记录

**采纳**：SQLAlchemy 2.x（async engine + AsyncSession 运行时）+ aiomysql（运行时驱动）+ PyMySQL（Alembic 迁移同步驱动）+ Alembic（迁移工具）。

**理由（权重序）**：
1. **团队文化对齐**：Java 基座是 MyBatis 手写 XML SQL + DTO/VO 分离文化；SQLAlchemy 允许 ORM 之外以 Core/text() 写显式 SQL，表模型与 Pydantic API 模型天然分离手动转换——与「VO 隔离、转换用原生 setter 逐字段」的项目精神同构。SQLModel 的「表模型即 API 模型」单类双职责与该文化相反；Tortoise 的 Django 风格隐式文化与 MyBatis 显式文化差异最大。
2. **生态与文档成熟度**：SQLAlchemy 是 Python ORM 事实标准，FastAPI 官方文档 SQL 模式即此组合；SQLModel 0.0.x 未到 1.0、更新节奏慢；Tortoise 复杂查询（JOIN/窗口/CTE）能力显著弱。
3. **异步契合**：FastAPI + LLM I/O 密集负载 → async 通路（create_async_engine + AsyncSession）；Alembic 迁移脚本走同步 URL（pymysql）执行更简单，双驱动分工是成熟组合。

**驱动取舍**：aiomysql（纯 Python，基于 PyMySQL，Windows 零编译风险）优于 asyncmy（C 扩展，Windows 有编译/wheel 风险，性能非当前瓶颈——LLM 负载瓶颈在模型 I/O 不在 DB 驱动）。

**风险与缓解（如实示）**：SQLAlchemy 2.1 官方主测矩阵为 MySQL 8.0+，本机 **MySQL 5.7.24 属方言兼容但非主测**——运行时 SQL 无虞（PyMySQL/aiomysql 明确支持 MySQL 5.5+，5.7 默认 mysql_native_password 无认证协议问题），风险集中在未来 DDL 生成细节（utf8mb4 索引 767 字节限制等）。**缓解**：首次建表轮 DDL 全量人工审查 + EXPLAIN 验证（移交备忘 #3），本轮零 DDL 无暴露面。

## 5. D2：Nacos SDK 决策记录（与阶段一研究结论有出入，明示采纳口径）

**事实**（阶段二实查修正）：官方 nacos-sdk-python 已到 **3.2.0**（主控研究的 3.0.0 已过时）；其 README 声明 **"Supported Nacos version over 3.x"**，而本机服务端实测 **2.4.3**（standalone，auth_enabled=false，curl /nacos/v1/console/server/state 确认）。3.x SDK 主通路为 gRPC（默认 port_offset 1000 → 服务端 9848，容器是否映射该端口**未证实**，docker ps 未取得映射信息）。

**与拍板前提的出入**：阶段一呈报口径「对 2.x 服务端经 HTTP 通路可用」沿自主控对 3.0.0 的研究；3.2.0 的 gRPC 主通路 + 3.x 服务端声明使该前提不再可靠。

**采纳口径**：仍装 nacos-sdk-python 3.2.0（官方现行主线、pydantic 2 契约、未来服务端升级 3.x 即无缝、无更优替代——社区 gRPC 分支不成熟、老 0.1.x 为 pydantic 1 时代残骸不建议）；**活体验证降级为尝试性（attempt）**：
- SDK `get_config` 成功 → 验证通过，理想路径；
- SDK 失败 → 用 curl v1 OpenAPI 兜底诊断（`GET /nacos/v1/cs/configs?dataId=cloud-common.yaml&group=DEFAULT_GROUP`，2.4.3 无鉴权可用），以区分失败归因：curl 通 = 基础设施与配置可达，属「SDK 3.x 对 2.x 服务端不兼容」的已知版本缺口（如实记档，移交处置：服务端升 3.x 或 HTTP-only 替代评估）；curl 不通 = 基础设施异常，如实上报。两种 SDK 失败情形均**不算依赖添加任务失败**，不为此擅自降版本或换包。

**读取目标**：`data_id=cloud-common.yaml, group=DEFAULT_GROUP, namespace=空(public)`——与 Java 侧 `spring.config.import: optional:nacos:cloud-common.yaml` 同一配置源，只读。注：SDK get_config 成功后会在 `~/nacos/cache` 落本地快照，属客户端本机缓存，不触碰「零写入基础设施」红线，记档知悉。

## 6. D3：版本与兼容策略

- 六组共 9 包分两次 `uv add`（AI 栈 / 基础设施），失败隔离；uv add 默认写入下限约束（`>=解析版本`），不手动钉上界；**uv.lock 为版本冻结的单一真相源**。
- langchain / langchain-openai 显式声明（虽经 deepagents 传递可达，直接依赖显式化是既定拍板）。
- 依赖拓扑（文字版架构图）：

```
cloud-base-ai (pyproject)
├── fastapi[standard] ── pydantic 2.x ─┐
├── langchain 1.x ──┐                  │ pydantic 单点收敛（uv 解析单一 2.x）
│                   ├─ langchain-core 1.x <2.0
├── langchain-openai 1.x ── openai 2.x
├── deepagents 0.7.x ── langgraph 1.2.x
│                    └─ langchain-anthropic / langchain-google-genai（连带，记档）
├── nacos-sdk-python 3.2.0 ── aiohttp + grpcio（连带，记档）；import 名 v2.nacos
├── sqlalchemy[asyncio] 2.1.x ── greenlet（[asyncio] extra，2.1 起非默认连带）
│   ├── aiomysql 0.3.x（运行时）── PyMySQL
│   └── pymysql 1.2.x（Alembic 迁移）
├── alembic 1.20.x
└── redis 8.1.x（redis.asyncio 内置）
```

## 7. 验证标准（测试策略——依赖类任务以验证步骤代替单测）

1. **静态（必做全绿）**：`uv lock` 解析成功；9 直接包 + fastapi 的 import 冒烟零异常（含非常规 import 名 `v2.nacos`）；importlib.metadata 版本报告输出全部直接+连带包版本；`uv tree` 收敛检查——pydantic 全树单一版本。
2. **hello world 回归**：TestClient 同进程 200 校验 + 真实起服（`--port 8010`，8000 被占）curl `/` 返回 `{"Hello":"World"}`。
3. **三连只读活体**：Redis PING=True；MySQL `SELECT VERSION()` 经 aiomysql 异步引擎返回 5.7.24-log；Nacos 按 D2 尝试性口径（SDK 成功或 curl 兜底归因记档）。
4. **红线复核**：全程未向 Nacos 注册实例、未建库建表、未向任何基础设施写数据（SELECT/PING/get_config/GET 均只读）。

## 8. 明确不做（范围外）

接入骨架（pydantic-settings / DB/Redis 连接生命周期 / Nacos 封装模块）、LLM 供应商选型与 API key 管理口径、agent 应用骨架与目录结构设计、服务注册与网关 lb:// 路由对接、任何 DDL/表/索引、Java 侧与前端任何改动、asyncmy 与社区 gRPC SDK、Nacos 服务端升级（仅移交评估）。

## 9. 移交事项

见实施计划末尾「移交后续阶段的备忘」章节（与本 spec §5/§8 对应展开）。
