# cloud-base-ai RAG 包工程目录骨架设计（2026-10-12）

> 轨道：纯后端（Python 单端，无 Java 侧与前端改动）。A 级流程阶段二产物；阶段一需求分析与用户三项拍板见本文 §2。本轮**零编码**：只落 RAG 域包目录骨架（`__init__.py` + 一行 docstring + README 段落）。

## 1. 背景与目标

cloud-base-ai（FastAPI / uv src layout / :8010，R11 后已具 Nacos 注册 + 网关 /ai/** 路由 + R<T> 基建）引入**首个业务域 RAG**（检索增强生成，LangChain 1.x 栈）。用户要求本轮只把包工程目录创好、不做其他编码——目标 = RAG 域子包骨架落位，目录树即该域的结构文档；既有 19 pytest 与起服链路零回归。

**契约预判记档（本轮零面）**：零 API、零端点、零表、零行为变更——**不建 contracts 文件、无错误码与权限标识分配、无 DDL 与索引设计**（沿依赖基座轮先例口径）。

## 2. 拍板结论（阶段一呈报后用户拍板，2026-10-12）

1. **目录风格 = 候选 A 数据流阶段平铺**：`cloud-base-ai/src/cloud_base_ai/rag/` 单层子包，模块按 RAG 管线阶段命名；阶段一 preview 树认可为候选，精确文件清单由架构定稿（本文 §6 即权威）。深度硬顶 2 层（`rag/` 下不再有子包）。
2. **供应商预留 = 模块级占位**：`vectorstore.py` / `embedding.py` 以单文件占位，不建供应商子包树；config 不预置任何供应商键。
3. **编码边界 = `__init__.py` + docstring + README**：每个文件仅包标记 + 模块顶部一行职责 docstring；README 增一段 rag/ 目录说明。文件内**禁止 import / 函数 / 类 / 常量**；阶段模块文件具体形态（一行 docstring 空文件 vs 只建目录+`__init__`）由架构定稿（D3：取前者）。验收口径 = 「uv build 能带走 + 目录树即文档」。

规范选择沿用阶段一裁定：**Python 最佳落地为主，Java 规范取精神不取形态**（不落 controller/service/mapper 目录形态；可映射精神 = 分层依赖单向 / DTO-VO 隔离 / 配置对象封装，R11 spec §5 九条已定版直接继承）。

## 3. 架构（文字版目录树）

```
cloud-base-ai/src/cloud_base_ai/
├── __init__.py                  # 既有（main 入口锚点，零触碰）
├── config.py                    # 既有基座：Settings（本轮零新键）
├── response.py                  # 既有基座：R<T>（rag 域未来复用，零复制）
├── exceptions.py                # 既有基座：BusinessException（同上）
├── exception_handler.py         # 既有基座：全局三 handler（同上）
├── nacos_registration.py        # 既有基座：注册生命周期（零触碰）
├── main.py                      # 既有：lifespan + /demo/ping + /health（零触碰，不 import rag）
└── rag/                         # ★ 本轮新增：RAG 业务域子包（深度硬顶 2 层）
    ├── __init__.py              # 包标记 + 域级一行 docstring
    ├── router.py                # 横切：API 入口（未来 APIRouter 落点）
    ├── service.py               # 横切：业务编排（router → service → 组件）
    ├── schemas.py               # 横切：pydantic DTO/VO（规范 §5-2 落点）
    ├── ingestion.py             # 管线阶段 1/6：文档摄入 load
    ├── chunking.py              # 管线阶段 2/6：文本分块 split
    ├── embedding.py             # 管线阶段 3/6：向量化 embed（供应商预留位）
    ├── vectorstore.py           # 管线阶段 4/6：向量存储 store（供应商预留位）
    ├── retrieval.py             # 管线阶段 5/6：检索 retrieve
    └── generation.py            # 管线阶段 6/6：生成 generate
```

**结构数据流（未来实现轮的依赖方向声明，本轮无运行时数据流）**：
- 查询链：`router → service → retrieval / generation`（生成消费检索结果）。
- 索引链：`ingestion → chunking → embedding → vectorstore`（离线/半在线建库管线）。
- 依赖单向：横切层（router）不反向被管线模块 import；schemas 被各层引用、自身不 import 业务模块。

## 4. 决策记录

### D1 布局 = 域触发分包（拍板 1；§5 第 1 条补充细则，细化非翻案）

RAG 是 `cloud_base_ai` **首个业务域**——R11 spec §5 第 1 条「业务复杂后再分包，不预分层」的触发条件本轮**首次被满足**：域边界是正当的分包边界，条款禁止的是**域内预分层**（controller/service 壳或组件分类壳），不是域级聚合。裁定：分包一次到位建 `rag/`，包内**仍平铺**，深度硬顶 2 层（`cloud_base_ai/rag/`），本条作为 §5 第 1 条的补充细则记档（见 §5）。

官方形态佐证（阶段一查证）：LangGraph 官方模板 `new-langgraph-project-python` 即 `src/` 下按职责平铺（agent/tools/configuration，不预分层）；社区主流 RAG 工程按数据流阶段命名模块（ingest/retrieval/embedding）。Java「按域聚合包」精神同构，Java 目录形态（controller/service/mapper）明确不采用。

### D2 文件清单定稿 = 9 模块 + `__init__`（拍板 1 授权）

相对阶段一 preview 树两处微调，均在定稿权内：

1. **增 `router.py`**：未来 RAG 端点的 APIRouter 落点——首个业务端点轮（R11 移交 #4）到来时路由挂载位置不再引发目录讨论；同时使分层依赖方向声明完整（router → service → 组件 → schemas）。
2. **`ingest` → `ingestion`**：与其余模块名词性统一（ingestion / chunking / embedding / retrieval / generation 全为名词形态；LangChain 官方 Indexing/Ingestion 术语同形）。

清单存在性判据：每个文件锚定**一个管线阶段或一个横切职责**，全部是未来必然有代码的落点，无「将来可能有」的投机文件；9 文件对 6 阶段 + 3 横切，粒度即止（防过度设计的另一种形态：空目录树太深——本轮深度锁 2 层、宽度锁 10 文件）。

### D3 模块文件形态 = 一行 docstring 空文件（拍板 3 二选一定稿）

不取「只建目录 + `__init__`」：阶段文件的存在本身是 RAG 管线的结构声明（「目录树即文档」的本体），且 docstring 可承载关键依赖事实提示（如 chunking 标注 text-splitters 未装、vectorstore 标注基础设施向量能力缺口），对后续实现轮是零成本的上下文。文件内禁止 import / 函数 / 类 / 常量（拍板 3 红线）。

### D4 供应商预留 = 模块级单文件（拍板 2）

`embedding.py` / `vectorstore.py` 各一个文件位。抽象缝已天然存在：`langchain_core.Embeddings / VectorStore`（本机已装，import 实查通）。不建供应商子包树；config 零新键。基础设施事实链（预留的必要性依据）：**MySQL 5.7.24 无向量索引能力、Redis 6.1 非 Redis Stack 无向量检索模块**——现存基础设施无法承担向量存储，未来实现轮必引专用包（chroma/faiss/milvus 或 MySQL BLOB + 应用层算距离的降级形态），届时在此二文件内封装、经 langchain_core 基类抽象。

### D5 依赖零变更（事实记档）

本机 venv 实查（2026-10-12）：`langchain 1.4.4` / `langchain-core 1.6.9` / `langchain-openai 1.7.0` / `langgraph 1.2.14` 已装；**`langchain-text-splitters`、`langchain-community`（loaders + FAISS）、任何 vectorstore 实现包（chroma 等）、`langchain-classic` 均未装**——`RecursiveCharacterTextSplitter` 当前 import 不可达（`langchain_text_splitters` 与 `langchain.text_splitter` 双路径 ModuleNotFoundError 实证）。推论：RAG 功能实现轮**必然新增 1-3 个依赖包**，本轮明确 OUT；目录骨架是结构占位，不是可用链路（防「目录建好」被误读为「链路可跑」）。

### D6 既有基座复用，main.py 零触碰

rag 域未来出参走既有 `R<T>`、异常走 `exceptions` / `exception_handler`、配置走 `Settings` 扩展——**复用不复制**。本轮 main.py 不 import rag（未接线，import 无意义且扩大回归面）；import 链完好由起服冒烟承载（验证 ②）。pyproject / uv.lock 零 diff（uv_build backend 对 src layout 子包自动包含，验证 ③ 以 whl 清单实证）。

## 5. 工程规范补充细则（§5 第 1 条细化，随本轮记档进规范体系）

R11 spec §5 第 1 条「src layout，包内模块平铺；业务复杂后再分包，不预分层」补充细则（本轮 RAG 域首次触发，裁定逻辑见 D1）：

- **域触发分包**：新业务域（有独立业务语义的内聚能力域，如 rag）到达时建一个同名 snake_case 子包；基座设施模块（config/response/exceptions 等横切件）**不因业务域到达而重组**（保持平铺，重组须显式立项——本轮 D6 同口径）。
- **包内平铺**：域子包内模块平铺命名（数据流阶段名或横切职责名），不建分层壳（controller/service/mapper）与组件分类壳（loaders/splitters 子包树）。
- **深度硬顶 2 层**：`cloud_base_ai/<域>/` 下不再有子包；域内模块膨胀需再分时，回架构出增量 spec 再议（突破硬顶是拍板项）。

## 6. 封装设计（文件清单全量与 docstring 定稿文案）

| 文件 | 动作 | 职责（= 文件内唯一内容的一行 docstring，backend-agent 逐字用） |
|---|---|---|
| `src/cloud_base_ai/rag/__init__.py` | Create | `"""RAG（检索增强生成）业务域：ingest → chunk → embed → store → retrieve → generate 管线与编排。"""`
| `src/cloud_base_ai/rag/router.py` | Create | `"""RAG 域 API 入口：FastAPI APIRouter 落点（骨架占位，端点随功能实现轮落）。"""`
| `src/cloud_base_ai/rag/service.py` | Create | `"""RAG 域业务编排：router → service → 管线组件，分层依赖单向。"""`
| `src/cloud_base_ai/rag/schemas.py` | Create | `"""RAG 域 pydantic 模型：入参 DTO 与出参 VO（禁裸 dict，规范 §5-2）。"""`
| `src/cloud_base_ai/rag/ingestion.py` | Create | `"""文档摄入阶段（load）：多源文档加载入口，产出 langchain_core Document。"""`
| `src/cloud_base_ai/rag/chunking.py` | Create | `"""分块阶段（chunk）：文本切分策略落点（依赖 langchain-text-splitters，当前未装）。"""`
| `src/cloud_base_ai/rag/embedding.py` | Create | `"""向量化阶段（embed）：langchain_core Embeddings 实现封装位（供应商预留，config 零键）。"""`
| `src/cloud_base_ai/rag/vectorstore.py` | Create | `"""向量存储阶段（store）：langchain_core VectorStore 实现封装位（供应商预留；MySQL 5.7/Redis 6.1 均无向量能力）。"""`
| `src/cloud_base_ai/rag/retrieval.py` | Create | `"""检索阶段（retrieve）：retriever 构建与检索策略。"""`
| `src/cloud_base_ai/rag/generation.py` | Create | `"""生成阶段（generate）：prompt 组装与 LLM 生成链。"""`
| `cloud-base-ai/README.md` | Modify | 增「RAG Package Skeleton」节：rag/ 子包用途、十文件职责一览、深度硬顶 2 层约定、指向本 spec |

全部 Create 文件为**仅含一行 docstring 的空模块**（无 import / 函数 / 类 / 常量）；不建 `.gitkeep`（`__init__.py` 已保证 git 跟踪）；不建 `tests/rag/`（零测试对象，验证五项承载）。UTF-8 无 BOM、行尾 LF（与既有源文件一致）。

## 7. 数据流

本轮零代码，无运行时数据流。结构级声明见 §3 末（查询链 / 索引链 / 依赖单向）。既有数据流（注册、请求、停机）零触碰。

## 8. 错误处理

零新增面：无新端点、无新异常路径、无新错误码；既有三 handler 与 R 码值表零触碰。

## 9. 测试策略（结构类任务以验证步骤代替单测，沿依赖基座轮先例）

主控定稿五项（实施计划 Task 3 落命令与期望）：

1. 既有 19 pytest 全绿零回归。
2. uvicorn 起服冒烟 `/demo/ping`（code 200）+ `/health`（nacos_registered=true）不回归，验后停服收口（端口释放复核）。
3. **uv build 产物实证新子包被带走**：whl 清单含 `rag/` 全部 10 文件（editable install 下 `import cloud_base_ai.rag` 恒通、不构成「打包带走」证据，故以 whl 清单为准；`import` 检查作伴随项）。whl 属构建产物且 cloud-base-ai/.gitignore 未含 `dist/`——**验后删 dist/，git status 零污染**。
4. 目录树与本文 §6 清单逐项核对（`find` 输出对照）。
5. `pyproject.toml` / `uv.lock` 零 diff（依赖零变更红线）。

e2e 全量回归不强制（零网关路由/认证面触碰，沿 R11 口径）。

## 10. 明确不做（范围外）

任何 RAG 功能代码（load/chunk/embed/检索/生成的实现）；任何依赖新增（langchain-text-splitters / langchain-community / vectorstore 包显式排除——D5）；任何 API 端点、路由挂载与契约文档；任何表/DDL/索引；向量存储与 embedding/LLM 供应商选型及 key（config 零新键，LLM key 留 LLM 轮）；main.py 与既有基座模块任何改动；基座模块重组（收拢 core/infra 子包——阶段一显式呈报未采纳）；`tests/rag/` 预建；.gitignore 变更（dist/ 验后即删，入移交）；Java 侧与前端任何改动；e2e 全量回归。

## 11. 移交事项

1. **RAG 功能实现轮必新增依赖**：text-splitters 必需；loaders / vectorstore 视选型 1-2 个——按 langchain 1.x 包体系归位（core 基类 / community / partner 包），届时 `rag/` 各文件从 docstring 空位进入实现。
2. **供应商与 LLM key 轮**：`embedding.py` / `vectorstore.py` 封装位启用；config 键与 Nacos 配置中心消费随 R11 移交 #9 汇流立项。
3. **首个端点轮**：`rag/router.py` 注册 APIRouter + main.py 首次 import rag（本轮 D6 零触碰的解除点）；X-User-* 消费约定（R11 移交 #4）；业务错误码段如需届时议定。
4. **dist/ 与 .gitignore**：本轮验后即删；后续出现频繁 build 的轮次，把 `dist/` 补进 `cloud-base-ai/.gitignore`。
5. **深度硬顶的突破须再议**：rag 包内模块未来再分（如 ingestion 多源加载器膨胀、或引 LangGraph 图编排需 graphs 位置）时，回架构出增量 spec（§5 细则是拍板项，不静默突破）。
