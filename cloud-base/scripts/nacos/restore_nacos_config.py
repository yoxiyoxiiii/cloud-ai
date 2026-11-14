"""从本地备份恢复 Nacos 配置到 MySQL（nacos_config 库）。

配合 backup_nacos_config.py 产出的快照使用。安全约束：
  - 恢复前要求 nacos 容器处于停止状态（运行中直写库会与 Nacos 内存缓存不一致，
    须先 `docker stop nacos`，恢复后再 `docker start nacos` 冷加载）；
  - 本脚本只恢复 config_info（与灰度行若有）；his_config_info 历史不参与；
  - stdout 只打印键名与行数，不打印配置内容。

用法：
  uv run --project cloud-ai-common python cloud-base/scripts/nacos/restore_nacos_config.py [--snapshot <时间戳>] [--dry-run]
默认从 current/ 镜像恢复；--snapshot 指定 snapshots/ 下某次快照。
"""

import hashlib
import json
import subprocess
import sys
from pathlib import Path

import pymysql

MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = 3306
MYSQL_USER = "root"
MYSQL_PASSWORD = ""
DB_NAME = "nacos_config"
CONTAINER = "nacos"

CLOUD_BASE_DIR = Path(__file__).resolve().parents[2]
BACKUP_ROOT = CLOUD_BASE_DIR / "nacos-local"

INSERT_BASE = ["data_id", "group_id", "tenant_id", "content", "md5"]
INSERT_OPTIONAL = ["type", "src_user", "src_ip", "app_name", "c_desc", "c_use",
                   "effect", "c_schema", "gmt_create", "gmt_modified"]


def tenant_of(dir_name: str) -> str:
    """目录名还原租户：default 目录对应空租户（2.x 兼容副本）。"""
    return "" if dir_name == "default" else dir_name


def nacos_container_running() -> bool:
    try:
        r = subprocess.run(
            ["docker", "inspect", "-f", "{{.State.Running}}", CONTAINER],
            capture_output=True, text=True, timeout=15)
    except (OSError, subprocess.SubprocessError):
        return False  # docker 不可用即无法判定运行态，按未运行处理
    return r.returncode == 0 and r.stdout.strip().lower() == "true"


def table_exists(conn, table: str) -> bool:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT COUNT(*) FROM information_schema.tables "
            "WHERE table_schema = %s AND table_name = %s", (DB_NAME, table))
        return cur.fetchone()[0] > 0


def present_cols(conn, table: str, wanted: list) -> list:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema = %s AND table_name = %s", (DB_NAME, table))
        have = {r[0] for r in cur.fetchall()}
    return [c for c in wanted if c in have]


def upsert_config(cur, cols, e, content, src_dir):
    """单条 upsert：唯一键 (data_id, group_id, tenant_id) 命中则更新内容与指纹。"""
    col_sql = ", ".join(f"`{c}`" for c in cols)
    ph = ", ".join(["%s"] * len(cols))
    updates = ", ".join(f"`{c}` = VALUES(`{c}`)" for c in ("content", "md5", "type") if c in cols)
    if "gmt_modified" in cols:
        updates += ", `gmt_modified` = NOW()"
    if "src_user" in cols:
        updates += ", `src_user` = 'restore-script'"
    sql = (f"INSERT INTO config_info ({col_sql}) VALUES ({ph}) "
           f"ON DUPLICATE KEY UPDATE {updates}")
    vals = []
    for c in cols:
        if c == "data_id":
            vals.append(e["dataId"])
        elif c == "group_id":
            vals.append(e.get("group") or "DEFAULT_GROUP")
        elif c == "tenant_id":
            vals.append(e["tenant"])
        elif c == "content":
            vals.append(content)
        elif c == "md5":
            vals.append(hashlib.md5(content.encode("utf-8")).hexdigest())
        elif c == "type":
            vals.append(e.get("type") or "yaml")
        elif c == "src_user":
            vals.append("restore-script")
        elif c == "src_ip":
            vals.append("127.0.0.1")
        elif c in ("gmt_create", "gmt_modified"):
            vals.append(None)  # 库侧默认值/NOW 兜底
        else:
            vals.append("")
    cur.execute(sql, vals)


