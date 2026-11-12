"""Nacos 配置全量本地备份（防配置丢失的快照源）。

从宿主 MySQL 的 nacos_config 库导出全部租户的 config_info（含 3.x 灰度表若有数据），
落盘到 cloud-base/nacos-local/（已 gitignore，含密钥真值，绝不入库）：
  snapshots/<时间戳>/<租户目录>/<data_id>   每次运行的历史快照
  current/<租户目录>/<data_id>              最近一次的镜像（服务本地兜底启动指向这里）
  snapshots/<时间戳>/manifest.json          清单（租户/数据ID/MD5/字节数，无内容）

安全口径：stdout 只打印配置键名、MD5、大小与双租户差异结论，绝不打印配置内容。

用法：uv run --project cloud-base-ai python cloud-base/scripts/nacos/backup_nacos_config.py
（依赖 pymysql 经 cloud-base-ai 环境提供；MySQL 连接口径同仓规：127.0.0.1:3306 root 空密码）
"""

import hashlib
import json
import shutil
import sys
from datetime import datetime
from pathlib import Path

import pymysql

MYSQL_HOST = "127.0.0.1"
MYSQL_PORT = 3306
MYSQL_USER = "root"
MYSQL_PASSWORD = ""
DB_NAME = "nacos_config"

# 本脚本所在 .../cloud-base/scripts/nacos/ → cloud-base 根
CLOUD_BASE_DIR = Path(__file__).resolve().parents[2]
BACKUP_ROOT = CLOUD_BASE_DIR / "nacos-local"

# 备份关注的列：前 6 列为必需，其余按库内实际存在与否动态携带（防列漂移猜错）
REQUIRED_COLS = ["data_id", "group_id", "tenant_id", "content", "md5"]
OPTIONAL_COLS = ["type", "gmt_modified", "src_user", "src_ip", "app_name",
                 "c_desc", "c_use", "effect", "c_schema", "gmt_create"]


def tenant_dir_name(tenant_id: str) -> str:
    """租户名转目录名：空租户（2.x 兼容副本）用 default，其余做路径安全化。"""
    if not tenant_id:
        return "default"
    safe = "".join(c if c.isalnum() or c in "-_" else "_" for c in tenant_id)
    return safe or "default"


def table_columns(conn, table: str) -> list:
    with conn.cursor() as cur:
        cur.execute(
            "SELECT column_name FROM information_schema.columns "
            "WHERE table_schema = %s AND table_name = %s",
            (DB_NAME, table),
        )
        return [r[0] for r in cur.fetchall()]


def dump_config_info(conn, snap_dir: Path) -> list:
    """导出 config_info 全租户行；返回清单条目（不含内容）。"""
    cols = [c for c in REQUIRED_COLS + OPTIONAL_COLS if c in table_columns(conn, "config_info")]
    sql = f"SELECT {', '.join(cols)} FROM config_info"
    entries = []
    with conn.cursor(pymysql.cursors.DictCursor) as cur:
        cur.execute(sql)
        for row in cur.fetchall():
            tdir = tenant_dir_name(row["tenant_id"])
            out = snap_dir / tdir / row["data_id"]
            out.parent.mkdir(parents=True, exist_ok=True)
            content = row["content"] or ""
            out.write_text(content, encoding="utf-8", newline="\n")
            # 库内 md5 与内容指纹双记——恢复与校验都以本次落盘内容为准
            local_md5 = hashlib.md5(content.encode("utf-8")).hexdigest()
            entries.append({
                "tenant": row["tenant_id"],
                "dataId": row["data_id"],
                "group": row.get("group_id", ""),
                "type": row.get("type", ""),
                "dbMd5": row.get("md5", ""),
                "fileMd5": local_md5,
                "bytes": len(content.encode("utf-8")),
                "modified": str(row.get("gmt_modified", "")),
            })
    return entries


def dump_gray_if_any(conn, snap_dir: Path) -> list:
    """3.x 灰度配置（config_info_gray）若有数据一并导出，防静默丢失。"""
    if "config_info_gray" not in table_columns(conn, "config_info_gray"):
        return []
    with conn.cursor() as cur:
        cur.execute("SELECT COUNT(*) FROM config_info_gray")
        if cur.fetchone()[0] == 0:
            return []
    cols = [c for c in ["data_id", "group_id", "tenant_id", "gray_name", "content", "md5"]
            if c in table_columns(conn, "config_info_gray")]
    entries = []
    with conn.cursor(pymysql.cursors.DictCursor) as cur:
        cur.execute(f"SELECT {', '.join(cols)} FROM config_info_gray")
        for row in cur.fetchall():
            tdir = tenant_dir_name(row["tenant_id"])
            out = snap_dir / tdir / "__gray__" / f"{row['data_id']}@{row.get('gray_name', '')}"
            out.parent.mkdir(parents=True, exist_ok=True)
            content = row["content"] or ""
            out.write_text(content, encoding="utf-8", newline="\n")
            entries.append({
                "tenant": row["tenant_id"], "dataId": row["data_id"], "gray": True,
                "grayName": row.get("gray_name", ""),
                "fileMd5": hashlib.md5(content.encode("utf-8")).hexdigest(),
            })
    return entries


