"""冒烟专用外部 MCP server（fastmcp http transport）：两个工具名刻意避开内置
SYSTEM 名下工具（uk_tool_key 跨 server 全局唯一——同名挂他库会被同步冲突规则
跳过，外部形态的「新增草稿 → 审核 → 绑定 → 真跑」链路就无工具可用）。

不做鉴权：凭据链路的真验证由 smoke_mcp_server_mgmt.py 对 cloud-system /mcp 的
test-config 段承担（那边有真实双主体过滤器）；本 server 只提供「有独立命名工具
的可连端点」。由 smoke_mcp_server_mgmt.py 按需启动/回收，独立运行仅用于手工调试：

    uv run --project cloud-base-ai python cloud-base-ai/scripts/smoke/smoke_external_mcp_server.py [port]

端点 http://127.0.0.1:<port>/mcp（fastmcp 4.1.0 streamable_http_path 默认值）。
"""

import json
import sys

from fastmcp import FastMCP

mcp = FastMCP(name="SMOKEEXT")


@mcp.tool
def smoke_query_orders(page_num: int = 1) -> str:
    """查询订单列表（冒烟固定数据）。"""
    return json.dumps({"total": 2, "page": page_num,
                       "rows": [{"orderId": "E2E-1"}, {"orderId": "E2E-2"}]})


@mcp.tool
def smoke_query_stats() -> str:
    """查询平台访问统计（冒烟固定数据）。"""
    return json.dumps({"visits": 42})


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9399
    mcp.run(transport="http", host="127.0.0.1", port=port)
