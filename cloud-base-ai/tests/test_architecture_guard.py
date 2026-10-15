"""架构守护测试：用 ast 静态扫描 src/，违反分层或约定即红。

对应 Java 侧 cloud-system 的 ArchitectureGuardTest，目的相同：把「口头规范」变成构建期的
硬约束，避免靠人工双审才发现。本文件只读源码文本，不 import 业务模块，
因此不会触发 Milvus、Nacos、MySQL 等外部连接，可在任何机器上稳定运行。

新增规则的准入标准：对当前代码必须全绿，并且对应一个真实发生过或明确会发生的错误。
规则的完整说明与例外写法见 .claude/skills/ai-spec/SKILL.md。
"""

import ast
from collections.abc import Iterator
from pathlib import Path

import pytest

SRC = Path(__file__).resolve().parents[1] / "src" / "cloud_base_ai"


def _py_files() -> list[Path]:
    return sorted(p for p in SRC.rglob("*.py") if "__pycache__" not in p.parts)


def _parse(path: Path) -> ast.Module:
    return ast.parse(path.read_text(encoding="utf-8"), filename=str(path))


def _rel(path: Path) -> str:
    return path.relative_to(SRC).as_posix()


def _imported_modules(tree: ast.Module) -> Iterator[tuple[str, int]]:
    """产出文件内每条 import 的完整模块名及行号。

    `from a.b import c` 同时产出 `a.b` 与 `a.b.c`，这样既能拦截
    `from cloud_base_ai.rag import dao`，也能拦截 `import cloud_base_ai.rag.dao`。
    """
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                yield alias.name, node.lineno
        elif isinstance(node, ast.ImportFrom) and node.module:
            yield node.module, node.lineno
            for alias in node.names:
                yield f"{node.module}.{alias.name}", node.lineno


def _violations(files: list[Path], is_bad) -> list[str]:
    """通用扫描：对每个 import 调用 is_bad(模块名)，收集违规项为「文件:行号 模块」。"""
    found: list[str] = []
    for path in files:
        for module, lineno in _imported_modules(_parse(path)):
            if is_bad(module):
                found.append(f"{_rel(path)}:{lineno} {module}")
    return found


# ---------- 规则 1：分层依赖单向 router → service → dao ----------


def test_router_does_not_import_dao_or_db() -> None:
    """router 不得直接碰 dao、entities、db 包，必须经 service。

    原因：事务边界在 service 的 @transactional 上，router 绕过 service
    直接调 dao 会拿不到会话，或者在没有事务的情况下写库。
    """
    routers = [p for p in _py_files() if p.name == "router.py"]
    assert routers, "没找到任何 router.py，扫描路径可能错了"
    bad = _violations(
        routers,
        lambda m: m.endswith(".dao") or m.endswith(".entities") or ".db" in m and m.startswith("cloud_base_ai.db"),
    )
    assert not bad, "router 直接依赖了持久层，应改为经 service 调用：\n" + "\n".join(bad)


def test_dao_does_not_import_upper_layers() -> None:
    """dao 是最底层，不得反向依赖 service 或 router。"""
    daos = [p for p in _py_files() if p.name == "dao.py"]
    assert daos
    bad = _violations(daos, lambda m: m.endswith(".service") or m.endswith(".router"))
    assert not bad, "dao 反向依赖了上层：\n" + "\n".join(bad)


def test_entities_do_not_import_upper_layers() -> None:
    """实体只描述表结构，不得依赖 dao、service、router。"""
    entities = [p for p in _py_files() if p.name == "entities.py"]
    assert entities
    bad = _violations(
        entities,
        lambda m: m.endswith(".dao") or m.endswith(".service") or m.endswith(".router"),
    )
    assert not bad, "entities 依赖了上层：\n" + "\n".join(bad)


# ---------- 规则 2：事务边界归 service，dao 永不提交 ----------


def test_dao_never_commits_or_begins_transactions() -> None:
    """dao 只能 flush，不得 commit、rollback、begin。

    原因：dao 方法会被嵌套在 service 的 @transactional 里，
    它自己提交会打破「任何异常整体回滚」的保证。
    """
    forbidden = {"commit", "rollback", "begin", "begin_nested"}
    bad: list[str] = []
    for path in [p for p in _py_files() if p.name == "dao.py"]:
        for node in ast.walk(_parse(path)):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute):
                if node.func.attr in forbidden:
                    bad.append(f"{_rel(path)}:{node.lineno} .{node.func.attr}()")
    assert not bad, "dao 里出现了事务控制调用，事务只能由 service 的 @transactional 管理：\n" + "\n".join(bad)


