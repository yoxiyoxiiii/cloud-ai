# AI 基座 MinIO 对象存储封装实施计划（2026-10-16，纯后端 B 级）

> **For agentic workers:** 本计划按任务顺序执行，步骤用 checkbox 跟踪。
> 需求原文：AI 服务加上文件上传的服务，使用对象存储 MinIO；粒度：封装为对象或工具即可。
> 即：**只交付可复用存储工具层**——零新端点、零新表、零契约、零前端、不接既有 KB 上传链路。

**Goal**: cloud-base-ai 新增 MinIO 官方 SDK 薄封装（工具对象 + 配置段 + 单测 + 真连接冒烟），未来任何业务轮（如文件级管理）引之即用。

**Architecture**: 单文件 `src/cloud_base_ai/storage.py`（`MinioStorage` 类 + `get_storage()` lru_cache 单例工厂，模式对齐 `get_milvus`/`get_settings`）；配置进 `config.py` 新 `MinIOSettings` 段（env 前缀 `CLOUD_BASE_AI_MINIO__`）。基础设施复用用户既有 `minio` Docker 容器（现 Exited，`docker start` 即活）。

**Tech Stack**: minio 官方 Python SDK（同步客户端；本轮无调用方，异步接入时 `run_in_threadpool` 包装属未来轮）。

---

## 基础设施事实（2026-10-16 摸底）

用户既有容器 `minio`（镜像 `cgr.dev/chainguard/minio:latest`，数据卷 `minio_minio-data:/data`，cmd `server /data --address :9000 --console-address :9001`），宿主端口映射 **9100→9000（API）/ 9101→9001（控制台）**，凭证在容器 env `MINIO_ROOT_USER/MINIO_ROOT_PASSWORD`（root 用户 minioadmin + 随机密码）。等价 docker run 记档（密码不入档）：

```bash
docker run -d --name minio -p 9100:9000 -p 9101:9001 \
  -e MINIO_ROOT_USER=minioadmin -e MINIO_ROOT_PASSWORD=<部署期随机值> \
  -v minio_minio-data:/data cgr.dev/chainguard/minio:latest \
  server /data --address :9000 --console-address :9001
```

注：9000 被本机其他 Docker 代理占用、9090/9091 是 langfuse 栈的 minio（别动）——**本工程用 9100/9101，勿改**。

## 设计定案（不重议）

1. **SDK**：官方 `minio>=7.2`（非 boto3——直连 MinIO 无 S3 兼容层抽象必要）。
2. **配置哲学**：endpoint/bucket/secure 是本机基础设施事实默认值（对齐 DB 段）；access_key/secret_key 默认空、真值仅本地 `.env`（对齐 LLM api_key 哲学）。本轮无启动期消费方——凭证空不阻断 uvicorn 启动，天然满足。
3. **异常策略**：工具层不吞不译，`S3Error` 直接上抛；未来业务 service 层接入手册=catch 记日志后转 AI 段错误码（接续 5005 起）。
4. **bucket 口径**：应用 bucket `cloud-ai` 由 `ensure_bucket()` 幂等创建，创建后常驻（不随冒烟删除）。
5. **越界即停**：不接 KB 上传链、不做端点/契约、不动 cloud-web、不动 test_architecture_guard.py（守护红线）、不动 dev-services.sh（MinIO 无运行链路依赖，属基础设施不受该脚本管理）。

---

### Task 1: 起容器 + 加依赖

- [ ] **Step 1.1**: `docker start minio` → `netstat -ano | grep LISTENING | grep :9100` 有监听 → `curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:9100/minio/health/live` = 200。控制台 `http://127.0.0.1:9101`（记档即可，不验收）。
- [ ] **Step 1.2**: `C:/Users/jy-root/.local/bin/uv.exe add --project cloud-base-ai "minio>=7.2"` → pyproject dependencies 出现 minio、uv.lock 更新。
- [ ] **Step 1.3**: 凭证写入 `.env`（值以 `docker inspect minio --format '{{range .Config.Env}}{{println .}}{{end}}'` 输出为准；**真值绝不进代码/文档/日志**）：

