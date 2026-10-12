"""R<T> 码值断言（计划 Task 3 Step 1 / spec D4 表逐项）。"""

from cloud_base_ai.response import MSG_SUCCESS, R


def test_ok_default() -> None:
    r = R.ok()
    assert r.code == 200
    assert r.msg == MSG_SUCCESS
    assert r.data is None


def test_ok_with_data() -> None:
    r = R.ok("x")
    assert r.code == 200
    assert r.data == "x"
    assert r.msg == "操作成功"


def test_fail_default_code() -> None:
    r = R.fail("bad")
    assert r.code == 1002
    assert r.msg == "bad"
    assert r.data is None


def test_fail_custom_code() -> None:
    r = R.fail("bad", 500)
    assert r.code == 500
    assert r.data is None
