# cloud-ai-common

AI 基座共享底座库。三工程拆分（2026-11-14）后承载两侧服务共同依赖的约 840 行共享代码：

- `db/`：engine/session 工厂、`@transactional` 事务边界、Base/审计/软删 Mixin
- `response.py` / `exceptions.py` / `exception_handler.py`：统一返回 `R<T>` 与全局异常处理
- `security.py`：网关五头身份核心（Identity / contextvar / require_perms）
- `redis_client.py`：Redis Cluster 客户端（7000，AI 消息存储）
- `nacos_registration.py`：Nacos 临时实例注册生命周期
- `model_factory.py`：ChatOpenAI chat 模型单例工厂
- `schemas.py`：AiPageResult / VoTimeMixin 共享出参基座
- `config.py`：CommonSettings（五公共段）+ settings provider 注册点

本工程不独立运行服务，由兄弟工程以 uv path dependency（editable）引用：

```toml
[tool.uv.sources]
cloud-ai-common = { path = "../cloud-ai-common", editable = true }
```

无发布物与版本号语义，恒取本地源码；三工程被相对路径绑在同一目录结构内。
运维执行器脚本（Nacos 备份/恢复、双库迁移）在 `scripts/`，经 `uv run --project cloud-ai-common` 执行。
