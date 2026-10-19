# 向量集合库级隔离实施计划（2026-10-19，纯后端单章）

> 设计依据：`docs/superpowers/specs/2026-10-19-ai-kb-collection-isolation-design.md`（拍板结论、
> 失败矩阵、易错点均在 spec，本文不重复论证）。轨道：纯后端 A 级；**契约无变更，前端零改动**。
> 实现纪律：实现 agent 不做 git 提交；`dao.py` 预计零改、`vectorstore.get_milvus` 不动——若实现
> 中发现必须改，先回报主控核对再动。

## 开工基线

- 单测基线：`cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run pytest -q`（pytest 须 cd 进
  cloud-base-ai 跑，仓库根跑收集报错且管道吞码假绿）——记录基线用例数，收官须全绿。
- 环境：Milvus 19530 / MinIO 9100 / MySQL 3306 / Ollama embedding 在运行（T8/T9 需要）。
- 存量事实（2026-10-19 实测）：活库 id=20、活文件 id=1（库 20 下）、活子块 102；软删库 20 个、
  软删文件 10 个、软删子块 462；Milvus 仅 `test123` 一个集合。

---

## 后端任务（cloud-base-ai + DDL）

### T1 DDL 增量脚本 + 基线同步 + 实体加列

**文件**：
- 新建 `cloud-base/scripts/sql/2026-10-19-ai-kb-collection-name.sql`
- 改 `cloud-base/scripts/sql/cloud_ai.sql`（仅 `ai_knowledge_base` 建表语句）
- 改 `cloud-base-ai/src/cloud_base_ai/rag/entities.py`（`AiKnowledgeBase`）
- 改 `cloud-base-ai/tests/test_rag_entities.py`（`test_kb_columns_match_ddl`）

**要点**：增量脚本 = ALTER ADD COLUMN（`VARCHAR(64) NOT NULL DEFAULT ''`，comment 见 spec §4，
置于 description 之后）+ 全量回填 `UPDATE ai_knowledge_base SET collection_name = CONCAT('kb_', id)`
（**不带 WHERE**——含软删行统一公式，软删行指向已 drop 集合无碍）。基线建表语句同步加列。实体列
带 `server_default=text("''")` 与 DDL 对齐。

**易错**：实体列若不设 server_default，既有 `create_kb`/`_seed_kb` 类用例的 ORM 插入（未赋新列）
会在 SQLite 替身上触发 NOT NULL 报错——server_default 兜住，用例随 T4 更新为显式断言。

**验收**：
- `test_kb_columns_match_ddl` 列集含 `collection_name` 且全量单测绿（此时代码未消费新列）；
- 脚本语句数=2（ALTER+UPDATE），注释说明回填含软删行的原因（一句话）。

### T2 storage 补按前缀清理封装

**文件**：改 `cloud-base-ai/src/cloud_base_ai/rag/storage.py`；改 `cloud-base-ai/tests/test_storage.py`

**要点**：`MinioStorage` 新增方法（同步 SDK，service 侧负责 `run_in_threadpool` 包装）：

```python
def remove_prefix(self, prefix: str) -> int:
    """按前缀清空对象，返回删除数：list_objects(recursive=True) 遍历逐个 remove_object。
    空前缀/无对象=空迭代零操作（幂等，删库重试安全）。"""
```

**验收**：单测（FakeMinio 替身沿既有 test_storage 形态）覆盖：多对象全删返回计数、空前缀零调用
不报错、前缀不存在零调用。既有 storage 用例不破。

### T3 vectorstore 补 drop_collection 辅助（get_milvus 不动）

**文件**：改 `cloud-base-ai/src/cloud_base_ai/rag/vectorstore.py`

**要点**：新增模块级函数 `drop_collection(collection_name: str) -> None`——pymilvus
`MilvusClient`（按 settings.milvus.uri 新建连接，低频操作不缓存）`has_collection` 判断在前、
`drop_collection` 在后，**收尾必须 `get_milvus.cache_clear()`**。函数 docstring 写明为什么
（spec §6：drop 后缓存实例持已删集合字段表，复用即写废；懒建自愈与 _v2 切换都依赖缓存清除）。
`get_milvus` 本体零改。

**验收**：真连接验证归 T8 冒烟场景 [5]（单测层 SQLite 无 Milvus，只验证函数存在与签名不现实——
本任务不写单测，冒烟覆盖）。

### T4 service：命名中心化 + create_kb 两步式写派生名

**文件**：改 `cloud-base-ai/src/cloud_base_ai/rag/service.py`；改 `cloud-base-ai/tests/test_rag_service.py`

**要点**：模块级函数 `derive_collection_name(kb_id: int) -> str`（返回 `f"kb_{kb_id}"`，命名规则
唯一实现处，docstring 说明一库一集合与 `_v2` 演进）。`create_kb` 内：

```python
kb = await dao.save_kb(session, AiKnowledgeBase(name=req.name, description=req.description))
kb.collection_name = derive_collection_name(kb.id)   # 两步式：id 由 flush 回填后才可知
```

