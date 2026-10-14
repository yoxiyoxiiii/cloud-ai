# cloud-base-ai ORM 实体 + DAO 持久层设计（2026-10-13）

> 轨道：纯后端 A 级。阶段二产物；阶段一需求分析与拍板结论见本文 §2。**零 API 端点、零契约面——不建 contracts/ 文档**（沿 R12「零契约面不建 contracts」先例；错误码零新增、权限标识零新增、菜单种子零涉及）。

## 1. 背景与目标

以 `cloud-base/scripts/sql/cloud_ai.sql` 的 DDL（两表：`ai_knowledge_base` / `ai_knowledge_base_files`）为依据，在 AI 基座 cloud-base-ai 落地首个持久层：**ORM 实体 + DAO 数据操作对象 + 连接管理基建**，为后续「知识库管理端点轮」与「RAG ingestion 轮」提供地基。本轮不含任何 API 端点。

用户 WIP 三处痕迹（`rag/orm/` 空包、router 尾部 create_kb 语法错误占位、schemas 的 CreateKBReq）按 §2 拍板处置。

**现状事实修正（阶段一核查）**：DB 依赖栈已在 venv（uv.lock 实证 sqlalchemy 2.1.4 `[asyncio]` / aiomysql 0.3.2 / pymysql 1.2.3 / alembic 1.20.0 / greenlet 3.5.6）——「尚无 ORM/DB 访问层」仅指代码；本轮对选型是**确认**而非空白引入。

## 2. 拍板结论（阶段一呈报后拍板，2026-10-13）

1. **Q6 落位 = 基建 db/ + 域内平铺**：连接/session/实体基类落 `cloud_base_ai/db/`（横切基建包，未来会话表/Agent 表同源）；实体+DAO 落 rag/ 域内平铺（`rag/entities.py` + `rag/dao.py`，两实体一文件、两 DAO 一文件）；用户 WIP 的 `rag/orm/` 空包删除。**「深度硬顶 2 层」未突破（rag/ 下零子包）——rag/orm/ 方案曾议未采纳，记档备查**（R12 §5 突破须拍板的条款本轮未触发）。
2. **Q9 DDL 修订四项全同意**：① 修两表 id 注释笔误（files 表 id 改「文件ID（主键）」）；② `ai_knowledge_base` 补 `uk_name(name)`；③ `file_count`/`file_chunk_count` 补 `DEFAULT 0`；④ `ai_knowledge_base_files` 补 `uk(kb_id, file_path)`——VARCHAR(512)×utf8mb4 索引 2048 bytes，实现轮实测 innodb_large_prefix，不行退前缀索引 `(kb_id, file_path(191))`（plan 带实测分叉步骤）。实施先核对 cloud_ai 库现状：未建改剧本直执行 / 已建出增量 ALTER（scripts/sql/ 日期剧本惯例）；已建表的注释笔误不追改（MODIFY 全列定义风险大于收益，仅剧本层修）。
3. **Q11 WIP 处置**：移除 router.py 尾部 create_kb 语法错误占位（当前工作区 import 即炸，阻断一切验证）；`CreateKBReq` 留 schemas.py（合法代码，端点轮消费）。
4. **其余九问全按架构倾向**：SQLAlchemy 2.x typed Declarative + async engine + aiomysql；实体与 pydantic DTO 分离（转换器随端点轮）；DAO 模块函数式、方法收 session 参数、不自带 commit（事务边界 = service 层一个事务块）；软删每方法显式 `filter(deleted == 0)`、审计公共路径填充（account 来源 contextvar，挂点留端点轮）；DatabaseSettings 默认本机事实值（charset utf8mb4 显式、pool_recycle 3600）；engine 生命周期 lifespan shutdown dispose；DB 不可达 = 惰性首连请求期报错不阻断启动；测试 = SQLite 内存替身（aiosqlite 新增 dev 依赖）+ 真库冒烟不进 pytest；alembic 不初始化（表结构真相源 = 手写 SQL）；本轮零新错误码（DAO 异常透传全局 handler 走 500，业务码端点轮从 5003 接续）。

