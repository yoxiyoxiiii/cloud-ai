# -*- coding: utf-8 -*-
"""双库拆分一次性迁移：cloud_ai → cloud_ai_rag(5 表) + cloud_ai_agent(8 表)。

背景：AI 基座三工程拆分（共享库 cloud-ai-common + 服务 cloud-base-rag /
cloud-base-agent），两服务各配独立库名（.env DB__DATABASE）。本脚本把旧单库
cloud_ai 的 13 表按域复制到两个新库，旧库原样保留不删不改。

口径：
- 复制方式 CREATE TABLE ... LIKE + INSERT ... SELECT（DDL 连索引与自增一起带；
  LIKE 不复制外键——本库 DDL 本就无外键，语义无损）；
- 幂等防御：目标库同名表已存在即中止（防二次运行造成半覆盖）；
- 停机窗口：8011/8012 有监听即中止（复制期双写会撕裂行数断言）；
- 旧库表清单与 13 表名单不符即中止（防未知表漏迁）；
- 连接参数为本机事实默认（root 空密码，与共享库 DatabaseSettings 默认同源）；
  库名/表名是脚本内常量不是用户输入，标识符拼接无注入面，值无参数化必要。

用法（仓库根执行）：
C:/Users/jy-root/.local/bin/uv.exe run --project cloud-ai-common python cloud-ai-common/scripts/migrate_split_db.py [--dry-run]
"""
import socket
import sys

import pymysql

HOST, PORT, USER, PASSWORD = "127.0.0.1", 3306, "root", ""
SOURCE_DB = "cloud_ai"

RAG_TABLES = (
    "ai_knowledge_base",
    "ai_knowledge_base_files",
    "ai_kb_chunk",
    "ai_kb_grant",
    "ai_chat_session",
)
AGENT_TABLES = (
    "ai_agent",
    "ai_agent_session",
    "ai_agent_run_log",
    "ai_agent_tool",
    "ai_agent_mcp_server",
    "ai_tool",
    "ai_mcp_server",
    "ai_mcp_server_grant",
)
# 目标库 → 域表清单（两清单合计 13 表，与旧库 SHOW TABLES 精确比对）
TARGETS: dict[str, tuple[str, ...]] = {
    "cloud_ai_rag": RAG_TABLES,
    "cloud_ai_agent": AGENT_TABLES,
}

SERVICE_PORTS = (8011, 8012)


def fail(msg: str) -> None:
    """中止出口：打印原因后非零退出，绝不带半成品继续。"""
    print(f"ABORT: {msg}")
    sys.exit(1)


def assert_services_stopped() -> None:
    """停机检查：8011/8012 任一端口可连通即说明 AI 服务在跑，中止。"""
    for port in SERVICE_PORTS:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
            s.settimeout(1.0)
            if s.connect_ex(("127.0.0.1", port)) == 0:
                fail(f"端口 {port} 有服务监听（AI 服务未停），复制期双写会撕裂行数断言")


def show_tables(cur, db: str) -> set[str]:
    cur.execute(f"SHOW TABLES FROM `{db}`")
    return {row[0] for row in cur.fetchall()}


def check_and_plan(cur, dry_run: bool) -> list[tuple[str, str, str]]:
    """前置校验并产出复制计划 [(目标库, 表名, 源行数)]，全部只读。"""
    expected = set(RAG_TABLES) | set(AGENT_TABLES)
    actual = show_tables(cur, SOURCE_DB)
    if actual != expected:
        missing, extra = expected - actual, actual - expected
        fail(
            f"旧库 {SOURCE_DB} 表清单与 13 表名单不符（缺 {sorted(missing)} 多 {sorted(extra)}），"
            "可能有未知表漏迁，请先核对 DDL 基线"
        )

    plan: list[tuple[str, str, str]] = []
    for target_db, tables in TARGETS.items():
        existing = show_tables(cur, target_db) if target_db in _existing_dbs(cur) else set()
        clash = existing & set(tables)
        if clash:
            fail(f"目标库 {target_db} 已存在同名表 {sorted(clash)}（幂等防御：防二次运行半覆盖）")
        for table in tables:
            cur.execute(f"SELECT COUNT(*) FROM `{SOURCE_DB}`.`{table}`")
            plan.append((target_db, table, cur.fetchone()[0]))
    return plan


def _existing_dbs(cur) -> set[str]:
    """SHOW DATABASES 集合（目标库可能尚未建，先探测防 SHOW TABLES 报错）。"""
    cur.execute("SHOW DATABASES")
    return {row[0] for row in cur.fetchall()}


def run(dry_run: bool) -> None:
    assert_services_stopped()
    conn = pymysql.connect(
        host=HOST, port=PORT, user=USER, password=PASSWORD,
        database=SOURCE_DB, charset="utf8mb4",
    )
    try:
        with conn.cursor() as cur:
            plan = check_and_plan(cur, dry_run)
            print(f"[plan] source={SOURCE_DB} tables={len(plan)} "
                  f"(rag={len(RAG_TABLES)}, agent={len(AGENT_TABLES)})")
            if dry_run:
                for target_db, table, src_rows in plan:
                    print(f"[dry-run] CREATE TABLE `{target_db}`.`{table}` LIKE `{SOURCE_DB}`.`{table}`;")
                    print(f"[dry-run] INSERT INTO `{target_db}`.`{table}` SELECT * FROM `{SOURCE_DB}`.`{table}`;  -- {src_rows} rows")
                print("DRY RUN PASS（未写库）")
                return

            for target_db in TARGETS:
                cur.execute(
                    f"CREATE DATABASE IF NOT EXISTS `{target_db}` "
                    "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci"
                )
            for target_db, table, _ in plan:
                cur.execute(f"CREATE TABLE `{target_db}`.`{table}` LIKE `{SOURCE_DB}`.`{table}`")
            for target_db, table, _ in plan:
                cur.execute(f"INSERT INTO `{target_db}`.`{table}` SELECT * FROM `{SOURCE_DB}`.`{table}`")
            conn.commit()

            # 每表行数断言：源/目标必须一致，任一不符即整体失败（目标库留下但不影响重跑判断——
            # 幂等防御会在下次运行时被同名表中止，人工 DROP 后方可重来）
            for target_db, table, src_rows in plan:
                cur.execute(f"SELECT COUNT(*) FROM `{target_db}`.`{table}`")
                dst_rows = cur.fetchone()[0]
                status = "OK" if dst_rows == src_rows else "MISMATCH"
                print(f"[copy] {target_db}.{table}: {src_rows} -> {dst_rows} {status}")
                if dst_rows != src_rows:
                    fail(f"{target_db}.{table} 行数不一致（源 {src_rows} / 目标 {dst_rows}）")
            print(f"MIGRATE PASS（旧库 {SOURCE_DB} 原样保留）")
    finally:
        conn.close()


if __name__ == "__main__":
    run(dry_run="--dry-run" in sys.argv)
