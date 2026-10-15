---
name: ai-spec
description: AI 基座（cloud-base-ai，Python/FastAPI）开发规范基准——分层依赖、实体/DAO/Service/Router 模板、事务口径（@transactional）、错误码与异常转译、测试姿势、接入外部系统（Milvus/Ollama/MySQL）的检查清单。凡 cloud-base-ai 的后端任务（新增表/新增端点/改 DAO/接入外部系统/写测试）均应触发本技能；Java 服务用 backend-spec，不用本技能。
---

# AI 基座开发规范（cloud-base-ai）

本技能是 cloud-base-ai 的编码约定集中地，对应 Java 侧的 `/backend-spec`。机械可检查的规则由 `cloud-base-ai/tests/test_architecture_guard.py` 强制，写错构建即红；本技能补充「守护测试查不到、但常踩坑」的约定。

**与 CLAUDE.md 冲突时以 CLAUDE.md 为准。** 注释与文档须遵守 SOP「文档与注释可读性标准」：只写做什么和为什么，决策代号（D1/方案B 等）不裸引用。

## 一、分层与依赖方向

```
router  →  service  →  dao  →  entities
 (HTTP)    (编排+事务)  (SQL)    (表结构)
```

| 层 | 职责 | 禁止 |
|---|---|---|
| router | 入参校验、调用 service、包装 `R<T>` | import dao / entities / db（守护测试检查） |
| service | 业务编排、事务边界、异常转译 | 直接写 SQL |
| dao | 只写查询与 flush | commit / rollback / begin（守护测试检查）；import service/router |
| entities | 表结构映射 | import dao/service/router |

外部系统（Milvus、模型、Nacos）的封装放 `rag/` 下独立模块（如 `vectorstore.py`），由 service 调用，不进 dao。

## 二、实体（entities.py）

- 继承 `Base`，按需叠加 `AuditMixin`（审计四列）和 `SoftDeleteMixin`（逻辑删除）。
- **每个 `mapped_column` 必须带 `comment=`**（守护测试检查）。Mixin 里的列不在此列。
- 状态类字段在实体内部建嵌套枚举，名字以 `Enum` 结尾，**字段类型保持 `int`/`str`**，不存枚举实例：

```python
class ChunkTypeEnum(IntEnum):
    PARENT = 0
    CHILD = 1

    @classmethod
    def of(cls, value: int) -> "AiKbChunk.ChunkTypeEnum | None":
        try:
            return cls(value)
        except ValueError:
            return None
```

- 表结构以 `cloud-base/scripts/sql/cloud_ai.sql` 为准；改实体必须同步改 DDL，并更新对应的列集断言测试（`tests/test_rag_entities.py`）。
- **改字段名时全仓搜一遍旧名**，包括测试里的构造参数。

## 三、DAO（dao.py）

模块级 `async def`，第一个参数固定是 `session: AsyncSession`。方法命名沿用动词集：`find` 单查、`save` 新增、`update` 修改、`delete` 软删、`list` 列表、`page_list` 分页、`count` 计数。

| 写操作 | 做法 | 原因 |
|---|---|---|
| 新增 | `fill_create_audit` → `add` → `flush` → `refresh` | async 下 flush 后直接读 server 生成列会触发隐式查询并报 `MissingGreenlet`，`refresh` 是显式合法路径 |
| 修改 | `fill_update_audit` → `merge` → `flush` | |
| 删除 | `UPDATE SET deleted=1` + 审计两值，返回 `rowcount` | 软删；配合 `synchronize_session=False` |

读操作每个都显式带 `deleted == NORMAL` 过滤。分页用 `_clamp_page`（上限 200，对齐 Java 的 maxLimit）。**dao 不转译异常**，`IntegrityError` 原样上抛，由 service 转业务码。

## 四、事务（db/tx.py）

事务边界只在 service 方法上，用 `@transactional`。语义对齐 Spring 的 REQUIRED：

- 正常返回提交；**抛任何异常都回滚**（与 Java 默认只回滚运行时异常不同）；
- 已在事务内再调用 `@transactional` 方法，自动合并进外层事务；
- 方法内用 `current_session()` 取会话，传给 dao；漏装饰器时它抛 `RuntimeError`，守护测试会在构建期拦住。