def generate_local_profiles(entries: list, snap_dir: Path) -> list:
    """为每个有 Nacos 配置的仓库内服务生成 application-local.yml（profile=local 本地兜底）。

    落在 <服务>/src/main/resources/application-local.yml（gitignored，含密钥真值勿手改勿提交），
    内容 = cloud-common.yaml + 服务 yaml 的多文档合并（--- 分隔，键冲突时服务段优先）。
    每次备份重新生成，保证与 Nacos 备份同源同步。
    """
    common_file = snap_dir / "default" / "cloud-common.yaml"
    common_text = common_file.read_text(encoding="utf-8").rstrip() if common_file.exists() else ""
    header = (
        "# 本地兜底配置：backup_nacos_config.py 从 Nacos 备份自动生成，勿手改勿提交（含密钥真值）。\n"
        "# Nacos 配置变更后重跑备份脚本即可再生本文件。\n"
        "# 启用：java -jar ... --spring.profiles.active=local——激活后本地文件优先于 Nacos 取值，健康环境勿带 local。\n"
    )
    generated, done = [], set()
    for e in entries:
        if e.get("gray") or not e["dataId"].endswith(".yaml"):
            continue
        svc = e["dataId"][:-5]  # cloud-sso.yaml -> cloud-sso
        if svc in done:
            continue  # 双租户两条同 content，生成一次即可
        done.add(svc)
        res_dir = CLOUD_BASE_DIR / svc / "src" / "main" / "resources"
        if not res_dir.is_dir():
            continue  # 仓库内无对应可运行服务模块（聚合目录/未落地服务跳过）
        tdir = "default" if e["tenant"] == "" else e["tenant"]
        parts = []
        if common_text and svc != "cloud-common":
            parts.append(common_text)
        parts.append((snap_dir / tdir / e["dataId"]).read_text(encoding="utf-8").rstrip())
        target = res_dir / "application-local.yml"
        target.write_text(header + "\n---\n".join(parts) + "\n",
                          encoding="utf-8", newline="\n")
        generated.append(target.relative_to(CLOUD_BASE_DIR).as_posix())
    return generated


def diff_tenants(entries: list) -> dict:
    """双租户一致性体检：同名 dataId 两副本内容不一致即是双写错位（历史踩坑点）。"""
    by_tenant = {}
    for e in entries:
        if e.get("gray"):
            continue
        by_tenant.setdefault(e["tenant"], {})[e["dataId"]] = e["fileMd5"]
    tenants = sorted(by_tenant)
    if len(tenants) < 2:
        return {"note": f"仅存在租户 {tenants}，无双副本可比对"}
    a, b = by_tenant[tenants[0]], by_tenant[tenants[1]]
    result = {}
    for data_id in sorted(set(a) | set(b)):
        if data_id not in a:
            result[data_id] = f"仅 {tenants[1]} 有"
        elif data_id not in b:
            result[data_id] = f"仅 {tenants[0]} 有"
        elif a[data_id] != b[data_id]:
            result[data_id] = "两租户内容不一致（双写错位，须人工核对后向两租户发相同干净内容）"
    return result or {"result": f"{tenants[0]} 与 {tenants[1]} 全部一致"}


def main() -> int:
    try:
        conn = pymysql.connect(host=MYSQL_HOST, port=MYSQL_PORT, user=MYSQL_USER,
                               password=MYSQL_PASSWORD, database=DB_NAME, charset="utf8mb4")
    except pymysql.MySQLError as e:
        print(f"[FAIL] MySQL 连接失败: {type(e).__name__}: {e}")
        return 1

    ts = datetime.now().strftime("%Y%m%d-%H%M%S")
    snap_dir = BACKUP_ROOT / "snapshots" / ts
    snap_dir.mkdir(parents=True, exist_ok=True)

    entries = dump_config_info(conn, snap_dir)
    gray_entries = dump_gray_if_any(conn, snap_dir)
    conn.close()

    manifest = {
        "createdAt": datetime.now().isoformat(timespec="seconds"),
        "sourceDb": f"{DB_NAME}@{MYSQL_HOST}:{MYSQL_PORT}",
        "tool": "cloud-base/scripts/nacos/backup_nacos_config.py",
        "configs": entries,
        "grayConfigs": gray_entries,
        "tenantDiff": diff_tenants(entries),
    }
    (snap_dir / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")

    # current/ 镜像 = 最近一次快照（本地兜底启动指向这里）；整体替换防残留旧文件
    current = BACKUP_ROOT / "current"
    if current.exists():
        shutil.rmtree(current)
    shutil.copytree(snap_dir, current)

    local_profiles = generate_local_profiles(entries, snap_dir)

    tenants = sorted({e["tenant"] or "(空)" for e in entries})
    print(f"[OK] 备份完成: {snap_dir}")
    print(f"[OK] 共 {len(entries)} 条配置，租户: {', '.join(tenants)}；灰度配置 {len(gray_entries)} 条")
    for e in sorted(entries, key=lambda x: (x["tenant"], x["dataId"])):
        print(f"  - [{e['tenant'] or '(空)'}] {e['dataId']} ({e['bytes']}B md5={e['fileMd5'][:8]})")
    if gray_entries:
        print("[WARN] 存在灰度配置行，已导出至 __gray__/ 目录（发布态不在本备份的恢复语义内）")
    diff = manifest["tenantDiff"]
    if "result" in diff:
        print(f"[OK] 双租户体检: {diff['result']}")
    else:
        print("[WARN] 双租户体检发现差异（双写错位风险，键名如下）:")
        for k, v in diff.items():
            print(f"  - {k}: {v}")
    print(f"[OK] current/ 镜像已刷新: {current}")
    for p in local_profiles:
        print(f"[OK] 已生成服务本地配置: {p}（--spring.profiles.active=local 启用）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