赋值随 `@transactional` 提交时自动 flush 持久化（save_kb 内部 refresh 已完成，之后赋值无冲突；
不需要显式 update 调用）。

**验收**：单测断言建库后行内 `collection_name == f"kb_{row.id}"`（含查重 5003 / IntegrityError
兜底分支的既有用例不破）。

### T5 service：上传链两阶段重构（消灭字面量一）

**文件**：改 `cloud-base-ai/src/cloud_base_ai/rag/service.py`；改 `cloud-base-ai/tests/test_rag_service.py`

**要点**：`upload_file_by_kb` 改编排方法（无 `@transactional`、不碰 `current_session`），形态对齐
既有 `reembedding`（重嵌入已是两阶段——本任务使其与重嵌入**形态一致**，非新模式）：

```python
class UploadContext(NamedTuple):
    kb_id: int
    collection_name: str

@transactional
async def _load_upload_context(kb_id: int) -> UploadContext: ...
    # find_kb_by_id None→5004，取列纯值

@transactional
async def _persist_file_chunks(ctx: UploadContext, file_name: str, object_name: str,
                               parent_docs: list[Document], child_docs: list[Document]) -> list[int]: ...
    # 文件行（file_path=object_name 等登记字段）+ 父子块行同一事务；返回子块行 id 清单
    # （纯值出事务防惰性加载陷阱——沿 ReembeddingContext 模式）

async def upload_file_by_kb(kb_id: int, file: UploadFile) -> int: ...
    # 编排：① 读上下文 ② 解析分片+MinIO put（事务外，失败零 DB 写）③ 落库 ④ get_milvus(ctx.collection_name).add_documents
```

Milvus 实例名改 `ctx.collection_name`（字面量 `"test123"` 消失）。新增常量
`CODE_VECTOR_WRITE_FAILED = 5014` / `MSG_VECTOR_WRITE_FAILED = "向量写入失败，请对该文件执行重新向量化"`，
阶段④失败 `log.error` 记根因后转 5014（msg 刻意引导重嵌入而非重试上传——重试会产生重复文件行，
spec §3.3）。方法体行数守 ≤50 行目标（编排薄、阶段方法单一职责）。

**验收**：
- 既有上传用例组（`_run_upload` 替身结构保持：FakeMilvus 按名返回、monkeypatch 点不变）全绿；
- 新增：阶段④失败 → 5014 且**文件行与块行已在库**（断言 saved 实体落库、FakeMilvus.added 为空）；
  MinIO put 失败 → 零 DB 写（既有用例语义在新形态下保持）；5004 分支零外部调用；
- FakeMilvus 替身可记录收到的 collection 名——断言等于 `kb_{kb_id}`（字面量清零的功能性证明）。

### T6 service：重嵌入读绑定列（消灭字面量二）

**文件**：改 `cloud-base-ai/src/cloud_base_ai/rag/service.py`；改 `cloud-base-ai/tests/test_rag_service.py`

**要点**：`ReembeddingContext` 加 `collection_name: str` 字段；`_load_reembedding_context` 在
file_row 校验后按 `file_row.kb_id` 查 kb 行取列（文件行活 ⇒ 库必活——库删时文件行已级联软删、
5005 在先，无需额外 5004 分支）；`_swap_vectors` 签名加 `collection_name` 参数，`reembedding`
编排传 `ctx.collection_name`。

**验收**：既有重嵌入用例组全绿（替身 `get_milvus` 按名返回，断言名=种子库行内列值）；新增一个
「字面量清零」断言（FakeMilvus 收到名 != "test123" 且 == kb 行列值）。

### T7 service：delete_kb 三存储全清编排

**文件**：改 `cloud-base-ai/src/cloud_base_ai/rag/service.py`；改 `cloud-base-ai/tests/test_rag_service.py`

**要点**：改编排方法 + 两个阶段方法（spec §3.2 顺序，**先外部清后软删**）：

```python
@transactional
async def _load_kb_collection(kb_id: int) -> str: ...        # find_kb_by_id None→5004，返回列纯值
@transactional
async def _soft_delete_kb_cascade(kb_id: int) -> None: ...   # 既有四级联软删原样搬入，rowcount=0→5004
async def delete_kb(kb_id: int) -> None:
    """编排：MinIO 前缀清(裸 500) → Milvus drop(5008) → MySQL 级联软删。
    先外部后软删=失败时 MySQL 未动、原样重删即重试（后置则重试被 5004 挡死）。"""
```

Milvus drop 走 T3 的 `vectorstore.drop_collection`（含 cache_clear），失败 `log.error` +
`BusinessException(MSG_VECTOR_PURGE_FAILED, CODE_VECTOR_PURGE_FAILED)`（5008 复用）；MinIO
前缀清与 Milvus drop 均以 `run_in_threadpool` 包装（同步 SDK）。MinIO 清失败**不转码**裸上抛
（与上传链 MinIO 失败对称）。方法 docstring 写明中间态收敛路径（drop 成功+软删失败 → 再上传
懒建自愈）。