## 3. 领域定位与边界

- 两表 = RAG 索引链（ingestion→chunking→embedding→vectorstore）的**管理面/元数据面**：向量本体未来落外部 vectorstore（vectorstore.py docstring 已记档 MySQL/Redis 无向量能力），两表只承载业务元数据，经 kb_id/file_path 关联向量侧。
- `ai_knowledge_base_files` 的 `file_chunk_size/file_chunk_overlap/file_split_type` 是 **chunking 参数在文件级的快照**（每文件可不同分片策略，ingestion 编排时写入、重建索引时读回）。
- `file_count/file_chunk_count` 是库级冗余计数（列表页免聚合）：维护责任属**编排层（service）事务内**，DAO 只提供能力（count_kb_files 等），计数维护编排本轮显式 OUT。
- **消费方**：未来「知识库管理 service」（create_kb 端点轮）与 RAG ingestion 编排。本轮产出对他们是无 UI 的内部层。

## 4. 架构（文字版）

```
端点轮（OUT）              本轮（IN）
rag/router.py ──OUT──► rag/service.py（编排·事务块 async with session.begin()）   ← 端点轮/ingestion 轮
                              │ 调用（收 session、异常不转译）
                              ▼
                       rag/dao.py（15 个模块函数：save/find/update/page_list/list/count/delete × 两实体）
                              │ 映射
                       rag/entities.py（AiKnowledgeBase / AiKnowledgeBaseFiles）
                              │ 继承 Mixin
                       db/base.py（Base + AuditMixin + SoftDeleteMixin + 审计填充 + current_account contextvar）
                       db/engine.py（build_url / get_engine / get_session_factory / dispose_engine，lru_cache）
                       config.py（DatabaseSettings 嵌套段）
                              ▼
                       MySQL 5.7.24 @127.0.0.1:3306/cloud_ai（aiomysql + utf8mb4）
```

依赖方向单向：`rag/dao → rag/entities → db/base → (sqlalchemy)`；`db/engine → config`；`main.py` 仅在 lifespan shutdown 调 `dispose_engine()`。

## 5. 决策记录

### D1 选型 = SQLAlchemy 2.1.4 typed Declarative + async engine + aiomysql（确认用户依赖意向）

- typed Declarative（`Mapped[]/mapped_column`）与 pydantic DTO 是两物（规范第 2 条「pydantic 即 DTO」的边界：实体不是 DTO）；2.0 style 显式 `select()/update()` 语句 SQL 可见，对齐 Java「手写 SQL」精神而非黑盒 ORM。
- async 全链：服务全 async（SSE 流式必须 async），同步 DAO 调用堵事件循环；**Windows 事件循环正相关记档**——main.py 已全局设 WindowsSelectorEventLoop（grpc 坑），恰好是 aiomysql 需要的循环形态。
- **组合未实证**：aiomysql 0.3.2（维护不活跃，PyMySQL 底层尚稳）× SQLAlchemy 2.1.4 × MySQL 5.7.24 × Windows——真库冒烟必做（§9）；fallback = pymysql + `asyncio.to_thread` 包装（**换执行面不换结构**：dao.py 函数签名与事务口径不变，仅 engine/驱动层替换）。
- alembic 1.20.0 闲置（依赖在、不初始化）：表结构真相源 = 手写 SQL 剧本（沿 Java scripts/sql/ 惯例，防双真相源）；表演进走日期增量剧本。

### D2 落位 = db/ 基建包 + rag/ 域内平铺（拍板 1）

- `cloud_base_ai/db/`：横切基建包（`models/` 子包先例），含 `base.py`（Base/Mixin/审计）、`engine.py`（连接）、`__init__.py`（导出收敛）。基建不属任何业务域。
- `rag/entities.py` + `rag/dao.py`：知识库实体是 RAG 域业务对象，落域内**平铺文件**（R12 十文件之外的自然生长，同 chat 轮 generation.py 被激活的口径）——rag/ 下零子包，「深度硬顶 2 层」维持。
- `rag/orm/` 空包删除；router 尾部占位移除（拍板 3）。

