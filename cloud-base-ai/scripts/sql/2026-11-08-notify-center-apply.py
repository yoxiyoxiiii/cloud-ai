# -*- coding: utf-8 -*-
"""一次性落库脚本：站内消息通知中心（notify 域）三表 + 种子（2026-11-08 通知中心轮）。

重放 cloud-base/scripts/sql/2026-11-08-notify-center.sql（四段：三表 DDL / 内置模板 2 条 /
菜单 19 段 5 行 / xxl-job 任务 id 11），前置断言不满足立即终止（固定主键不可重放，防撞库）。
落库后回查四组后置断言：三表存在、菜单 19 段 5 行、模板 2 行、xxl_job_info id 11 + admin 绑定 5 行。
连接本机 root 空密码（CLAUDE.md「对 MySQL 读写中文一律 pymysql 优先」，charset utf8mb4 参数化）。
"""
import re
import sys
from pathlib import Path

import pymysql

SQL_FILE = (Path(__file__).resolve().parents[3]
            / "cloud-base" / "scripts" / "sql" / "2026-11-08-notify-center.sql")


def pre_assert(cur) -> None:
    """前置断言：三表不存在、菜单 19 段空闲（含墓碑）、xxl_job id 11 空闲"""
    cur.execute("SELECT table_name FROM information_schema.tables "
                "WHERE table_schema = 'cloud_system' AND table_name LIKE 'sys_notify%'")
    tables = [r[0] for r in cur.fetchall()]
    assert not tables, f"前置断言失败：notify 三表已存在 {tables}（增量不可重放）"

    cur.execute("SELECT id FROM cloud_system.sys_menu WHERE id IN (19,196,197,198,199)")
    ids = [r[0] for r in cur.fetchall()]
    assert not ids, f"前置断言失败：菜单 id 已占用（含墓碑）{ids}"

    cur.execute("SELECT id FROM xxl_job.xxl_job_info WHERE id = 11")
    assert not cur.fetchone(), "前置断言失败：xxl_job_info id=11 已存在"


def post_assert(cur) -> None:
    """后置断言：逐组核对计划 B1 验收口径并打印"""
    cur.execute("SELECT table_name FROM information_schema.tables "
                "WHERE table_schema = 'cloud_system' AND table_name LIKE 'sys_notify%' ORDER BY table_name")
    print("tables:", [r[0] for r in cur.fetchall()])

    cur.execute("SELECT id, name, perms, type FROM cloud_system.sys_menu "
                "WHERE id IN (19,196,197,198,199) AND deleted = 0 ORDER BY id")
    menus = cur.fetchall()
    print("menu rows:", len(menus), menus)
    assert len(menus) == 5, f"菜单 19 段应 5 行，实际 {len(menus)}"

    cur.execute("SELECT id, code, name, type, level, is_builtin FROM cloud_system.sys_notify_template "
                "WHERE deleted = 0 ORDER BY id")
    templates = cur.fetchall()
    print("builtin templates:", templates)
    assert len(templates) == 2, f"内置模板应 2 行，实际 {len(templates)}"

    cur.execute("SELECT id, executor_handler, schedule_conf, trigger_status "
                "FROM xxl_job.xxl_job_info WHERE id = 11")
    job = cur.fetchone()
    print("xxl_job id 11:", job)
    assert job is not None and job[1] == "notifyCleanJobHandler", "xxl_job 任务 id 11 缺失或 handler 不符"

    cur.execute("SELECT COUNT(*) FROM cloud_system.sys_role_menu WHERE role_id = 1 "
                "AND menu_id IN (19,196,197,198,199)")
    bound = cur.fetchone()[0]
    print("admin bindings:", bound)
    assert bound == 5, f"admin 绑定应 5 行，实际 {bound}"


def split_statements(sql_text: str) -> list[str]:
    """按行剥掉 -- 注释后以分号切分语句（本文件无字符串内分号，中文全角分号亦不含于语句）"""
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
            pre_assert(cur)
            for stmt in split_statements(SQL_FILE.read_text(encoding="utf-8")):
                if stmt.upper().startswith("USE"):
                    conn.select_db(re.sub(r"`", "", stmt.split(None, 1)[1]).strip())
                elif stmt.lower() == "commit":
                    continue
                else:
                    cur.execute(stmt)
            post_assert(cur)
            print("APPLY OK:", SQL_FILE.name)
    finally:
        conn.close()


if __name__ == "__main__":
    sys.exit(main())
