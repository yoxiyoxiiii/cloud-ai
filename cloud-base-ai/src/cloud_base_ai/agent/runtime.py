"""平台 Agent 运行时：按库内配置组装 DeepAgent（裁剪内置工具 + MCP 工具包装器 + 进程内缓存）。

三件事：
1. 内置工具裁剪——deepagents 默认注入文件六工具/execute/task，经 HarnessProfile 的
   excluded_tools 剥除（模型面与调度面双剥离），显式补回 TodoListMiddleware 的
   write_todos 多步规划。profile key 按「ls_provider:identifier」解析，ChatOpenAI
   实例恒为 openai:<model_name>（装机版 0.7.23 实证，见 examples/agent_poc.py）。
2. MCP 工具包装器——按 ai_tool 注册表行构造 StructuredTool：args_schema 由
   input_schema（JSON Schema）经 pydantic create_model 动态生成（服务端定义参数面，
   模型不能自造参数）；协程体经 contextvar 取当前运行用户 JWT 调 MCP 通道，
   结果三分支渲染（正常 / 权限拒绝 / 其它错误——错误文本回模型自救不终断运行），
   每次调用同步记入审计收集器。
3. compiled agent 缓存——contextvar 在 astream 流式任务链的传播性已实证（同
   examples/agent_poc.py），compiled agent 可跨请求安全复用；按
   （agent_id → (config_version, agent)）缓存，版本不匹配重建覆盖，上限 32 条 LRU
   逐最旧（管理面规模冗余充足）。

安全红线：system_prompt 为管理员静态模板原样传入（不渲染任何变量）；JWT/审计收集器
经 contextvar 注入（请求开始 set，同 db/tx.py 会话模式），真值绝不进日志。
"""

import json
import logging
from collections import OrderedDict
from collections.abc import Sequence
from contextvars import ContextVar
from typing import Any

from deepagents import (
    GeneralPurposeSubagentProfile,
    HarnessProfile,
    create_deep_agent,
    register_harness_profile,
)
from langchain.agents.middleware import TodoListMiddleware
from langchain_core.tools import StructuredTool
from pydantic import BaseModel, ConfigDict, create_model

from cloud_base_ai.agent.entities import AiAgent, AiTool
from cloud_base_ai.exceptions import BusinessException
from cloud_base_ai.mcp import client as mcp_client
from cloud_base_ai.models import model_factory

logger = logging.getLogger(__name__)

# 编排兜底码（契约 §5：5018 智能体运行失败）——runtime 是组装层，码在本次声明、
# run_service 复用；LLM/MCP 既有码（5001/5002/5009/5010）不在此重复声明，透传即可
CODE_AGENT_RUN_FAILED: int = 5018

# MCP 权限拒绝的模型可见文案（不透出服务端原始前缀文本）
MSG_PERMISSION_DENIED_TO_MODEL = "当前用户无权执行该操作"

# 当前运行用户的 JWT（请求开始由 run_service 注入；POC 已验证经 astream 任务链传进工具体）
run_jwt: ContextVar[str | None] = ContextVar("agent_run_jwt", default=None)
# 当前运行的工具调用审计收集器（list[dict]；工具体逐次 append，run_service 收口落库）
run_tool_calls: ContextVar[list[dict] | None] = ContextVar(
    "agent_run_tool_calls", default=None
)

# ---------- 内置工具裁剪（进程级一次性注册） ----------

# 剥除的默认内置工具：文件六工具 + execute（shell）；
# task（通用子代理）随 GeneralPurposeSubagentProfile(enabled=False) 下线
_EXCLUDED_BUILTIN_TOOLS = frozenset(
    {"ls", "read_file", "write_file", "edit_file", "delete", "glob", "grep", "execute"}
)

_trim_profile_registered: set[str] = set()


def ensure_trim_profile(model_name: str) -> None:
    """为指定模型名注册裁剪 profile（幂等，进程内同名只注册一次）。

    key 形态 openai:<model_name>：deepagents 对模型实例按「ls_provider:identifier」
    解析 profile，ChatOpenAI 的 ls_provider 恒为 openai，注册该精确 key 即命中。
    """
    if model_name in _trim_profile_registered:
        return
    register_harness_profile(
        f"openai:{model_name}",
        HarnessProfile(
            excluded_tools=_EXCLUDED_BUILTIN_TOOLS,
            general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False),
        ),
    )
    _trim_profile_registered.add(model_name)
    logger.info("trim harness profile registered for model=%s", model_name)


# ---------- 入参 schema 动态构造（JSON Schema → pydantic 模型） ----------

# MVP 支持的 JSON Schema 类型映射；现有两工具仅 string/integer
_JSON_TYPE_MAP: dict[str, type] = {
    "string": str,
    "integer": int,
    "number": float,
    "boolean": bool,
}


