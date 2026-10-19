# 向量集合库级隔离与生命周期治理技术方案（2026-10-19，纯后端轮）

> 需求原文：「进行向量数据库名称后端自己维护，修改当前存在的问题」。需求分析九问已全部拍板
> （结论见 §2），本文按拍板口径展开。**契约无变更**——集合名不出后端，现有 API 行为面不变，
> 不出 contracts 文档；前端零改动。

## 1. 背景与目标

`rag/service.py` 两处 `get_milvus("test123")`（上传链与重嵌入置换）把全部知识库的向量写进同一个
Milvus 集合，且向量 metadata 无 kb 维度、Milvus 侧无法按库过滤——库级隔离事实上不可实现。共用集合
又连带三个生命周期缺口：删库不动向量（孤儿累积，实测软删子块 462 行 vs 活子块 102 行）、删库不动
MinIO 原件（孤儿对象 10 个）、上传链外部写埋在 MySQL 事务内（失败留不可修复的孤儿对象）。

本轮交付：`ai_knowledge_base` 落 `collection_name` 绑定列（一库一集合）、三条写链改走绑定列、
删库升级为三存储全清、上传链对齐重嵌入的两阶段形态、存量 `test123` 处置。检索本身不在本轮
（`retrieval.py` 是空壳、零检索消费方——换轨无线上影响，是本轮的窗口期依据）。

## 2. 拍板结论（设计输入，终版）

| 问题 | 拍板 |
|---|---|
| 存量 test123 | drop + 按真相源重建：活文件调既有重嵌入端点写入新集合；test123 整体 drop（462 行软删孤儿随之清零）；存量 10 个 MinIO 孤儿对象按行精确清理 |
| 建集合时点 | 首次上传懒建（langchain-milvus 构造期集合不存在自动按当前 embedding 参数建表）；「无文件即无集合」语义自洽 |
| 删库生命周期 | **三存储全清**：MySQL 软删留追溯 + Milvus drop 该库集合 + MinIO 清 `kb/{kb_id}/` 前缀；顺序**先外部清后软删**；drop 失败复用 5008、MySQL 未动可重试；中间态由上传链懒建自愈 |
| 上传链事务形态 | 对齐重嵌入两阶段：MySQL 先提交、Milvus 后置；失败面变「文件已登记无向量」，由既有重嵌入端点修复——实质清偿「外部存储一致性」欠账 |
| 列与回填 | `VARCHAR(64) NOT NULL`；全量行（含软删）统一公式回填 `kb_{id}`；零新增索引、不加 uk |
| 换维度 `_v2` | 只留演进空间不实现：绑定列即指针，UPDATE 列值 + 跑重嵌入即完成切换 |
| 文件级向量清理 | 本轮零改动（单文件删除端点不存在；方案写明未来端点同构于重嵌入历代清单） |
| kb 冗余计数 | `file_count`/`file_chunk_count` 从未维护——登记 handoff 另轮，不扩本轮 |

## 3. 总体设计

### 3.1 一库一集合模型

- **命名**：`kb_{id}`（`derive_collection_name(kb_id)` 中心化函数，禁散落魔法串）。字母开头合法、
  id 自增不复用、drop 后名字不回收——天然无撞名，故不加 uk。
- **绑定**：`ai_knowledge_base.collection_name` 是唯一取合入口；写链一律「查行取列」不现场派生。
  落列而非每次 `f"kb_{id}"` 的三理由（换维度蓝绿切指针 / 命名策略可演进 / 绑定关系可对账）已拍板。
- **懒建**：`get_milvus(collection_name)` 构造时集合不存在则按当前 embedding 参数建表（首次建表
  参数固化的既有行为）。「库存在但无集合」=「无文件即无向量」，对账兜底异常态。
- **生命周期状态机**：建库（有名无集合）→ 首次上传（懒建集合+写向量）→ 重嵌入（同集合内置换）→
  删库（drop 集合 + 清对象 + MySQL 软删）→ 终态（行软删可追溯、外部存储已物理清、不可恢复——
  当前本无复活端点，软删即事实终态，不破坏任何现有能力）。

### 3.2 删库三清编排（对齐重嵌入的「编排 + 阶段事务」形态）

