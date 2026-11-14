"""业务异常（对齐 Java BusinessException）：全局处理器转 R.fail，HTTP 恒 200。"""


class BusinessException(Exception):
    """业务校验/处理失败；code 默认 1002（1xxx 通用段，零新码段分配）。"""

    def __init__(self, msg: str, code: int = 1002) -> None:
        super().__init__(msg)
        self.msg = msg
        self.code = code
