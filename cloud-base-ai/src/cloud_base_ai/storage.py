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

    def remove_prefix(self, prefix: str) -> int:
        """按前缀清空对象，返回删除数：list_objects(recursive=True) 遍历逐个 remove_object。

        空前缀/前缀无对象 = 空迭代零操作（幂等，删库整段重试安全——外部清先于
        MySQL 软删，重删时此步空清无害）。
        """
        removed = 0
        for obj in self._client.list_objects(self._bucket, prefix=prefix, recursive=True):
            self._client.remove_object(self._bucket, obj.object_name)
            removed += 1
        return removed

    def presigned_get_url(self, object_name: str, expires_seconds: int = 3600) -> str:
        """预签名下载直链（默认 1 小时；免凭证临时访问）。"""
        return self._client.presigned_get_object(
            self._bucket, object_name, expires=timedelta(seconds=expires_seconds),
        )


@lru_cache
def get_storage() -> MinioStorage:
    """进程内单例工厂（模式同 get_settings/get_milvus）。"""
    return MinioStorage()