### D3 实体形态 = Mixin 组合 + 嵌套枚举禁魔法值

- `Base(DeclarativeBase)` 全局唯一；`AuditMixin`（create_by/create_time/update_by/update_time）+ `SoftDeleteMixin`（deleted + 嵌套 `DeletedEnum(IntEnum)`，NORMAL=0/DELETED=1，Java 实体内嵌枚举精神的 Python 对应）。
- `AiKnowledgeBaseFiles` 嵌套 `SplitTypeEnum(str, Enum)`（PARENT_CHILD="parent-child"/RECURSION="recursion"+of()），字段类型保持 str（Java 第 8 条「字段保持 Integer 映射」同款：枚举仅常量表，存取不经 Enum 实例——避开 `str(Enum)` 的 `str()` 陷阱）。
- 类型映射：`VARCHAR(n)→String(n)`、`DATETIME→DateTime`、`TINYINT→SmallInteger`（跨方言兼容，deleted 是 int 语义不必锁 TINYINT 字节宽）、`BIGINT PK→Integer primary_key`（MySQL 主键自增）。DDL 的 DEFAULT 以 `server_default=text(...)` 忠实映射（deleted='0'、file_count/file_chunk_count='0'、create_time='CURRENT_TIMESTAMP'）。
- **uk/索引在 `__table_args__` 声明**（uk_name / uk_kb_file_path / idx_kb_id + 表 comment）——供 SQLite create_all 建出同语义约束；物理真相源仍是手写 DDL（若走前缀索引分叉，物理形态与 ORM 声明不一致，无害记档：ORM 声明只影响测试建表）。

### D4 DAO 口径 = 模块函数收 session，flush 不 commit（拍板 4）

- 15 个函数（kb 8 + files 7），动词对齐规范第 4 条：

| AiKnowledgeBase | AiKnowledgeBaseFiles |
|---|---|
| save_kb / find_kb_by_id / find_kb_by_name | save_kb_file / find_kb_file_by_id |
| update_kb / delete_kb_by_id | update_kb_file / delete_kb_file_by_id |
| page_list_kbs(name 模糊可选) / list_kbs / count_kbs | page_list_kb_files(kb_id 必带, file_name 模糊可选) / list_kb_files(kb_id) / count_kb_files(kb_id) |

- **软删口径逐方法**：所有 find/page_list/list/count 显式 `filter(deleted == 0)`；delete = `UPDATE SET deleted=1 + update_by/update_time 审计两值`（core update 语句带 where id+deleted=0，返回 rowcount；二次删返回 0）。
- **审计口径**：save/update 走 `fill_create_audit/fill_update_audit`（db/base.py 公共路径）——插入填四值（update_*=create_*，对齐 Java「插入四值=create 值」）、更新填两值；delete 在语句 values() 里直填两值。account 来源 `current_account()` contextvar（默认 None → 可空列存 NULL），**挂点（security 依赖里 set）留端点轮**。
- **save = add + flush + refresh**：refresh 显式回填 server_default 生成值（file_count/deleted/create_time）——async 环境下 flush 后直接访问 server 生成列会触发隐式 SELECT 报 MissingGreenlet，refresh 是显式合法路径；id 自增走 lastrowid 回填。
- **update = fill_update_audit + merge + flush**（消费方典型流：find 拿活实体→改字段→update_kb）。
- **分页 clamp**：`page = max(1, page)`、`size = max(1, min(size, 200))`（对齐 Java 分页插件 maxLimit 200）；排序 `id DESC`（主键反向扫，无额外索引）。
- **DAO 层零异常转译**：IntegrityError（uk 撞重/墓碑占键）与连接异常原样上抛——service 层兜底 `IntegrityError → 业务码`（Java DuplicateKeyException 兜底的对应物，端点轮落）；本轮 DAO 若被误从端点直触，全局 handler 500 兜底（零新码）。

### D5 事务边界 = 编排层 session.begin() 事务块