```python
@transactional
async def create_xxx(req: CreateXxxReq) -> int:
    session = current_session()
    if await dao.find_xxx_by_name(session, req.name) is not None:
        raise BusinessException(MSG_XXX_EXISTS, CODE_XXX_EXISTS)
    try:
        row = await dao.save_xxx(session, Xxx(name=req.name))
    except IntegrityError as e:
        logger.error("create_xxx duplicate: %s: %s", type(e).__name__, e)
        raise BusinessException(MSG_XXX_EXISTS, CODE_XXX_EXISTS) from e
    return row.id
```

**查重 + 唯一键兜底两层都要有**：先查重挡常规重名；并发或「软删记录仍占着唯一键」时靠 `IntegrityError` 兜底，先 `logger.error` 记根因，再转业务码。

**事务只管数据库。** Milvus、文件服务这类外部写入不在事务内，失败时数据库回滚但外部已写入的数据不会撤销。接入这类外部写入时，必须在方案里写明补偿策略（先写哪边、失败如何清理），并登记到 `docs/handoff/ai.md`。

## 五、异常与错误码

- 业务失败抛 `BusinessException(msg, code)`——**msg 在前，code 在后**，与 Java 习惯相反，别写反。
- 全局 handler 转成 HTTP 200 + `R{code, msg}`。
- AI 段错误码是 **5xxx**，在 service 模块顶部以常量声明（`CODE_X` 与 `MSG_X` 成对），接续上一个号分配。当前已用到 5004。
- except 块要么记 `logger.error` 再转业务异常，要么第一行就是 `raise` 透传（守护测试检查）。
- 错误信息里**不得含 API key 和上游原始响应**。

## 六、Router

- 路由级权限依赖：`APIRouter(prefix=..., dependencies=[Depends(require_perms("ai:xxx:action"))])`。权限标识 `ai:<实体>:<动作>`，需要先在 `sys_menu` 加种子。
- 返回统一 `R[T]`，两行式：先接结果再 `return R.ok(x)`。
- 入参出参用 pydantic 对象，不用裸 dict。流式端点（SSE）是已记档的例外。
- **漏写路由级依赖不会有任何报错，请求会直接放行**——新增或修改 router 后，测试里必须有「无权限返回 403」的用例。

## 七、测试

- 单测不连真实外部系统：数据库用 SQLite 内存替身（`tests/db_testing.py`）。
- service 层测试用 `make_service_session_factory`，并 `monkeypatch.setattr(tx, "get_session_factory", ...)`。
- 异步测试：模块顶部 `pytestmark = pytest.mark.anyio`。
- 写操作的测试用 `async with s.begin():` 包住，读操作如果后面还要 `begin` 也要包，否则隐式事务悬挂。
- 测试数据名带 `e2e_` 前缀。
- 每条新增的 dao 动词至少一个用例；软删、审计、唯一键撞重必须有用例。
- **改完代码跑全量 `pytest`，不要只跑自己改的文件**。

## 八、接入外部系统（Milvus / Ollama / 新数据库）

单测的内存替身覆盖不到真实系统的类型与方言差异。接入时必须：

1. 查装机版本的源码或文档确认关键行为，**不凭记忆**。例如 langchain-milvus 的主键类型由 `auto_id` 决定：`auto_id=False` 建 VARCHAR 主键（要求传字符串 id），`auto_id=True` 建 INT64 主键（传字符串会报类型错）。同一个 collection 名第一次建表时的参数会固定表结构，改参数后要先 `drop()`。
2. 单例工厂用 **`functools.lru_cache`**，不要用 `anyio.functools.lru_cache`（守护测试检查）。
3. 附一个可单独运行的真连接冒烟脚本，放 `cloud-base-ai/scripts/smoke/`，真跑一次并把输出写进报告。
4. 连接信息走 `config.py` 的配置对象，不在代码里写死地址。

## 九、检查清单

- [ ] 分层方向对：router 没 import dao；dao 没 commit？
- [ ] 每个 `mapped_column` 有 `comment=`？DDL 同步改了？列集断言测试同步改了？
- [ ] dao 读路径都带 `deleted` 过滤，写路径走审计公共函数？
- [ ] service 写方法有 `@transactional`？查重 + `IntegrityError` 兜底两层都有？
- [ ] except 块记了 `logger.error` 或纯透传？
- [ ] 新增错误码接续编号，常量成对声明？
- [ ] router 有权限依赖，且有 403 用例？
- [ ] 用到外部系统写入：补偿策略写进方案，欠账登记进 `docs/handoff/ai.md`？
- [ ] 全量 `pytest` 全绿（含 `test_architecture_guard.py`）？
- [ ] 注释无裸代号、无意识流长段？