```
delete_kb(kb_id)                      ← 编排方法：不开事务不碰 current_session
  ├─ ① _load_kb_collection（@transactional 读）：find_kb_by_id → None 抛 5004 → 取列纯值
  ├─ ② MinIO 前缀清（事务外）：storage.remove_prefix(f"kb/{kb_id}/")，S3Error 裸上抛全局 500
  ├─ ③ Milvus drop（事务外）：vectorstore.drop_collection(collection_name)，
  │     失败 log.error + 抛 5008（msg「向量清理失败，请重试」）
  └─ ④ _soft_delete_kb_cascade（@transactional 写）：既有四级联软删原样搬入，rowcount=0 → 5004
```

顺序依据（先外部后软删）：②③任一失败时 MySQL 未动，**原样重试成立**（重删走完整①-④；若后置
软删再清外部，重试会被 5004 挡死只能靠对账）。②在③前：MinIO 失败时向量未动；③失败时对象已清
（重删时②幂等空清）。各步幂等性：②空前缀=空操作；③内部先 `has_collection` 再 drop；④条件写
`deleted=0` 二次删为 0 行兜底。

中间态收敛：③成功后④提交失败的极端窗口 = 集合没了、行还活——该库再上传时懒建自愈（见 §6
缓存清除，这是自愈成立的前提）；表现为「重新上传后恢复正常」，无脏数据。

### 3.3 上传链两阶段对齐

现形态（单 `@transactional`：MinIO 写 → MySQL 三表 → Milvus 写）改为重嵌入同构：

```
upload_file_by_kb(kb_id, file)       ← 编排方法：不开事务
  ├─ ① _load_upload_context（@transactional 读）：find_kb_by_id → None 抛 5004 → 取 {kb_id, collection_name}
  ├─ ② 解析分片（事务外，与重嵌入同构）+ MinIO put_bytes（事务外；失败零 DB 写——现状语义保持）
  ├─ ③ _persist_file_chunks（@transactional 写）：文件行 + 父子块行；返回子块 id 清单（纯值）
  └─ ④ vectorstore.get_milvus(collection_name).add_documents(child_docs, ids=…)
        失败 log.error + 抛 5014「向量写入失败，请对该文件执行重新向量化」
```

失败面变化（与重嵌入的收敛哲学一致——「失败可重试收敛」而非「零孤儿」）：

| 失败点 | 旧形态 | 新形态 |
|---|---|---|
| MinIO 写失败 | 零 DB 写（不变） | 零 DB 写（保持） |
| MySQL 写失败 | 整体回滚，MinIO 留孤儿对象 | 整体回滚，MinIO 留孤儿对象（窗口收窄：仅此一类） |
| Milvus 写失败 | MySQL 回滚 + **MinIO 留不可见不可修的孤儿对象** | **文件已登记无向量**：列表可见、重嵌入端点即修复路径 |

5014 引导重嵌入而非重试上传是关键：新形态下重试上传会登记第二个文件行（旧行悬死），重嵌入按
file_id 置换才是幂等收敛路径。5014 接在 5013（会话管理轮）之后，为 AI 段新末位。

## 4. 数据模型与 DDL

增量脚本 `cloud-base/scripts/sql/2026-10-19-ai-kb-collection-name.sql`（新建库表语句不适用，
本表已存在）：

```sql
-- 向量集合绑定列（2026-10-19 集合隔离轮）：一库一集合，服务端派生，用户与前端不感知
ALTER TABLE ai_knowledge_base
    ADD COLUMN collection_name VARCHAR(64) NOT NULL DEFAULT '' COMMENT 'Milvus集合名（服务端派生kb_{id}，一库一集合；换embedding维度时改本列切kb_{id}_v2新集合后重嵌入）' AFTER description;
-- 全量回填（含软删行，统一公式无特判；软删行指向已随 test123 drop 消失的集合无碍——终态行无人读）
UPDATE ai_knowledge_base SET collection_name = CONCAT('kb_', id);
```

- 基线 `cloud_ai.sql` 的 `ai_knowledge_base` 建表语句同步加列（新装环境无回填需求，DEFAULT ''
  与增量形态一致）。
- **索引审查：零新增**。该列无独立查询路径——唯一消费方式是主键查行随行带出（上传/重嵌入/删库
  链的 find_kb_by_id / find_kb_file_by_id→file_row.kb_id→kb 行）；对账脚本小量全表扫。不加
  uk_collection_name：单一写方派生（自增主键），无撞名路径，加了反而给未来手工修数添约束。
- 执行方式：脚本含中文 COMMENT，按项目口径用 pymysql 执行（utf-8 读文件 + charset="utf8mb4"
  逐语句执行），命令见 plan 验收步骤。