```dotenv
# ---- MinIO 对象存储（凭证真值仅本地 .env，不入库不入档）----
CLOUD_BASE_AI_MINIO__ENDPOINT=127.0.0.1:9100
CLOUD_BASE_AI_MINIO__ACCESS_KEY=minioadmin
CLOUD_BASE_AI_MINIO__SECRET_KEY=<docker inspect 查得的真实密码>
CLOUD_BASE_AI_MINIO__BUCKET=cloud-ai
```

### Task 2: 配置段（config.py + .env.example + test_settings）

- [ ] **Step 2.1**: `config.py` 在 `MilvusSettings` 之后新增段并挂进 `Settings`（`minio: MinIOSettings = MinIOSettings()`）：

```python
class MinIOSettings(BaseModel):
    """MinIO 对象存储连接参数：本机基础设施默认端口；凭证为部署期自设值，
    真值仅本地 .env（同 LLM api_key 哲学，默认空）。本轮无启动期消费方，空凭证不阻断启动。

    env 覆盖形态：CLOUD_BASE_AI_MINIO__ENDPOINT / __ACCESS_KEY / __SECRET_KEY /
    __BUCKET / __SECURE。
    """
```

```python
    endpoint: str = "127.0.0.1:9100"  # host:port 无协议（minio SDK 入参格式），容器映射 9100→9000
    access_key: str = ""
    secret_key: str = ""
    bucket: str = "cloud-ai"
    secure: bool = False  # 本机 http；TLS 部署置 True
```

- [ ] **Step 2.2**: `.env.example` 末尾追加（**占位值，非真值**，风格对齐既有 DB 段注释块）：

```dotenv
# ---- MinIO 对象存储（endpoint/bucket 默认本机事实值；凭证真值仅本地 .env）----
# CLOUD_BASE_AI_MINIO__ENDPOINT=127.0.0.1:9100
# CLOUD_BASE_AI_MINIO__ACCESS_KEY=
# CLOUD_BASE_AI_MINIO__SECRET_KEY=
# CLOUD_BASE_AI_MINIO__BUCKET=cloud-ai
```

- [ ] **Step 2.3**: `tests/test_settings.py` 按该文件既有 env 覆盖测试风格新增：默认值断言（endpoint/bucket/secure）+ 一例 `CLOUD_BASE_AI_MINIO__BUCKET` env 覆盖生效断言。

### Task 3: 存储工具对象（storage.py）

- [ ] **Step 3.1**: 新建 `src/cloud_base_ai/storage.py` 全文如下：

```python
"""对象存储封装（MinIO）：官方 SDK 薄封装。当前无业务调用方——未来业务轮引之即用，
接入手册：异步路由用 run_in_threadpool 包装（SDK 为同步客户端）；S3Error 在业务
service 层 catch 记日志后转 AI 段错误码（工具层不吞不译）。"""

from __future__ import annotations

import io
from datetime import timedelta
from functools import lru_cache

from minio import Minio

from cloud_base_ai.config import get_settings


class MinioStorage:
    """MinIO 存储工具对象：bucket 幂等就绪 + 对象写/读/删/预签名读。"""

    def __init__(self, client: Minio | None = None) -> None:
        """client 可注入（单测替身）；缺省按配置段构造真实客户端。"""
        settings = get_settings().minio
        self._bucket = settings.bucket
        self._client = client if client is not None else Minio(
            endpoint=settings.endpoint,
            access_key=settings.access_key,
            secret_key=settings.secret_key,
            secure=settings.secure,
        )

    def ensure_bucket(self) -> None:
        """bucket 幂等就绪：不存在则创建；对象写入前调用一次。"""
        if not self._client.bucket_exists(self._bucket):
            self._client.make_bucket(self._bucket)

    def put_bytes(self, object_name: str, data: bytes,
                  content_type: str = "application/octet-stream") -> None:
        """字节内容直传（BytesIO 定长写；同名对象覆盖为 MinIO 既有语义）。"""
        self._client.put_object(
            self._bucket, object_name, io.BytesIO(data),
            length=len(data), content_type=content_type,
        )

    def get_bytes(self, object_name: str) -> bytes:
        """整对象读回（小文件口径；流式读取待真实接入方出现再定）。"""
        response = self._client.get_object(self._bucket, object_name)
        try:
            return response.read()
        finally:
            response.close()
            response.release_conn()

    def remove(self, object_name: str) -> None:
        """删除对象；不存在时 S3Error 上抛，由调用方决定是否忽略。"""
        self._client.remove_object(self._bucket, object_name)

    def presigned_get_url(self, object_name: str, expires_seconds: int = 3600) -> str:
        """预签名下载直链（默认 1 小时；免凭证临时访问）。"""
        return self._client.presigned_get_object(
            self._bucket, object_name, expires=timedelta(seconds=expires_seconds),
        )


@lru_cache
def get_storage() -> MinioStorage:
    """进程内单例工厂（模式同 get_settings/get_milvus）。"""
    return MinioStorage()
```