def build_args_schema(tool_row: AiTool) -> type[BaseModel]:
    """按注册表 input_schema 构造 pydantic 入参模型（服务端定义参数面）。

    支持 type（string/integer/number/boolean）+ required + default；min/max 等
    约束项 MVP 不映射（面收紧留给后续轮）。不支持的类型抛 5018——注册表配错属
    组装期失败，由运行编排收口（此时尚无工具调用可记）。
    """
    try:
        schema = json.loads(tool_row.input_schema)
    except (json.JSONDecodeError, TypeError) as e:
        raise BusinessException(
            f"工具 {tool_row.tool_key} 的 input_schema 不是合法 JSON", CODE_AGENT_RUN_FAILED
        ) from e
    properties: dict = schema.get("properties") or {}
    required = set(schema.get("required") or [])
    fields: dict[str, Any] = {}
    for name, spec in (properties or {}).items():
        if not isinstance(spec, dict):
            raise BusinessException(
                f"工具 {tool_row.tool_key} 的入参 {name} 定义不合法", CODE_AGENT_RUN_FAILED
            )
        type_name = spec.get("type")
        if type_name not in _JSON_TYPE_MAP:
            raise BusinessException(
                f"工具 {tool_row.tool_key} 的入参 {name} 类型不支持：{type_name!r}",
                CODE_AGENT_RUN_FAILED,
            )
        py_type = _JSON_TYPE_MAP[spec["type"]]
        if name in required:
            fields[name] = (py_type, ...)
        elif "default" in spec:
            fields[name] = (py_type, spec["default"])
        else:
            fields[name] = (py_type | None, None)
    # extra=forbid：模型自造参数面外的字段直接校验失败（参数面由服务端定义的红线）
    return create_model(
        f"{tool_row.tool_key}_args",
        __config__=ConfigDict(extra="forbid"),
        **fields,
    )


def _content_text(result: Any) -> str:
    """取 MCP 结果首块文本（cloud-system converter 统一 JSON 字符串输出）。"""
    content = getattr(result, "content", None) or []
    return getattr(content[0], "text", "") if content else ""


# ---------- MCP 工具包装器 ----------


def build_mcp_tool(tool_row: AiTool) -> StructuredTool:
    """按注册表行构造 MCP 工具（name=tool_key 注入模型；协程体桥接 MCP 通道）。

    结果三分支：正常 → content 文本返回模型；isError 且 PERMISSION_DENIED 前缀 →
    固定文案「当前用户无权执行该操作」（模型转述，不透出服务端原文）；其它 isError /
    通道异常 → 错误文本回模型自救（不终断运行，工具失败由模型自行决策下一步）。
    每次调用同步 append 审计收集器（contextvar，未注入时静默跳过——组装自检场景）。
    """

    async def _arun(**kwargs: Any) -> str:
        calls = run_tool_calls.get()
        entry: dict = {"tool_key": tool_row.tool_key, "arguments": kwargs,
                       "ok": True, "error": None}
        outcome = ""
        jwt = run_jwt.get()
        if not jwt:
            outcome = "工具调用失败：用户凭证缺失"
            entry["ok"], entry["error"] = False, "用户凭证缺失"
        else:
            try:
                result = await mcp_client.call_tool(jwt, tool_row.tool_key, kwargs)
            except BusinessException as e:
                # 通道层失败（MCP 未配置/调用失败等）：文本回模型，审计记失败摘要
                outcome = f"工具调用失败：{e.msg}"
                entry["ok"], entry["error"] = False, f"工具调用失败：{e.msg}"
            else:
                text = _content_text(result)
                if not getattr(result, "is_error", False):
                    outcome = text
                elif mcp_client.is_permission_denied(result):
                    outcome = MSG_PERMISSION_DENIED_TO_MODEL
                    entry["ok"], entry["error"] = False, MSG_PERMISSION_DENIED_TO_MODEL
                else:
                    outcome = f"工具执行出错：{text}"
                    entry["ok"], entry["error"] = False, text
        if calls is not None:
            calls.append(entry)
        return outcome

    return StructuredTool(
        name=tool_row.tool_key,
        description=tool_row.description,
        args_schema=build_args_schema(tool_row),
        coroutine=_arun,
    )


# ---------- 组装与缓存 ----------

_AGENT_CACHE_LIMIT = 32
# agent_id → (config_version, compiled_agent)；LRU 逐最旧
_agent_cache: OrderedDict[int, tuple[int, Any]] = OrderedDict()


def _model_name_of(model: Any) -> str:
    """从模型实例取名（profile key 用）——ChatOpenAI 暴露 model_name/model 两属性。"""
    return str(getattr(model, "model_name", None) or getattr(model, "model", ""))


def assemble_agent(agent_row: AiAgent, tool_rows: Sequence[AiTool]) -> Any:
    """按配置行组装 DeepAgent（零网络：ChatOpenAI 构造与 profile 注册均不触网）。

    system_prompt 原样传入（静态模板，不渲染变量）；工具面 = 勾选的 MCP 包装器 +
    write_todos（经 middleware 补回）；文件/shell/子代理经 profile 剥离。
    """
    model = model_factory.get_chat_model(temperature=float(agent_row.temperature))
    ensure_trim_profile(_model_name_of(model))
    return create_deep_agent(
        model=model,
        tools=[build_mcp_tool(t) for t in tool_rows],
        system_prompt=agent_row.system_prompt,
        middleware=[TodoListMiddleware()],
    )


def get_agent(agent_row: AiAgent, tool_rows: Sequence[AiTool]) -> Any:
    """取 compiled agent：缓存命中（config_version 相同）直接复用，否则组装并覆盖缓存。

    contextvar 跨请求传播性已实证，compiled agent 复用安全；编辑使 config_version+1
    → 版本不匹配 → 重建。同步字典操作无 await，单事件循环内原子，免锁。
    """
    cached = _agent_cache.get(agent_row.id)
    if cached is not None and cached[0] == agent_row.config_version:
        _agent_cache.move_to_end(agent_row.id)
        return cached[1]
    agent = assemble_agent(agent_row, tool_rows)
    _agent_cache[agent_row.id] = (agent_row.config_version, agent)
    while len(_agent_cache) > _AGENT_CACHE_LIMIT:
        _agent_cache.popitem(last=False)
    return agent


def reset_cache() -> None:
    """清空 compiled agent 缓存（测试隔离用）。"""
    _agent_cache.clear()