- DAO 写方法以 **flush 收尾、永不 commit**——多表写原子性由消费方一个事务块保证（`async with session.begin():` = Java `@Transactional(rollbackFor)` 的对应物）。多表写拼不进一个块即违规（对齐 Java「双事务边界即违规」口径）。
- session 获取：`async with get_session_factory()() as session:`（factory 每次 yield 独立 session）；`expire_on_commit=False`（async 硬要求，防 commit 后属性访问隐式 IO）。
- 行为锚在测试：begin 块内 save 两表 + 异常 rollback 零残留（见 §9）。

### D6 config DB 段 = DatabaseSettings 默认本机事实值（拍板 4）

```python
class DatabaseSettings(BaseModel):
    host: str = "127.0.0.1"; port: int = 3306; user: str = "root"
    password: str = ""; database: str = "cloud_ai"
    pool_size: int = 5; max_overflow: int = 10; pool_recycle: int = 3600; echo: bool = False
```

- 挂 `Settings.db`，env 形态 `CLOUD_BASE_AI_DB__*`（双下划线嵌套，既有拍板口径）；默认本机值对齐 Nacos 段先例（DB 是本机基础设施，与 LLM 外部付费依赖的「默认空=未配置态」哲学不同源，记档）。`.env.example` 补 db 段键注释。
- URL 组装独立函数 `build_url(cfg)`（可单测）：`mysql+aiomysql://{quote_plus(user)}:{quote_plus(password)}@{host}:{port}/{database}?charset=utf8mb4`——**charset=utf8mb4 显式**（CLAUDE.md Windows 中文 GBK 陷阱记档：不显式则连接层乱码）+ 密码特殊字符 quote_plus 防御。
- **pool_recycle=3600**：防 MySQL wait_timeout（5.7 默认 8h）stale 连接；pool_pre_ping 不开（每请求多一次 ping，本机量级 recycle 足够，记档）。pool_size 5+overflow 10：单进程 async 开发量级够用，溢出等待 30s（pool_timeout 默认），键可调。

### D7 engine 生命周期 = lru_cache 工厂 + lifespan dispose；DB 不可达 = 请求期报错

- `get_engine()/get_session_factory()` lru_cache 进程单例（engine 创建零网络，连接池惰性建连——**惰性首连**：DB 不可达在首个真实操作时报 sqlalchemy 异常 → 请求期 500，不阻断启动，对齐「缺配置/不可达是运行时可观测错误」降级哲学）。
- `main.py` lifespan shutdown 追加 `await dispose_engine()`（dispose + 双 cache_clear，幂等）——对齐 NacosRegistration 优雅关停先例；startup 零动作（无 ping 无预建）。
- 日志：engine 模块 logger debug 级记 dispose 事件；DAO/引擎异常不在基建层 catch（D4 零转译），日志归调用层（规范第 3 条的 catch 责任在 service，端点轮落）。

### D8 DDL 修订与索引设计（拍板 2）

| 索引 | 命中的查询 | 取舍 |
|---|---|---|
| `uk_name(name)`（新增） | `find_kb_by_name` 查重 + save 撞名 IntegrityError 兜底（防并发 TOCTOU 与**墓碑占键**——不含 deleted，沿 Java sys_user uk_account 先例，墓碑同名重建由应用层转业务码提示） | name 是唯一业务标识（CreateKBReq 唯一输入字段同源）；不建 idx_name（uk 即索引） |
| `uk_kb_file_path(kb_id, file_path)`（新增，分叉） | 同库同路径重复登记防御（未来 ingestion 查重/幂等路径） | VARCHAR(512)×utf8mb4=2048 bytes 超 767 旧限：5.7.24 innodb_large_prefix 默认 ON（3072 上限内）可整列；实测 1071 错误退 `(kb_id, file_path(191))` 前缀形态 |
| `idx_kb_id(kb_id, deleted)`（既有） | `page_list_kb_files/list_kb_files/count_kb_files` 的 `WHERE kb_id=? AND deleted=0` | 既有不动；不建 deleted 单列（0/1 低选择性，Java 先例） |
| kb 表无二级索引（除 uk_name） | `page_list_kbs WHERE deleted=0 ORDER BY id DESC` 走主键反向扫+过滤 | 管理面库数量级小， deleted 低选择性建索引无益，记档接受 |