### Task 4: 单元测试（tests/test_storage.py）

- [ ] **Step 4.1**: 新建 `tests/test_storage.py`：`FakeMinio` 替身类（记录调用、内存 dict 存对象、get_object 返回带 close/release_conn 的假响应）注入 `MinioStorage(client=fake)`，覆盖：

- ensure_bucket：bucket 不存在→make_bucket 被调；存在→不调（幂等）
- put_bytes → get_bytes 往返一致；put_object 收到 length=len(data) 与 content_type
- remove 后对象消失
- presigned_get_url 透传 expires（fake 返回定长字符串断言）
- `get_storage()` 两次调用同对象（lru_cache 单例）

风格对齐 tests/ 既有文件（纯 pytest 函数式、中文 docstring 断言意图）。

### Task 5: 真连接冒烟（scripts/smoke/smoke_minio.py）

- [ ] **Step 5.1**: 新建冒烟脚本（模式对齐 `smoke_milvus.py`：文件头写清前提/运行方式/清理口径；UTF-8 stdout；独立 main() 返回码；环境不通给友好提示退出码 2）：

流程：`get_storage()` → ensure_bucket → `put_bytes(f"e2e_smoke_minio_{ts}", b"...")` → get_bytes 内容一致 → presigned_get_url → `urllib.request.urlopen(url).read()` HTTP 实取内容一致 → remove → 再 get_bytes 期望抛 S3Error(NoSuchKey)。对象名带时间戳、结束即删；`cloud-ai` bucket 创建后**保留**（应用 bucket）。判定失败逐条打印，冒烟退出码 0/1/2 同 smoke_milvus。

- [ ] **Step 5.2**: 工程根跑 `C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python scripts/smoke/smoke_minio.py`（或等价 cd 形式）→ 输出「冒烟通过」。

### Task 6: 收尾核查

- [ ] **Step 6.1**: 全量测试：`cd cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run pytest` 全绿（含新 test_storage、test_settings 增断言；**必须工程根跑**——别处跑 rootdir 错位 conftest 失效）。
- [ ] **Step 6.2**: 自查：`.env` 真值未出现在任何 git 跟踪文件（`git status` + grep 新增文件）；守护测试零改动；`docker ps` minio 容器 Up（**基础设施常驻，收尾不 stop**）。
- [ ] **Step 6.3**: 实施报告：文件清单、pytest 计数、冒烟真实输出、容器状态。

---

## 验收标准

1. 全量 pytest 绿；smoke_minio 真连接全链路通过（put/get 一致、presign HTTP 200 内容一致、remove 后 NoSuchKey）。
2. 凭证真值仅存 `.env`（untracked）；.env.example/plan/代码/日志零真值。
3. 守护测试、cloud-web、KB 上传链路零触碰；minio 容器留存运行。

## 移交备忘

- 未来业务接入（文件级管理/上传链改造）时：异步包装用 run_in_threadpool；S3Error→AI 段错误码接续 5005 起；KB 原始文件存 MinIO 的 object_name 规范（如 `kb/{kb_id}/file/{file_id}`）届时随契约定。
