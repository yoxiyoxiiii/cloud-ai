"""storage.py 单元测试：FakeMinio 替身注入，不连真实 MinIO（真连接验证走
scripts/smoke/smoke_minio.py）。覆盖 bucket 幂等、写读往返、删除、预签名透传、单例工厂。
"""

from collections.abc import Callable
from types import SimpleNamespace
from datetime import timedelta

import pytest

from cloud_base_ai import storage
from cloud_base_ai.config import MinIOSettings
from cloud_base_ai.storage import MinioStorage, get_storage

BUCKET = "test-bucket"


class FakeResponse:
    """get_object 替身响应：read 一次性给全量；close/release_conn 记归还（连接必还）。"""

    def __init__(self, data: bytes) -> None:
        self._data = data
        self.closed = False
        self.released = False

    def read(self) -> bytes:
        return self._data

    def close(self) -> None:
        self.closed = True

    def release_conn(self) -> None:
        self.released = True


class FakeMinio:
    """Minio 客户端替身：对象存内存 dict，全部调用入参记录进 calls 供断言。"""

    def __init__(self) -> None:
        self.buckets: set[str] = set()
        self.objects: dict[tuple[str, str], bytes] = {}
        self.calls: list[tuple[str, dict]] = []
        self.last_response: FakeResponse | None = None

    def _record(self, method: str, **kwargs: object) -> None:
        self.calls.append((method, kwargs))

    def _calls_of(self, method: str) -> list[dict]:
        return [kw for m, kw in self.calls if m == method]

    def bucket_exists(self, bucket_name: str) -> bool:
        self._record("bucket_exists", bucket=bucket_name)
        return bucket_name in self.buckets

    def make_bucket(self, bucket_name: str) -> None:
        self._record("make_bucket", bucket=bucket_name)
        self.buckets.add(bucket_name)

    def put_object(self, bucket_name: str, object_name: str, data, length=None,
                   content_type=None) -> None:
        self._record("put_object", bucket=bucket_name, object_name=object_name,
                     length=length, content_type=content_type)
        self.objects[(bucket_name, object_name)] = data.read()

    def get_object(self, bucket_name: str, object_name: str) -> FakeResponse:
        self._record("get_object", bucket=bucket_name, object_name=object_name)
        resp = FakeResponse(self.objects[(bucket_name, object_name)])
        self.last_response = resp
        return resp

    def remove_object(self, bucket_name: str, object_name: str) -> None:
        self._record("remove_object", bucket=bucket_name, object_name=object_name)
        self.objects.pop((bucket_name, object_name), None)

    def list_objects(self, bucket_name: str, prefix: str | None = None,
                     recursive: bool = False) -> list[SimpleNamespace]:
        self._record("list_objects", bucket=bucket_name,
                     prefix=prefix, recursive=recursive)
        names = sorted(name for b, name in self.objects
                       if b == bucket_name and name.startswith(prefix or ""))
        return [SimpleNamespace(object_name=n) for n in names]

    def presigned_get_object(self, bucket_name: str, object_name: str, expires=None) -> str:
        self._record("presigned_get_object", bucket=bucket_name,
                     object_name=object_name, expires=expires)
        return f"http://fake-minio/{bucket_name}/{object_name}"


@pytest.fixture
def make_storage(monkeypatch: pytest.MonkeyPatch) -> Callable[[FakeMinio], MinioStorage]:
    """构造注入替身客户端的 MinioStorage，并钉死配置段（不依赖本地 .env）。"""
    settings = SimpleNamespace(minio=MinIOSettings(bucket=BUCKET))
    monkeypatch.setattr(storage, "get_settings", lambda: settings)

    def _make(fake: FakeMinio) -> MinioStorage:
        return MinioStorage(client=fake)

    return _make


def test_ensure_bucket_idempotent(make_storage) -> None:
    """bucket 不存在则创建；已存在不重复创建。"""
    fake = FakeMinio()
    s = make_storage(fake)
    s.ensure_bucket()
    assert fake.buckets == {BUCKET}
    s.ensure_bucket()
    assert len(fake._calls_of("make_bucket")) == 1