- `DEFAULT 0` 两列：让「创建空库」insert 省略计数列即得 0（配合实体 server_default，省略列不进 INSERT 是 SQLAlchemy 2.x 行为）。
- DATETIME 无小数秒：MySQL 5.7 截断微秒（断言口径按秒级/不等值比较，记档）；SQLite 内存测试不截断（DateTime 存字符串）——测试断言避开精度面。
- DDL 修订四项落剧本 `cloud_ai.sql`（未提交资产，直接改）；已建表分支出增量 `scripts/sql/2026-10-13-cloud-ai-orm-dao.sql`（结构三项 ALTER，注释笔误不追改记档）。

### D9 测试 = SQLite 内存替身 + anyio 标记 + 真库冒烟（拍板 4）

- **单测层（pytest）**：`sqlite+aiosqlite://`（内存）+ StaticPool + 每测试全新引擎建表（`make_db_session()` async contextmanager helper）——零环境依赖、毫秒级；async 测试跑法 = **anyio pytest 插件**（anyio 随 fastapi 传递依赖已在、自带 pytest11 entry point，**零新增测试依赖**），模块级 `pytestmark = pytest.mark.anyio` + conftest `anyio_backend="asyncio"`。兼容风险（pytest 9.1.1 × anyio 插件）「实现时验证」；fallback = dev 组加 pytest-asyncio（记档，不动实现代码）。
- aiosqlite 进 dev 依赖组（`uv add --group dev aiosqlite`）——唯一新增依赖。
- **方言差异记档**：SQLite 覆盖不到 MySQL 特有面（charset 行为、server 时区、错误码细节）——uk 撞重以 `sqlalchemy.exc.IntegrityError` 统一断言（两方言同抛）；MySQL 特有面由冒烟覆盖。
- **真库冒烟（不进 pytest）**：一次性脚本 smoke_db.py（验后删），走 `get_engine()` 真链路：建连 SELECT 1 / CRUD roundtrip / 软删过滤+墓碑占键 IntegrityError / 中文读写（charset 陷阱实证）/ `SHOW CREATE TABLE` 断言 uk_name（DDL 修订实证）。冒烟数据 `e2e` 前缀 + 结束物理 DELETE 清理（规范第 6 条「涉及库表时测试数据 e2e 前缀」口子的兑现）。
- conftest 顶层设 WindowsSelectorEventLoopPolicy（对齐 main.py，防 aiosqlite/事件循环形态差异）。

### D10 WIP 清理（拍板 3）

`git rm -f rag/orm/__init__.py`（目录随空）；router.py 尾部 `async def create_kb()` 占位（含前置空行）删除；`CreateKBReq` 留 schemas.py 不动。清理后先跑全量 pytest 记录基线计数，作为回归门分母。

### D11 零契约面记档

零端点/零 DTO 契约/零错误码/零权限标识/零菜单——不建 contracts/ 文档（R12 先例）；AI 段 5001/5002 已占不变，业务码（如「知识库不存在/名称已存在」）端点轮从 5003 接续。

## 6. 封装设计（文件清单全量）

