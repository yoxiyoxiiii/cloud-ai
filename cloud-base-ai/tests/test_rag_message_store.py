"""message_store 断言（FakeRedis 替身零网络）：升序往返 / limit 取尾 / 上限修剪 /
无 TTL / 空会话 / 删除幂等。

替身只实现 message_store 消费的五个命令（lpush/ltrim/llen/lrange/delete），语义对齐
redis-py：lpush 头插、ltrim 保留闭区间、delete 返回删除键数。
"""

import pytest

from cloud_base_ai.rag import message_store
from cloud_base_ai.rag.message_store import (
    MAX_MESSAGES_PER_SESSION,
    append_message,
    delete_messages,
    list_messages,
)


class FakeRedis:
    """单键 LIST 语义替身：内部以 list 模拟头插存储（索引 0 = 最新）。"""

    def __init__(self) -> None:
        self.data: dict[str, list[str]] = {}
        self.expire_calls: list[tuple[str, int]] = []  # 无 TTL 断言锚（应恒为空）

    def lpush(self, key: str, value: str) -> None:
        self.data.setdefault(key, []).insert(0, value)

    def ltrim(self, key: str, start: int, stop: int) -> None:
        items = self.data.get(key, [])
        stop_idx = None if stop == -1 else stop + 1  # redis stop 含端点；-1 = 末尾
        self.data[key] = items[start:stop_idx]

    def llen(self, key: str) -> int:
        return len(self.data.get(key, []))

    def lrange(self, key: str, start: int, stop: int) -> list[str]:
        items = self.data.get(key, [])
        stop_idx = None if stop == -1 else stop + 1
        return items[start:stop_idx]

    def delete(self, key: str) -> int:
        return 1 if self.data.pop(key, None) is not None else 0

    def expire(self, key: str, seconds: int) -> None:  # pragma: no cover - 仅记录调用
        self.expire_calls.append((key, seconds))


@pytest.fixture()
def fake(monkeypatch: pytest.MonkeyPatch) -> FakeRedis:
    fake = FakeRedis()
    monkeypatch.setattr(message_store, "get_redis_client", lambda: fake)
    return fake


def test_roundtrip_ascending(fake: FakeRedis) -> None:
    """读写往返：先落的问在回显数组前（时间升序），role/content 还原。"""
    append_message(1, "user", "你好")
    append_message(1, "assistant", "你好！有什么可以帮你？")
    msgs = list_messages(1)
    assert [(m.role, m.content) for m in msgs] == [
        ("user", "你好"), ("assistant", "你好！有什么可以帮你？")]


def test_limit_takes_tail(fake: FakeRedis) -> None:
    """limit=最近 N 条：取尾（最新侧），如 LLM 历史拼装只消费尾 history_limit 条。"""
    for i in range(5):
        append_message(1, "user", f"m{i}")
    msgs = list_messages(1, limit=2)
    assert [m.content for m in msgs] == ["m3", "m4"]


def test_over_limit_trims_oldest(fake: FakeRedis) -> None:
    """超上限恒 100 条：丢最旧，最新保留。"""
    for i in range(105):
        append_message(1, "user", f"m{i:03d}")
    msgs = list_messages(1)
    assert len(msgs) == MAX_MESSAGES_PER_SESSION
    assert msgs[0].content == "m005"  # 最旧 5 条被修剪
    assert msgs[-1].content == "m104"


def test_no_ttl_never_calls_expire(fake: FakeRedis) -> None:
    """无 TTL 决策锚：任何写入路径都不得设置过期（历史随会话生命周期存续）。"""
    append_message(1, "user", "hi")
    list_messages(1)
    delete_messages(1)
    assert fake.expire_calls == []


def test_empty_session_returns_empty(fake: FakeRedis) -> None:
    """空会话（建了没问）：回显空数组，非异常。"""
    assert list_messages(999) == []


def test_delete_idempotent(fake: FakeRedis) -> None:
    """删除幂等：键不存在再删 no-op；删后回显归空。"""
    append_message(1, "user", "hi")
    delete_messages(1)
    delete_messages(1)  # 二次删：键已不存在
    assert list_messages(1) == []


def test_sessions_isolated_by_key(fake: FakeRedis) -> None:
    """键按 session_id 隔离：两会话互不串。"""
    append_message(1, "user", "s1-msg")
    append_message(2, "user", "s2-msg")
    assert [m.content for m in list_messages(1)] == ["s1-msg"]
    assert [m.content for m in list_messages(2)] == ["s2-msg"]
    assert f"{message_store.RAG_MESSAGE_KEY_PREFIX}1" in fake.data
