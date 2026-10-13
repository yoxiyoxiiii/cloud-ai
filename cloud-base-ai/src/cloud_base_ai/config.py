"""配置模块：pydantic-settings，Nacos 注册（R11 D5）+ LLM 段（2026-10-13 AI 对话轮 D1）。"""

from functools import lru_cache
from pathlib import Path

from pydantic import BaseModel
from pydantic_settings import BaseSettings, SettingsConfigDict

# 工程根绝对路径（src/cloud_base_ai/config.py 三层上 = cloud-base-ai/）。
# env_file 必须绝对路径：pydantic-settings 相对路径按进程 cwd 解析，起服命令 cwd 在仓库根会指错。
BASE_DIR = Path(__file__).resolve().parents[2]


class NacosSettings(BaseModel):
    """Nacos 注册参数（env 覆盖形态：CLOUD_BASE_AI_NACOS__*，嵌套分隔 "__"）。"""

    server_addr: str = "127.0.0.1:8848"
    namespace_id: str = ""  # 空 = public（SDK naming 自动转 "public"，封装统一空串入参）
    group_name: str = "DEFAULT_GROUP"
    service_name: str = "cloud-base-ai"
    ip: str = "127.0.0.1"  # 注册 IP，可覆盖（D3：不做网卡探测）


class LLMSettings(BaseModel):
    """LLM 接入参数（spec D1）：api_key 真值仅存本地 .env，不入库不入档不入日志。

    env 覆盖形态：CLOUD_BASE_AI_LLM__API_KEY / __BASE_URL / __MODEL / __TIMEOUT /
    __MAX_RETRIES / __HISTORY_LIMIT；优先级 真实 env > .env > 默认值。
    """

    api_key: str = ""  # 默认空 = 未配置态（调用时报 5002，不阻断启动——同注册降级哲学）
    base_url: str = "https://api.deepseek.com"  # OpenAI 兼容端点，非 secret 可入库
    model: str = "deepseek-flash"
    timeout: float = 60.0  # 单次 LLM 请求超时（秒，D11）
    max_retries: int = 1  # SDK 内建重试次数（D11：过重放大流式挂起，取 1）
    history_limit: int = 20  # 服务层历史截断（条，D10：防 context 爆量与费用滥用）


class Settings(BaseSettings):
    """应用配置根（env 前缀 CLOUD_BASE_AI_，嵌套分隔符 "__"——单 "_" 与内层字段名
    自身含下划线歧义，含下划线内层字段覆盖会静默失效，2026-10-12 实测后主控拍板 "__"）。
    """

    nacos: NacosSettings = NacosSettings()
    llm: LLMSettings = LLMSettings()
    app_port: int = 8010  # uvicorn 监听与注册实例端口同源

    model_config = SettingsConfigDict(
        env_prefix="CLOUD_BASE_AI_",
        env_nested_delimiter="__",
        env_file=str(BASE_DIR / ".env"),  # 绝对路径随工程走（D1 关键坑）
        env_file_encoding="utf-8",
    )


@lru_cache
def get_settings() -> Settings:
    """进程内单例。"""
    return Settings()
