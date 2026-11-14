"""Agent 服务配置：继承公共段（cloud_ai_common.CommonSettings），无新增配置段。

外部 MCP server 凭据不走 pydantic-settings 字段收集（全键名直读，见 mcp/secrets.py），
故本侧 config 只有库名/端口/env_file 三处分叉。模块底部向 common 注册 settings
provider——import 本模块即完成注册，common 的 db/redis/model 工厂经 provider 读到
本侧单例。
"""

import os
from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import SettingsConfigDict

from cloud_ai_common.config import CommonSettings, DatabaseSettings, set_settings_provider

# 注册名必填（库级无默认，漏配即构造期 ValidationError）：运行时由入口 app.py 的
# setdefault 提供；本模块级再 setdefault 一次兜测试链——测试 import config 不经入口。
os.environ.setdefault("CLOUD_BASE_AI_NACOS__SERVICE_NAME", "cloud-base-agent")

# 工程根绝对路径（src/cloud_base_agent/config.py 三层上 = cloud-base-agent/）。
# env_file 必须绝对路径：pydantic-settings 相对路径按进程 cwd 解析，起服命令 cwd
# 在仓库根会指错。mcp/secrets.py 的 .env 直读复用本常量（落点公式不变）。
BASE_DIR = Path(__file__).resolve().parents[2]


class AgentDatabaseSettings(DatabaseSettings):
    """agent 侧库名默认值（双库拆分：agent 八表在 cloud_ai_agent）。"""

    database: str = "cloud_ai_agent"


class Settings(CommonSettings):
    """Agent 服务配置根：env 前缀 CLOUD_BASE_AI_（沿用不改），嵌套分隔 "__"，
    env_file 指本工程 .env。"""

    db: AgentDatabaseSettings = Field(default_factory=AgentDatabaseSettings)
    app_port: int = 8012

    model_config = SettingsConfigDict(
        env_prefix="CLOUD_BASE_AI_",
        env_nested_delimiter="__",
        env_file=str(BASE_DIR / ".env"),  # 绝对路径随工程走
        env_file_encoding="utf-8",
    )


@lru_cache
def get_settings() -> Settings:
    """进程内单例（common 经 provider 取到的即本函数）。"""
    return Settings()


# 向 common 注册：import 本模块即生效（入口与服务模块都 import 本模块）。
set_settings_provider(get_settings)