def main() -> int:
    args = sys.argv[1:]
    dry = "--dry-run" in args
    snap = BACKUP_ROOT / "current"
    if "--snapshot" in args:
        i = args.index("--snapshot")
        if i + 1 >= len(args):
            print("[FAIL] --snapshot 需要时间戳参数（snapshots/ 下的目录名）")
            return 1
        snap = BACKUP_ROOT / "snapshots" / args[i + 1]
    if not (snap / "manifest.json").exists():
        print(f"[FAIL] 快照清单不存在: {snap / 'manifest.json'}（先跑 backup_nacos_config.py）")
        return 1

    if nacos_container_running():
        print(f"[FAIL] nacos 容器正在运行——直写库会与内存缓存不一致。先执行: docker stop {CONTAINER}")
        return 1

    manifest = json.loads((snap / "manifest.json").read_text(encoding="utf-8"))
    entries = [e for e in manifest["configs"]]
    grays = manifest.get("grayConfigs") or []
    print(f"[INFO] 恢复源: {snap}（{manifest.get('createdAt', '?')}）")
    print(f"[INFO] 计划写入 config_info {len(entries)} 条"
          + (f"、config_info_gray {len(grays)} 条" if grays else ""))

    try:
        conn = pymysql.connect(host=MYSQL_HOST, port=MYSQL_PORT, user=MYSQL_USER,
                               password=MYSQL_PASSWORD, database=DB_NAME, charset="utf8mb4")
    except pymysql.MySQLError as e:
        print(f"[FAIL] MySQL 连接失败: {type(e).__name__}: {e}（库若整库丢失，需先建 nacos_config 库与官方 3.x 表结构，再跑本脚本）")
        return 1

    if not table_exists(conn, "config_info"):
        conn.close()
        print("[FAIL] nacos_config.config_info 表不存在——本脚本只做数据级恢复，整库重建需先落官方 3.x DDL")
        return 1

    cols = present_cols(conn, "config_info", INSERT_BASE + INSERT_OPTIONAL)
    missing = [c for c in INSERT_BASE if c not in cols]
    if missing:
        conn.close()
        print(f"[FAIL] config_info 缺必需列: {missing}")
        return 1

    if dry:
        for e in sorted(entries, key=lambda x: (x["tenant"], x["dataId"])):
            print(f"  [DRY] [{e['tenant'] or '(空)'}] {e['dataId']} -> {e['bytes']}B")
        print("[OK] dry-run 结束，未写库")
        conn.close()
        return 0

    written, verified = 0, 0
    try:
        with conn.cursor() as cur:
            for e in sorted(entries, key=lambda x: (x["tenant"], x["dataId"])):
                # 目录名映射与备份脚本一致：空租户在 default/ 下
                tdir = "default" if e["tenant"] == "" else e["tenant"]
                f = snap / tdir / e["dataId"]
                if not f.exists():
                    print(f"[WARN] 备份文件缺失，跳过: [{e['tenant'] or '(空)'}] {e['dataId']}")
                    continue
                content = f.read_text(encoding="utf-8")
                upsert_config(cur, cols, e, content, tdir)
                written += 1
            # 灰度行：先删后插（表内唯一键形态不依赖，按 data_id+gray_name+tenant_id 清理）
            if grays and table_exists(conn, "config_info_gray"):
                gcols = present_cols(conn, "config_info_gray",
                                     ["data_id", "group_id", "tenant_id", "gray_name",
                                      "content", "md5", "src_user", "src_ip"])
                for g in grays:
                    fname = f"{g['dataId']}@{g.get('grayName', '')}"
                    gf = snap / ("default" if g["tenant"] == "" else g["tenant"]) / "__gray__" / fname
                    if not gf.exists():
                        continue
                    content = gf.read_text(encoding="utf-8")
                    cur.execute(
                        "DELETE FROM config_info_gray WHERE data_id=%s AND gray_name=%s AND tenant_id=%s",
                        (g["dataId"], g.get("grayName", ""), g["tenant"]))
                    vals = []
                    for c in gcols:
                        vals.append({"data_id": g["dataId"], "gray_name": g.get("grayName", ""),
                                     "group_id": "DEFAULT_GROUP", "tenant_id": g["tenant"],
                                     "content": content, "md5": hashlib.md5(content.encode()).hexdigest(),
                                     "src_user": "restore-script", "src_ip": "127.0.0.1"}.get(c, ""))
                    cur.execute(f"INSERT INTO config_info_gray ({', '.join(gcols)}) "
                                f"VALUES ({', '.join(['%s'] * len(gcols))})", vals)
        conn.commit()

        # 写后校验：按键回读，内容 MD5 与备份文件比对
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            for e in entries:
                tdir = "default" if e["tenant"] == "" else e["tenant"]
                f = snap / tdir / e["dataId"]
                if not f.exists():
                    continue
                cur.execute("SELECT content FROM config_info WHERE data_id=%s AND group_id=%s AND tenant_id=%s",
                            (e["dataId"], e.get("group") or "DEFAULT_GROUP", e["tenant"]))
                row = cur.fetchone()
                if row and hashlib.md5((row["content"] or "").encode("utf-8")).hexdigest() == e["fileMd5"]:
                    verified += 1
                else:
                    print(f"[WARN] 回读不一致: [{e['tenant'] or '(空)'}] {e['dataId']}")
    finally:
        conn.close()

    print(f"[OK] 写入 {written} 条，回读校验通过 {verified} 条")
    print(f"[NEXT] 恢复完成——启动: docker start {CONTAINER}（冷加载库内配置），再逐个起业务服务")
    return 0 if written and written == verified else 2


if __name__ == "__main__":
    sys.exit(main())
