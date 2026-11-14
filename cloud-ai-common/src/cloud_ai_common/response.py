"""统一返回 R<T>（spec D4 / 契约 §2）：字段与码值逐项对齐 Java cloud-common-core-starter。"""

from typing import Any, Generic, TypeVar

from pydantic import BaseModel

T = TypeVar("T")

CODE_SUCCESS: int = 200
CODE_PARAM_ERROR: int = 1001
CODE_BUSINESS_ERROR: int = 1002
CODE_SYSTEM_ERROR: int = 500

MSG_SUCCESS: str = "操作成功"
MSG_SYSTEM_ERROR: str = "系统异常，请稍后重试"


class R(BaseModel, Generic[T]):
    """HTTP 状态恒 200，业务结果看 body.code。"""

    code: int = CODE_SUCCESS
    msg: str = MSG_SUCCESS
    data: T | None = None

    @staticmethod
    def ok(data: T | None = None) -> "R[T]":
        r: R[T] = R(code=CODE_SUCCESS, msg=MSG_SUCCESS, data=data)
        return r

    @staticmethod
    def fail(msg: str, code: int = CODE_BUSINESS_ERROR) -> "R[Any]":
        """一个方法覆盖 Java R.fail(String) / R.fail(int, String) 两形态。"""
        return R(code=code, msg=msg, data=None)
