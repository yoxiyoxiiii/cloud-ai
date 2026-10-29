"""deepagents 0.7.23 三项 POC（平台 Agent 管理轮，一次性实证脚本，不进单测）。

目的：为运行时组装选型提供装机版实证，三项结论回填设计文档 §3：
1. 工具裁剪终态——子类化 ChatOpenAI 覆写 bind_tools，捕获模型侧最终可见工具名清单，
   断言组装后恰为 {write_todos} ∪ 自定义工具（文件六工具/execute/task 全剥）。
   装机版事实（本 POC 首跑裁决）：设计原定的 FilesystemMiddleware(tools=[]) 被 0.7.23
   构造校验拒绝（read_file 必须在 allowlist 内），等价裁剪 = HarnessProfile 的
   excluded_tools（文件工具 + execute）+ 禁用通用子代理（task 随之下线）——
   _ToolExclusionMiddleware 把工具从模型请求剥除且调用面拒绝，双面一致。
   HarnessProfile 注册 key 对 ChatOpenAI 实例的解析形态亦在此裁决。
2. contextvar（请求 JWT）在 astream 流式任务链的传播性——通则 compiled agent 可跨请求
   缓存；不通则退为每请求组装。验证方式：同一个 compiled agent 跑两次，每次运行前设
   不同 contextvar 值，工具体内读到各自值即传播成立且缓存安全。
3. 多步流式形态——带一个 mock 工具跑真 astream，记录 metadata 键形态与中间轮文本行为
   （工具调用参数 chunk 是否被 isinstance(content, str) 天然过滤、中间轮文本与最终回答
   是否连续到达）。

运行方式（仓库根）：uv run --project cloud-base-ai python examples/agent_poc.py
前置：.env 配置 CLOUD_BASE_AI_LLM__API_KEY（真值不入日志）；输出全 ASCII（GBK 控制台坑）。
"""

import sys
from contextvars import ContextVar

from deepagents import (
    GeneralPurposeSubagentProfile,
    HarnessProfile,
    create_deep_agent,
    register_harness_profile,
)
from langchain.agents.middleware import TodoListMiddleware
from langchain_core.messages import AIMessageChunk, HumanMessage
from langchain_core.tools import tool
from langchain_openai import ChatOpenAI

from cloud_base_ai.config import get_settings

try:
    sys.stdout.reconfigure(encoding="utf-8")
except Exception:
    pass

# bind_tools 捕获：每次模型请求的工具名清单（模型侧最终可见面）
CAPTURED_TOOL_NAMES: list[list[str]] = []
# mock 工具体内读到的 contextvar 值（POC-2 的判据）
TOOL_SEEN_JWTS: list[str | None] = []
# 流式 metadata 键集样本（POC-3 记录备查）
SEEN_METADATA_KEYS: set[str] = set()

poc_jwt: ContextVar[str | None] = ContextVar("poc_jwt", default=None)

MOCK_TOOL_NAME = "poc_query_city_staff"


class ProbeChatOpenAI(ChatOpenAI):
    """捕获 bind_tools 收到的工具名清单——模型请求装配的最后一站，即最终可见面。"""

    def bind_tools(self, tools, **kwargs):  # type: ignore[override]
        names: list[str] = []
        for t in tools:
            if isinstance(t, dict):
                fn = t.get("function") if isinstance(t.get("function"), dict) else {}
                names.append(str(t.get("name") or fn.get("name") or "?"))
            else:
                names.append(str(getattr(t, "name", t)))
        CAPTURED_TOOL_NAMES.append(sorted(names))
        return super().bind_tools(tools, **kwargs)


@tool
async def poc_query_city_staff(city: str) -> str:
    """查询指定城市的驻场员工人数与名单（POC mock 工具，返回固定数据）。"""
    TOOL_SEEN_JWTS.append(poc_jwt.get())
    return f"{city} 驻场员工共 3 人：张三、李四、王五。"


def build_model() -> ProbeChatOpenAI:
    """按全局 .env 配置构造探针模型（构造零网络；key 真值绝不打印）。"""
    cfg = get_settings().llm
    return ProbeChatOpenAI(
        model=cfg.model,
        api_key=cfg.api_key,
        base_url=cfg.base_url,
        timeout=cfg.timeout,
        max_retries=cfg.max_retries,
        use_responses_api=False,  # DeepSeek 仅支持 chat completions，防误路由
    )


def register_trim_profile(model_name: str) -> str:
    """进程级一次性注册裁剪 profile：剥全部文件工具与 execute（excluded_tools，
    模型面与调度面双剥离），并禁用自动注入的通用子代理（task 工具随之下线）。

    key 取 openai:<model_name>——deepagents 对模型实例按「ls_provider:identifier」解析
    profile（ChatOpenAI 的 ls_provider 恒为 openai），精确 key 命中即生效。
    """
    key = f"openai:{model_name}"
    register_harness_profile(
        key,
        HarnessProfile(
            excluded_tools=frozenset(
                {"ls", "read_file", "write_file", "edit_file", "delete", "glob", "grep", "execute"}
            ),
            general_purpose_subagent=GeneralPurposeSubagentProfile(enabled=False),
        ),
    )
    return key


def build_agent(model: ProbeChatOpenAI):
    """平台 Agent 的目标组装形态：显式补回 write_todos；文件工具与 task 经 profile 剥离。"""
    return create_deep_agent(
        model=model,
        tools=[poc_query_city_staff],
        system_prompt="你是员工信息助手，回答驻场员工相关问题。",
        middleware=[
            TodoListMiddleware(),  # write_todos 多步规划（不在默认栈，显式补回）
        ],
    )