- 实体同步：`AiKnowledgeBase` 加 `collection_name: Mapped[str] = mapped_column(String(64),
  nullable=False, server_default=text("''"), comment=…)`——server_default 与 DDL 对齐，兜住
  既有未赋值插入的测试用例；`test_rag_entities.py` 列集断言加名。

## 5. 链路改造明细

| 位置 | 改动 |
|---|---|
| `service.create_kb` | `save_kb`（flush 回填 id）之后两步式赋值 `kb.collection_name = derive_collection_name(kb.id)`，随事务提交持久化（赋值必须在 save 之后——构造时 id 未知；save_kb 内部 refresh 已完成，之后赋值无冲突）。新增模块级函数 `derive_collection_name(kb_id: int) -> str`（命名规则唯一实现处） |
| `service.upload_file_by_kb` | 按图 §3.3 重构为编排 + 两阶段；Milvus 实例名从行内列取（消灭字面量一）；新增常量 5014 |
| `service.reembedding` | `ReembeddingContext` 加 `collection_name` 字段；`_load_reembedding_context` 按 file_row.kb_id 查 kb 行取列（文件行活 ⇒ 库必活：库删时文件行已级联软删、5005 在先）；`_swap_vectors` 增同名参数（消灭字面量二） |
| `service.delete_kb` | 按 §3.2 重构为三清编排 + `_load_kb_collection` / `_soft_delete_kb_cascade` 两个阶段方法；既有四级联软删 SQL 原样搬入后者 |
| `vectorstore.py` | `get_milvus` **不动**（按名构造即正确形态）；新增 `drop_collection(collection_name) -> None` 辅助（见 §6） |
| `storage.py` | 新增 `remove_prefix(prefix: str) -> None`：`list_objects(bucket, prefix=prefix, recursive=True)` 遍历逐个 `remove_object`；空前缀目录=空迭代零操作（幂等）。同步 SDK，service 侧 `run_in_threadpool` 包装（与 put/get 同口径） |
| `dao.py` | **预计零改**——find_kb_by_id 已随行带出新列；无新查询路径（实现 agent 核对，若发现需要新动词回报主控） |
| `router.py` / `schemas.py` | 零改（契约无变更，KbVo 不暴露该列——内部实现） |

## 6. vectorstore 缓存与 drop 的易错点（本轮最隐蔽的坑）

`get_milvus` 是 `lru_cache` 单例工厂。**集合被 drop 后缓存实例仍存活**：旧实例持有已删集合的
字段表缓存，若直接复用它 add，既不会重建集合也可能按旧 schema 写入——§3.2 的「懒建自愈」与
§7 换维度 `_v2` 切换都依赖「drop 后同名可重新构造」，lru_cache 恰好阻断这一点。

对策：drop 不走 `get_milvus` 实例的 `drop()`，而是新增底层客户端辅助函数：

```python
def drop_collection(collection_name: str) -> None:
    """删集合并清空单例缓存：drop 后同名懒建重建依赖缓存清除（旧实例持已删集合
    的字段表缓存，复用即写废）。has 判断在前，drop 不存在集合的幂等重删安全。"""
    client = MilvusClient(uri=get_settings().milvus.uri)
    if client.has_collection(collection_name):
        client.drop_collection(collection_name)
    get_milvus.cache_clear()
```

`cache_clear` 全清的代价 = 各库实例下次访问时重新构造 + describe（一次 gRPC 往返），删库是低频
管理操作，可接受。lru_cache 无淘汰上限的实例增长问题：库量级小（本机 21 行、企业单环境典型百级），
每实例仅是薄包装，不设上限维持现状；plan 移交备忘登记「集合数过万时再评估缓存上限」。

## 7. 错误码（AI 段 5xxx 接续）

| 码 | 常量 | msg | 触发 |
|---|---|---|---|
| 5008（复用） | CODE_VECTOR_PURGE_FAILED | 向量清理失败，请重试 | 删库链 Milvus drop 失败（MySQL 未动，重删即重试，语义成立） |
| 5014（新） | CODE_VECTOR_WRITE_FAILED | 向量写入失败，请对该文件执行重新向量化 | 上传链阶段④ Milvus 写失败（MySQL 已提交；msg 引导幂等修复路径——重试上传会产生重复文件行） |

常量成对声明（沿 `CODE_X`/`MSG_X` 既有模式）接在 5013 之后；5014 后 AI 段下一可用 5015。
删库链 MinIO 前缀清失败**不转码**：S3Error 裸上抛全局 500（与上传链 MinIO put 失败现状对称；
MySQL 未动可整段重试）。