| 文件 | 动作 | 职责 |
|---|---|---|
| `cloud-base-ai/src/cloud_base_ai/config.py` | Modify | `DatabaseSettings` 九字段 + Settings 挂 `db` 段（D6） |
| `cloud-base-ai/src/cloud_base_ai/db/__init__.py` | Create | 包 docstring + 收敛导出（Base/Mixin/fill_*/current_account/get_engine/get_session_factory/dispose_engine/build_url） |
| `cloud-base-ai/src/cloud_base_ai/db/base.py` | Create | `Base` + `AuditMixin` + `SoftDeleteMixin(DeletedEnum)` + `fill_create_audit/fill_update_audit` + `current_account/set_current_account` contextvar（D3） |
| `cloud-base-ai/src/cloud_base_ai/db/engine.py` | Create | `build_url`（quote_plus+charset）/`get_engine`（lru_cache）/`get_session_factory`（expire_on_commit=False）/`dispose_engine`（幂等）（D6/D7） |
| `cloud-base-ai/src/cloud_base_ai/main.py` | Modify | lifespan shutdown 追加 `await dispose_engine()`（其余零改动） |
| `cloud-base-ai/src/cloud_base_ai/rag/entities.py` | Create | `AiKnowledgeBase` / `AiKnowledgeBaseFiles`（Mixin 组合、__table_args__ uk/idx 声明、SplitTypeEnum）（D3） |
| `cloud-base-ai/src/cloud_base_ai/rag/dao.py` | Create | 15 个 DAO 函数（D4 矩阵 + 软删/审计/flush 口径） |
| `cloud-base-ai/src/cloud_base_ai/rag/orm/` | Delete | 用户 WIP 空包（D2/D10） |
| `cloud-base-ai/src/cloud_base_ai/rag/router.py` | Modify | 移除尾部 create_kb 语法错误占位（其余零改动） |
| `cloud-base-ai/pyproject.toml` | Modify | dev 组 + aiosqlite（经 uv add，D9） |
| `cloud-base-ai/.env.example` | Modify | db 段键注释（默认本机值零配置即连说明） |
| `cloud-base/scripts/sql/cloud_ai.sql` | Modify | 四项修订（D8；id 注释笔误/uk_name/DEFAULT 0/uk_kb_file_path） |
| `cloud-base/scripts/sql/2026-10-13-cloud-ai-orm-dao.sql` | 条件 Create | 已建表分支增量 ALTER（D8；未建分支不建此文件） |
| `cloud-base-ai/tests/conftest.py` | Create | Windows policy + `pytest_plugins=("anyio",)` + `anyio_backend` fixture（D9） |
| `cloud-base-ai/tests/db_testing.py` | Create | `make_db_session()` helper（每测试全新 SQLite 内存引擎+create_all+yield session+dispose）（D9） |
| `cloud-base-ai/tests/test_db_engine.py` | Create | build_url 形态/quote_plus 转义/get_engine 单例/dispose 后重建（D6/D7） |
| `cloud-base-ai/tests/test_rag_entities.py` | Create | 表名/列集对齐 DDL 清单/uk 与 idx 的 metadata 声明断言（D3） |
| `cloud-base-ai/tests/test_rag_dao.py` | Create | 主战场 ~26 用例（§9 清单：软删/审计/contextvar/uk 撞重/分页/事务块 rollback）（D4/D5） |
| `cloud-base-ai/tests/test_settings.py` | Modify | 追加 db 段默认值 + env 覆盖用例（`_env_file=None` 隔离纪律沿用） |
| `cloud-base-ai/smoke_db.py` | Create（临时） | 真库冒烟四项 + uk 实证（验后删，D9） |
| `cloud-base-ai/README.md` | Modify | 增「Database」小节：db 段键名/冒烟口径/事务边界约定 |

## 7. 数据流（DAO 写路径时序，以端点轮未来的 create_kb 为例）

1. （未来）router 校验 DTO → service `async with get_session_factory()() as session:` 取 session。
2. service `async with session.begin():` 开事务块 → `find_kb_by_name(session, name)` 查重（deleted=0）→ 命中抛业务码。
3. `save_kb(session, AiKnowledgeBase(name=...))`：fill_create_audit（current_account()+now 四值）→ add → flush（INSERT，省略 file_count/deleted 由 DB DEFAULT 填 0）→ refresh 回填 server 值。
4. 块正常退出 = commit（begin 上下文职责，DAO 永不 commit）；块内异常 = rollback（find 命中抛业务码场景，零残留）。
5. 撞 uk_name（含墓碑占键）→ IntegrityError 上抛 → service catch 转业务码（端点轮）；本轮无 service，DAO 异常直达调用方/全局 handler。

