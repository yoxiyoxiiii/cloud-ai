# -*- coding: utf-8 -*-
"""一次性落库脚本：审批通知模板种子（2026-11-08 审批通知轮）。

重放 cloud-base/scripts/sql/2026-11-08-approval-notify-integration.sql（两段：approval_remind id=2
内容升级 UPDATE + approval_result id=3 新增 INSERT NOT EXISTS），脚本幂等可重跑——第二次执行
UPDATE 同值重写、INSERT 被 NOT EXISTS 拦截（验收即跑两遍核对无重复无报错）。
落库后回查：内置模板 3 行、id=2 含任务到达五变量、id=3 含 resultText 且 level=0。
连接本机 root 空密码（CLAUDE.md「对 MySQL 读写中文一律 pymysql 优先」，charset utf8mb4）。
"""
import re
import sys
from pathlib import Path

import pymysql

SQL_FILE = (Path(__file__).resolve().parents[3]
            / "cloud-base" / "scripts" / "sql" / "2026-11-08-approval-notify-integration.sql")


def post_assert(cur) -> None:
    """后置断言：契约 §2 模板形态核对并打印"""
    cur.execute("SELECT id, code, type, level, is_builtin FROM cloud_system.sys_notify_template "
                "WHERE deleted = 0 ORDER BY id")
    templates = cur.fetchall()
    print("templates:", templates)
    assert len(templates) == 3, f"内置模板应 3 行，实际 {len(templates)}"

    cur.execute("SELECT content_template, variables_desc FROM cloud_system.sys_notify_template "
                "WHERE id = 2 AND deleted = 0")
    row = cur.fetchone()
    print("id=2 content:", row[0])
    print("id=2 variables_desc:", row[1])
    assert row is not None, "approval_remind 模板缺失"
    for var in ("${nickname}", "${applyUserName}", "${title}", "${businessTypeName}", "${taskName}"):
        assert var in row[0], f"id=2 内容缺变量 {var}"

    cur.execute("SELECT content_template, level FROM cloud_system.sys_notify_template "
                "WHERE id = 3 AND deleted = 0")
    row3 = cur.fetchone()
    print("id=3 content:", row3[0])
    print("id=3 level:", row3[1])
    assert row3 is not None, "approval_result 模板缺失"
    assert "${resultText}" in row3[0], "id=3 内容缺变量 ${resultText}"
    assert row3[1] == 0, f"id=3 level 应 0（普通），实际 {row3[1]}"

    cur.execute("SELECT COUNT(*) FROM cloud_system.sys_notify_template WHERE code = 'approval_result'")
    assert cur.fetchone()[0] == 1, "approval_result 出现重复行（INSERT 幂等失效）"


def split_statements(sql_text: str) -> list[str]:
    """按行剥掉 -- 注释后以分号切分语句（本文件无字符串内 ASCII 分号，中文分号仅在文案里）"""
    lines = [ln for ln in sql_text.splitlines() if not ln.strip().startswith("--")]
    statements = []
    for chunk in "\n".join(lines).split(";"):
        stmt = chunk.strip()
        if stmt:
            statements.append(stmt)
    return statements


def main() -> None:
    conn = pymysql.connect(host="127.0.0.1", port=3306, user="root", password="",
                           charset="utf8mb4", autocommit=True)
    try:
        with conn.cursor() as cur:
            for stmt in split_statements(SQL_FILE.read_text(encoding="utf-8")):
                if stmt.upper().startswith("USE"):
                    conn.select_db(re.sub(r"`", "", stmt.split(None, 1)[1]).strip())
                elif stmt.lower() == "commit":
                    continue
                else:
                    affected = cur.execute(stmt)
                    print(f"executed ({affected} rows):", stmt.split("\n")[0][:60])
            post_assert(cur)
            print("APPLY OK:", SQL_FILE.name)
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