## 8. 存量处置（一次性运维，步骤进 plan 验收）

顺序依赖：**先 DDL 后部署**（旧代码不感知新列可继续跑；新代码 SELECT 带新列、表无列即报错）。

1. 执行增量脚本（§4，pymysql）——全行回填 `kb_{id}`；
2. 重启 cloud-base-ai（新代码上线）；
3. 活文件重嵌入重建：对唯一活文件（当前 id=1，库 id=20）调既有 `POST /ai/rag/kb/reembedding`——
   新代向量写入 `kb_20`，同文件的历代向量（含 test123 内该文件 102 活块对应向量）被删除清单收敛；
4. drop `test123`（pymilvus 一次性命令）——其余 462 软删孤儿向量随集合消失；
5. MinIO 孤儿对象清理：**按 deleted=1 文件行的 file_path 精确删，禁止按 kb/ 前缀批删**（前缀内
   混有活库对象，活文件 id=1 的原件在 `kb/20/` 下会被误删）——一次性脚本查行→逐对象 remove；
6. 验证：Milvus `list_collections` 只剩 `kb_20` 且 num_entities=102；MinIO `kb/` 前缀仅剩活文件
   对象；MySQL 全行 collection_name 非空。

## 9. 测试策略

- **单测（SQLite 内存替身 + monkeypatch，沿既有形态）**：create_kb 两步式断言行内列值；
  上传链用例组适配两阶段（既有 `_run_upload` 替身结构保持，FakeMilvus 按名返回不变）+ 新增
  5014 分支（Milvus 失败时文件行已在库、无向量）；重嵌入断言 get_milvus 收到行内列名；删库链
  断言调用顺序（MinIO 清 → Milvus drop → MySQL 软删）、drop 失败 5008 时 MySQL 未动、
  5004 分支零外部调用；storage.remove_prefix 空前缀/多对象幂等。
- **实体列集断言**：`test_rag_entities.py` 加 `collection_name`。
- **守护测试**：新方法 `current_session` 全在 @transactional 内、router 不碰 dao 等既有规则自然满足。
- **真连接冒烟**：扩 `scripts/smoke/smoke_milvus.py` 增场景 [5]：临时集合 A/B 隔离写读互不可见
  + `drop_collection` 后同名经 `get_milvus` 懒建重建可写（验证 §6 cache_clear 路径）。
- **真链路验收**（plan 步骤）：存量处置后按 §8.6 验证 + 删库端到端（建库→上传→删库→Milvus/
  MinIO/MySQL 三侧核验）。

## 10. 欠账关系（handoff 清账动作）

| handoff 条目 | 动作 |
|---|---|
| Milvus collection 名硬编码 test123 | **删**（本轮清偿） |
| 已删知识库/文件的孤儿向量与孤儿原件未清理 | **删**（存量已清 + 删库链增量治理已建） |
| 外部存储写入与 MySQL 写入不在同一事务 | **改写**为残余口径：上传链已两阶段化，失败面收敛为「MySQL 提交失败留 MinIO 孤儿对象（低概率）」+「Milvus 写失败=登记无向量（5014 重嵌入修复，可收敛）」；删库链外部清幂等可重试——残余登记保留 |
| （新增）kb 冗余计数从未维护 | `file_count`/`file_chunk_count` 上传链不更新、重算动词无消费方、KbVo 未暴露——待消费方出现再评估维护责任 |
| （新增）get_milvus lru_cache 无上限 | 集合数百级内无碍；集合数过万时评估缓存上限（§6） |

## 11. 移交备忘（下轮规划前必读）

- 未来**单文件删除端点**落地时：向量清理同构于重嵌入历代清单（`list_child_ids_by_file` 含软删
  行口径 + delete-by-ids），MinIO 删单个 file_path 对象，同事务软删文件行+块行——三存储清序参
  照本轮删库链（外部清在前、软删在后）。
- **换 embedding 维度**（`_v2` 指针切换）：UPDATE `collection_name = 'kb_{id}_v2'` → 对库内
  文件逐个跑重嵌入（「换模型原参刷新」是其既有拍板场景）→ 确认后 drop 旧集合。机制零代码，
  操作手册随真实需求出现再落。
- 检索轮（`retrieval.py`）接入时：检索入口从行内 `collection_name` 取集合（多库授权检索 =
  各授权库集合分别检索合并），孤儿向量「不进检索结果」的 handoff 预期已由本轮清偿。
