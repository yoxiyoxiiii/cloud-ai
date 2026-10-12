"""配置模块（spec D5）：pydantic-settings 最小集，仅 Nacos 注册相关字段。"""

from functools import lru_cache

from pydantic import BaseModel
from pydantic_settings import BaseSettings, SettingsConfigDict


class NacosSettings(BaseModel):
    """Nacos 注册参数（env 覆盖形态：CLOUD_BASE_AI_NACOS__*，嵌套分隔 "__"）。"""

    server_addr: str = "127.0.0.1:8848"
    namespace_id: str = ""  # 空 = public（SDK naming 自动转 "public"，封装统一空串入参）
    group_name: str = "DEFAULT_GROUP"
    service_name: str = "cloud-base-ai"
    ip: str = "127.0.0.1"  # 注册 IP，可覆盖（D3：不做网卡探测）


class Settings(BaseSettings):
    """应用配置根（env 前缀 CLOUD_BASE_AI_，嵌套分隔符 "__"——单 "_" 与内层字段名
    自身含下划线歧义，含下划线内层字段覆盖会静默失效，2026-10-12 实测后主控拍板 "__"）。"""

    nacos: NacosSettings = NacosSettings()
    app_port: int = 8010  # uvicorn 监听与注册实例端口同源

    model_config = SettingsConfigDict(env_prefix="CLOUD_BASE_AI_", env_nested_delimiter="__")


@lru_cache
def get_settings() -> Settings:
    """进程内单例。"""
    return Settings()