# ---------- 规则 3：service 层 catch 必须记日志 ----------


def test_service_except_blocks_log_error_or_reraise() -> None:
    """service 里的 except 块必须满足其一：记 error 日志，或原样 raise。

    对应 CLAUDE.md 通用约束第 4 条：catch 异常必须先记录根因再转业务异常，
    否则线上排查时看不到真实原因。
    例外：except 块第一条语句就是不带参数的 `raise` 视为透传，不要求日志。
    """
    bad: list[str] = []
    for path in [p for p in _py_files() if p.name == "service.py"]:
        for node in ast.walk(_parse(path)):
            if not isinstance(node, ast.ExceptHandler):
                continue
            first = node.body[0] if node.body else None
            if isinstance(first, ast.Raise) and first.exc is None:
                continue  # 纯透传
            logs_error = any(
                isinstance(n, ast.Call)
                and isinstance(n.func, ast.Attribute)
                and n.func.attr in {"error", "exception"}
                for n in ast.walk(node)
            )
            if not logs_error:
                bad.append(f"{_rel(path)}:{node.lineno}")
    assert not bad, "service 的 except 块既没记 error 日志也不是纯透传：\n" + "\n".join(bad)


# ---------- 规则 4：service 的公开 async 写方法必须有事务边界 ----------


def test_service_methods_touching_session_are_transactional() -> None:
    """函数体里调用了 current_session() 的 service 方法，必须带 @transactional。

    原因：current_session() 在没有事务装饰器时运行期才抛 RuntimeError，
    这条规则把它提前到构建期。
    """
    bad: list[str] = []
    for path in [p for p in _py_files() if p.name == "service.py"]:
        for fn in ast.walk(_parse(path)):
            if not isinstance(fn, ast.AsyncFunctionDef | ast.FunctionDef):
                continue
            uses_session = any(
                isinstance(n, ast.Call) and isinstance(n.func, ast.Name) and n.func.id == "current_session"
                for n in ast.walk(fn)
            )
            if not uses_session:
                continue
            decorators = {
                d.id if isinstance(d, ast.Name) else getattr(d, "attr", "") for d in fn.decorator_list
            }
            if "transactional" not in decorators:
                bad.append(f"{_rel(path)}:{fn.lineno} {fn.name}")
    assert not bad, "这些方法用了 current_session() 却没有 @transactional：\n" + "\n".join(bad)


# ---------- 规则 5：缓存装饰器必须来自标准库 ----------


def test_lru_cache_comes_from_functools() -> None:
    """lru_cache 必须从 functools 导入，不得用 anyio.functools 的同名函数。

    原因：anyio.functools.lru_cache 是为异步函数设计的，用在同步工厂上
    语义不同且容易误用。项目里的单例工厂（模型、引擎、向量库）都是同步函数。
    """
    bad = _violations(_py_files(), lambda m: m == "anyio.functools.lru_cache")
    assert not bad, "lru_cache 应从 functools 导入：\n" + "\n".join(bad)


# ---------- 规则 6：实体的每一列都要有 comment ----------


def test_entity_columns_have_comment() -> None:
    """entities.py 里每个 mapped_column(...) 调用都必须带 comment= 参数。

    对应 CLAUDE.md 通用约束第 1 条：DDL 每列必须有 COMMENT。
    审计与逻辑删除列定义在 db/base.py 的 Mixin 里，不在本规则范围内。
    """
    bad: list[str] = []
    for path in [p for p in _py_files() if p.name == "entities.py"]:
        for node in ast.walk(_parse(path)):
            if (
                isinstance(node, ast.Call)
                and isinstance(node.func, ast.Name)
                and node.func.id == "mapped_column"
                and not any(kw.arg == "comment" for kw in node.keywords)
            ):
                bad.append(f"{_rel(path)}:{node.lineno}")
    assert not bad, "这些列缺少 comment=：\n" + "\n".join(bad)


# ---------- 规则 7：不留调试输出 ----------


@pytest.mark.parametrize("name", ["print", "breakpoint"])
def test_no_debug_calls_left_in_src(name: str) -> None:
    """src 里不得残留 print() 或 breakpoint()，日志统一用 logging。

    例外：__init__.py 里 CLI 入口（cloud-base-ai 脚本）允许 print。
    """
    bad: list[str] = []
    for path in _py_files():
        if path.name == "__init__.py" and path.parent == SRC:
            continue
        for node in ast.walk(_parse(path)):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == name:
                bad.append(f"{_rel(path)}:{node.lineno}")
    assert not bad, f"src 里残留了 {name}()，请改用 logging：\n" + "\n".join(bad)
