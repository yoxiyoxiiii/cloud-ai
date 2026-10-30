"""MCP server 凭据解析（.env 键引用，真值不入库不入档不入日志）。

键结构 CLOUD_BASE_AI_MCP__SERVERS__<NAME>__<KEY>=<真值>（<NAME>=server name，<KEY>=管理员
自定段如 TOKEN/API_KEY）；DB 的 credential_headers_json 只存键名。解析优先级：
真实环境变量 > .env 文件（进程内缓存一次，改 .env 须重启生效）。

不走 pydantic-settings 字段收集——嵌套 dict 子字段对 "__" 分隔的环境变量不收集
（2026-10-30 本机实测负结论），故用全键名直读（os.environ 先查、dotenv_values 兜底）。
冒烟脚本借「真实 env 优先」向进程注入临时凭据，无需改 .env。
"""

import os
import re
from functools import cache

from dotenv import dotenv_values

from cloud_base_ai.config import BASE_DIR

# 命名空间护栏：只允许引用 MCP server 凭据段的键——防把 LLM api_key 等其他敏感
# 环境变量注入出站请求头造成凭据外泄（违例在管理面入参校验即拒，1001）
CREDENTIAL_KEY_PATTERN = re.compile(
    r"^CLOUD_BASE_AI_MCP__SERVERS__[A-Z0-9_]+__[A-Z0-9_]+$"
)

CODE_MCP_CREDENTIAL_MISSING: int = 5026
MSG_MCP_CREDENTIAL_MISSING: str = "MCP凭据未配置"


@cache
def _env_file_values() -> dict[str, str | None]:
    """.env 文件键值直读（进程内缓存一次——.env 不热载，改值须重启进程）。"""
    return dict(dotenv_values(BASE_DIR / ".env"))


def resolve(full_key: str) -> str | None:
    """按键名解析凭据真值：真实环境变量优先，.env 文件兜底；两处均无返回 None。

    调用方对 None 的处置按场景分：管理面测试/同步/调用期转 5026 业务码（不阻断启动）。
    """
    value = os.environ.get(full_key)
    if value is not None:
        return value
    return _env_file_values().get(full_key)
