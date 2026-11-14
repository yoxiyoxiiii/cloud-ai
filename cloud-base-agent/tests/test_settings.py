"""Agent 侧 Settings 断言：库名/端口/env_file 三处分叉（无新增配置段；外部 MCP
凭据不走字段收集，见 mcp/secrets.py）。公共段用例在 cloud-ai-common/tests/。
"""

from pathlib import Path

from cloud_base_agent.config import BASE_DIR, Settings


def test_db_defaults_cloud_ai_agent(monkeypatch) -> None:
    """agent 侧库名默认 cloud_ai_agent（双库拆分），其余默认本机事实值。"""
    for key in ("HOST", "PORT", "USER", "PASSWORD", "DATABASE"):
        monkeypatch.delenv(f"CLOUD_BASE_AI_DB__{key}", raising=False)
    s = Settings(_env_file=None)
    assert s.db.database == "cloud_ai_agent"
    assert s.db.host == "127.0.0.1"


def test_app_port_default_8012(monkeypatch) -> None:
    monkeypatch.delenv("CLOUD_BASE_AI_APP_PORT", raising=False)
    assert Settings(_env_file=None).app_port == 8012


def test_env_file_points_to_agent_project_root() -> None:
    """env_file 落点=本工程根（config.py 在 src/cloud_base_agent/ 下三层上=cloud-base-agent/）。"""
    assert BASE_DIR == Path(__file__).resolve().parents[1]
    assert Settings.model_config.get("env_file") == str(BASE_DIR / ".env")
