"""
stream 工具包
"""
import json


def to_sse_format(payload: dict[str, str]) -> str:
    """
    sse 协议。包装数据：
    格式：data:{"content": "见到"}
    :return:
    """
    # ensure_ascii=False：帧内中文可读，JSON 转义保证换行不破坏帧定界
    return f"data:{json.dumps(payload, ensure_ascii=False)}\n\n"