## 8. 错误处理

| 场景 | 行为 | 记档 |
|---|---|---|
| DB 不可达/缺配置 | 首个真实操作抛 sqlalchemy 异常 → 请求期 500（全局 handler），不阻断启动 | D7 惰性首连哲学 |
| uk 撞重（含墓碑占键） | `IntegrityError` 原样上抛，DAO 零转译 | D4；service 兜底端点轮落 |
| DAO 未知异常 | 原样上抛 → 全局 handler 500 | 本轮零新码（D11） |
| 事务块内业务异常 | rollback 干净退出，已 flush 未 commit 的写全部回滚 | D5 行为锚在测试 |

## 9. 测试策略

**单测（SQLite 内存，零网络零真库）**：
- test_rag_dao.py 用例清单：save 审计四值（无 context → create_by None / set_current_account("admin") → "admin"）、id 回填、server_default 回填（file_count==0）；find_by_id 命中/未命中/软删不可见；find_by_name 命中/空；update 后 update 两值刷新（账号变更强断言）；delete rowcount=1/再删 0/墓碑行 deleted=1 且审计两值已写（无过滤 select 回查）；page_list 分页正确性/total/name 模糊/软删不计/size clamp；count 软删不计；uk_name 撞 IntegrityError（含墓碑同名重建）；files 全套同构 + uk(kb_id,file_path) 撞 + 按 kb_id 过滤；**事务块 rollback 零残留**（begin 内 save 后抛异常 → 回查为空——D5 行为锚）。
- test_rag_entities.py：表名/列名清单与 DDL 全等、__table_args__ 的 uk_name/uk_kb_file_path/idx_kb_id 存在性。
- test_db_engine.py：build_url 默认值/特殊字符密码 quote/自定义 host:port；get_engine 单例；dispose 后 cache_clear 新实例。
- test_settings.py 增量：db 段默认值 + `CLOUD_BASE_AI_DB__HOST/PORT/PASSWORD` 覆盖。

**真库冒烟（smoke_db.py，验后删）**：SELECT 1 建连（组合实证 D1）→ CRUD roundtrip → 软删过滤+墓碑占键 IntegrityError → 中文 roundtrip → SHOW CREATE TABLE 断言 uk_name——数据 e2e 前缀+结束清理。

**回归门**：既有 pytest 全绿（以 T1 清理后基线计数为准）+ 起服 `/demo/ping` code:200、`/health` 零回归（Nacos 未跑时 nacos_registered:false 属预期降级）+ 收口即停 8010 + `uv build` whl 清单含 db/ 与 rag 两新文件（R12 打包证据口径，dist/ 验后删）。

## 10. 明确不做（范围外）

API 端点（create_kb 等）与 VO/DTO 转换器；security 里 set_current_account 挂点接线；计数维护编排（file_count/file_chunk_count 的 UPDATE 编排属 ingestion 轮）；向量库/文件上传/下载；ingestion/chunking 链实现；alembic 初始化；5003 起业务码分配；菜单种子/e2e（零前端面零网关面，沿 R11/R12 口径不强制全量 e2e）；models/model_factory 与 llm/embedding 配置段（零触碰）。

## 11. 移交事项

1. **端点轮**：create_kb 等 API + 契约 + `convert` 转换器（实体→VO）+ security 依赖里 `set_current_account(account)` 接线（X-User-Account → contextvar）+ IntegrityError→业务码兜底（5003 起）+ 分页参数入 DTO。
2. **ingestion 轮**：files 写路径 + 计数维护编排（事务块内 UPDATE/重算，D5 口径样板已在测试）。
3. **aiomysql 维护度观察**：组合问题反复出现时评估 fallback（pymysql+to_thread 换执行面）或 asyncmy 替换（需再编译验证）。
4. **连接池实测调优**：真实并发画像出来后再调 pool_size/max_overflow/pool_pre_ping。
5. **表结构演进**：一律手写日期增量剧本（alembic 继续闲置；表多到剧本难管时再议引入）。