**验收**（单测替身：FakeStorage 加 remove_prefix 记录、FakeVectorDrop 记录 drop_collection 调用）：
- 顺序断言：remove_prefix 先于 drop_collection 先于 MySQL 软删（记录调用序列）；
- drop 失败 → 5008 且 MySQL 零软删（行仍活）；MinIO 失败 → 裸异常且 Milvus/MySQL 均未动；
- 5004 分支零外部调用；rowcount=0 并发兜底分支保持；
- 前缀串断言 `f"kb/{kb_id}/"`、drop 名断言 = 行内列值。

### T8 冒烟脚本扩展（真连接）

**文件**：改 `cloud-base-ai/scripts/smoke/smoke_milvus.py`

**要点**：在既有 [1]-[4] 场景后增场景 [5]「一库一集合隔离 + drop 后懒建重建」（全部用带时间戳
临时集合，不碰业务集合，沿脚本既有纪律）：两个不同名集合各写一条 → 各自 similarity_search 只
命中自己（隔离证明）→ `vectorstore.drop_collection(A)` → 再经 `get_milvus(A)` add_documents +
检索成功（懒建重建 + cache_clear 生效证明——spec §6 易错点的功能性验证）。

**验收**：真跑通过（前置 Milvus+Ollama 在运行，命令同脚本头部注释）。

### T9 存量处置运维步骤（按序执行，本任务即验收清单）

**顺序依赖：先 DDL 后部署**（旧代码不感知新列可继续跑；新代码 SELECT 带新列、表无列即报错）。

1. 执行增量脚本（pymysql 口径——脚本含中文 COMMENT，utf-8 读文件 + charset="utf8mb4" 逐语句执行；
   命令模板沿 CLAUDE.md「pymysql 优先」条目）；
2. 重启 cloud-base-ai（新代码）；
3. 活文件重嵌入：`POST /ai/rag/kb/reembedding` body `{"file_id": 1}`（网关 admin token 或直连
   8010 手工带鉴权 header）——新代向量入 `kb_20`，该文件在 test123 内的历代向量被删除清单收敛；
4. drop `test123`：pymilvus 一次性命令（`MilvusClient(...).drop_collection("test123")`）——
   462 行软删孤儿向量随集合消失；
5. **MinIO 孤儿对象清理：按 deleted=1 文件行的 file_path 逐对象精确删，禁止按 kb/ 前缀批删**——
   前缀内混有活库对象（活文件原件在 `kb/20/` 下，前缀批删会误删致活文件无法重嵌入）。一次性
   pymysql 查行 + storage.remove 循环；
6. 三侧验证：Milvus `list_collections` == `["kb_20"]` 且 num_entities==102；MinIO `kb/` 前缀仅剩
   活文件 1 个对象；MySQL 全行 `collection_name` 非空（`SELECT COUNT(*) FROM ai_knowledge_base
   WHERE collection_name=''` == 0）。

**验收**：6 步全过；中途任一步失败按 spec §3.2/§8 幂等语义重试（drop 已含 has 判断、重嵌入幂等）。

### T10 handoff 清账 + 全量回归收口

**文件**：改 `docs/handoff/ai.md`

**要点**：按 spec §10 表格执行——删「test123 硬编码」「孤儿向量与孤儿原件」两条；「外部存储
写入与 MySQL 不在同一事务」改写为两阶段化后的残余口径（不再含上传链 Milvus 失败留 MinIO 孤儿
对象的旧形态）；新增「kb 冗余计数从未维护」「get_milvus lru_cache 无上限（集合数百级内无碍）」
两条（含登记日期 2026-10-19）。

**验收**：`cd cloud-base-ai && uv run pytest -q` 全绿（含守护测试），用例数 ≥ 基线 + 新增；两文档
（spec/plan）与实现无出入——有出入时改文档留实现理由，不静默漂移。

---

## 收口验收（主控执行）

1. T1-T10 全绿 + T9 六步过；
2. `grep -rn "test123" cloud-base-ai/src cloud-base-ai/scripts` 零命中（字面量清零的机械证明）；
3. 端到端抽测：建库 → 上传（集合懒建、行内列回填）→ 重嵌入（同集合置换）→ 删库（三侧核验：
   Milvus 集合消失、MinIO 前缀空、MySQL 三表软删）。

## 移交备忘（下轮规划前必读）

- 单文件删除端点落地时的三存储清序与向量清理同构口径——见 spec §11；
- 换 embedding 维度的 `_v2` 切换操作手册（零代码，UPDATE 列 + 逐文件重嵌入 + drop 旧集合）——
  spec §11；
- 检索轮接入：从行内 collection_name 取集合，多库授权检索=各授权库集合分别检索合并——spec §11；
- kb 冗余计数、lru_cache 上限两条新欠账在 handoff，消费方出现再评估。