async def run_once(agent, jwt_value: str, tag: str) -> dict[str, object]:
    """单次流式运行：模拟服务形态——contextvar 在流式 generator 外层注入，
    generator 内起 astream（消费方任务与组装处任务不同）。返回观测记录。"""
    poc_jwt.set(jwt_value)
    text_segments: list[str] = []
    chunk_kinds: list[str] = []
    # 观测 ToolMessage（工具结果）形态：其 content 是非空 str，若仅按
    # isinstance(content, str) 放行会被误当 token 外发——验证收紧条件的必要性
    tool_msg_text_total = 0

    async def stream():
        nonlocal tool_msg_text_total
        # 服务侧 event_gen 同构：astream 在 generator 体内被消费
        async for chunk, metadata in agent.astream(
            {"messages": [HumanMessage(content="北京现在有几个驻场员工？请先用工具查询再回答。")]},
            stream_mode="messages",
        ):
            content = getattr(chunk, "content", "")
            kind = type(chunk).__name__
            chunk_kinds.append(kind)
            if kind == "ToolMessage" and isinstance(content, str):
                tool_msg_text_total += len(content)
            if isinstance(chunk, AIMessageChunk) and isinstance(content, str) and content:
                text_segments.append(content)  # 收紧后的放行条件：仅 AI 文本 token
            if isinstance(metadata, dict):
                SEEN_METADATA_KEYS.update(metadata.keys())
            yield

    consumed = 0
    async for _ in stream():
        consumed += 1

    return {
        "tag": tag,
        "segments": len(text_segments),
        "joined_len": len("".join(text_segments)),
        "chunk_kinds": sorted(set(chunk_kinds)),
        "tool_msg_text_len": tool_msg_text_total,
        "tool_calls": len(TOOL_SEEN_JWTS),
    }


def verdict(lines: list[tuple[bool, str]]) -> int:
    ok = True
    for passed, msg in lines:
        print(f"[{'OK' if passed else 'FAIL'}] {msg}")
        ok = ok and passed
    return 0 if ok else 1


async def main() -> int:
    cfg = get_settings().llm
    if not cfg.api_key:
        print("[SKIP] CLOUD_BASE_AI_LLM__API_KEY empty - cannot run LLM POC")
        return 1
    print(f"[INFO] model={cfg.model} base_url={cfg.base_url}")

    # 模型实例的 profile 解析形态（非 secret，可打印）
    from deepagents._models import get_model_identifier, get_model_provider
    model = build_model()
    identifier = get_model_identifier(model)
    provider = get_model_provider(model)
    print(f"[INFO] deepagents resolves instance -> provider={provider!r} identifier={identifier!r}")

    key = register_trim_profile(cfg.model)
    print(f"[INFO] harness profile registered under key={key!r}")

    agent = build_agent(model)
    print("[OK] agent assembled (TodoListMiddleware + profile excluded_tools + GP subagent off)")

    # POC-2 + POC-3：同一 compiled agent 连跑两次，每次运行前设不同 contextvar
    run_a = await run_once(agent, "jwt-POC-A", "A")
    run_b = await run_once(agent, "jwt-POC-B", "B")
    print(f"[INFO] run A: {run_a}")
    print(f"[INFO] run B: {run_b}")
    print(f"[INFO] metadata keys seen: {sorted(SEEN_METADATA_KEYS)}")

    # ---------- POC-1：裁剪终态 ----------
    final_sets = [set(names) for names in CAPTURED_TOOL_NAMES]
    expected = {MOCK_TOOL_NAME, "write_todos"}
    trimming_ok = bool(final_sets) and all(s == expected for s in final_sets)
    leaked = sorted(set().union(*final_sets) - expected) if final_sets else ["<no capture>"]
    poc1_lines = [
        (bool(final_sets), f"POC-1 bind_tools captured {len(final_sets)} model request(s)"),
        (trimming_ok,
         f"POC-1 final tool set == {{write_todos, {MOCK_TOOL_NAME}}} in every request; leaked={leaked}"),
    ]

    # ---------- POC-2：contextvar 传播 ----------
    # 判据：工具体内按运行先后读到 jwt-POC-A / jwt-POC-B（None/串包即不通）
    expected_jwts = ["jwt-POC-A", "jwt-POC-B"]
    poc2_ok = TOOL_SEEN_JWTS == expected_jwts
    poc2_lines = [
        (poc2_ok, f"POC-2 contextvar reached tool body per run: seen={TOOL_SEEN_JWTS}"),
        (poc2_ok, "POC-2 verdict: compiled agent cross-request cache IS SAFE (option: cache)"),
    ]
    if not poc2_ok:
        poc2_lines.append((False, "POC-2 fallback required: assemble per request (no cache)"))

    # ---------- POC-3：多步流式形态 ----------
    text_ok = run_a["joined_len"] > 0 and run_b["joined_len"] > 0
    tool_msg_seen = run_a["tool_msg_text_len"] > 0  # type: ignore[operator]
    poc3_lines = [
        (text_ok, f"POC-3 text streamed: A={run_a['segments']} segs, B={run_b['segments']} segs"),
        (run_a["tool_calls"] >= 1, f"POC-3 mock tool executed (total calls={len(TOOL_SEEN_JWTS)})"),
        (tool_msg_seen,
         f"POC-3 ToolMessage results ARE emitted by astream (text len A={run_a['tool_msg_text_len']}) "
         "-> gate MUST be isinstance(chunk, AIMessageChunk), not merely isinstance(content, str)"),
    ]

    print()
    print("==== POC verdicts ====")
    code1 = verdict(poc1_lines)
    code2 = verdict(poc2_lines)
    code3 = verdict(poc3_lines)
    return 1 if (code1 or code2 or code3) else 0


if __name__ == "__main__":
    import asyncio

    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    sys.exit(asyncio.run(main()))