def test_put_get_roundtrip(make_storage) -> None:
    """put_bytes→get_bytes 内容往返一致；length/content_type 透传；响应连接归还。"""
    fake = FakeMinio()
    s = make_storage(fake)
    data = b"hello minio"
    s.put_bytes("a/b.txt", data, content_type="text/plain")
    put_kw = fake._calls_of("put_object")[0]
    assert put_kw["bucket"] == BUCKET
    assert put_kw["length"] == len(data)
    assert put_kw["content_type"] == "text/plain"
    assert s.get_bytes("a/b.txt") == data
    assert fake.last_response is not None
    assert fake.last_response.closed and fake.last_response.released


def test_put_bytes_default_content_type(make_storage) -> None:
    """未显式给 content_type 时默认二进制流类型。"""
    fake = FakeMinio()
    s = make_storage(fake)
    s.put_bytes("bin.dat", b"\x00\x01")
    assert fake._calls_of("put_object")[0]["content_type"] == "application/octet-stream"


def test_remove_object(make_storage) -> None:
    """remove 后对象从存储中消失。"""
    fake = FakeMinio()
    s = make_storage(fake)
    s.put_bytes("gone.txt", b"bye")
    s.remove("gone.txt")
    assert (BUCKET, "gone.txt") not in fake.objects


def test_remove_prefix_deletes_all_under_prefix(make_storage) -> None:
    """前缀内对象全删、返回删除数；前缀外对象不动。"""
    fake = FakeMinio()
    s = make_storage(fake)
    s.put_bytes("kb/20/a.pdf", b"a")
    s.put_bytes("kb/20/b.pdf", b"b")
    s.put_bytes("kb/21/c.pdf", b"c")  # 前缀外：不动
    removed = s.remove_prefix("kb/20/")
    assert removed == 2
    assert (BUCKET, "kb/20/a.pdf") not in fake.objects
    assert (BUCKET, "kb/20/b.pdf") not in fake.objects
    assert (BUCKET, "kb/21/c.pdf") in fake.objects
    assert fake._calls_of("list_objects")[0]["recursive"] is True


def test_remove_prefix_empty_prefix_zero_calls(make_storage) -> None:
    """空前缀但无对象 = 空迭代零 remove 调用、不报错（幂等）。"""
    fake = FakeMinio()
    s = make_storage(fake)
    assert s.remove_prefix("") == 0
    assert fake._calls_of("remove_object") == []


def test_remove_prefix_missing_prefix_noop(make_storage) -> None:
    """前缀不存在（无任何对象落在其下）= 零删除、不报错。"""
    fake = FakeMinio()
    s = make_storage(fake)
    s.put_bytes("kb/20/a.pdf", b"a")
    assert s.remove_prefix("kb/999/") == 0
    assert fake._calls_of("remove_object") == []
    assert (BUCKET, "kb/20/a.pdf") in fake.objects


def test_presigned_get_url_passes_expires(make_storage) -> None:
    """presigned_get_url 把有效期透传给 SDK 并返回其 URL。"""
    fake = FakeMinio()
    s = make_storage(fake)
    url = s.presigned_get_url("x.txt", expires_seconds=120)
    kw = fake._calls_of("presigned_get_object")[0]
    assert kw["expires"] == timedelta(seconds=120)
    assert url == f"http://fake-minio/{BUCKET}/x.txt"


def test_get_storage_singleton(monkeypatch: pytest.MonkeyPatch) -> None:
    """get_storage 两次调用返回同一实例（lru_cache 单例）。"""
    settings = SimpleNamespace(minio=MinIOSettings(bucket=BUCKET))
    monkeypatch.setattr(storage, "get_settings", lambda: settings)
    get_storage.cache_clear()
    try:
        assert get_storage() is get_storage()
    finally:
        get_storage.cache_clear()
