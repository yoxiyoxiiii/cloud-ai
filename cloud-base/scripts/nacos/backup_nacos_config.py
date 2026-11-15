"""Nacos 配置全量本地备份（防配置丢失的快照源 + 服务 local 离线配置生成器）。

从宿主 MySQL 的 nacos_config 库导出全部租户的 config_info（含 3.x 灰度表若有数据），
落盘到 cloud-base/nacos-local/（已 gitignore，含密钥真值，绝不入库）：
  snapshots/<时间戳>/<租户目录>/<data_id>   每次运行的历史快照
  current/<租户目录>/<data_id>              最近一次的镜像（服务本地兜底启动指向这里）
  snapshots/<时间戳>/manifest.json          清单（租户/数据ID/MD5/字节数，无内容）

同时为仓库内服务生成 <服务>/src/main/resources/application-local.yml（gitignored）：
  有 dataId 的服务 = cloud-common + 服务 yaml 快照多文档合并 + local 离线段；
  gateway 无 dataId，按内置模板 = cloud-common 快照 + 离线段（验签必需 cloud.jwt.secret）。
离线段 = Nacos discovery/import-check 关闭 + 七服务静态实例表（local 模式下 lb:// 解析来源）。

安全口径：stdout 只打印配置键名、MD5、大小与双租户差异结论，绝不打印配置内容。

用法：uv run --project cloud-ai-common python cloud-base/scripts/nacos/backup_nacos_config.py
（依赖 pymysql 经 cloud-ai-common 环境提供（dev 依赖组）；MySQL 连接口径同仓规：127.0.0.1:3306 root 空密码）
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

# local 离线段静态实例表（服务名→地址）：local 模式下 lb:// 解析的唯一来源。
# 端口矩阵单点维护于此——改端口改这里，并与 CLAUDE.md 端口段人工同步。
STATIC_INSTANCES = [
    ("cloud-sso", "http://127.0.0.1:9201"),
    ("cloud-system", "http://127.0.0.1:9202"),
    ("cloud-bpmn", "http://127.0.0.1:9203"),
    ("cloud-task", "http://127.0.0.1:9204"),
    ("cloud-aggregate", "http://127.0.0.1:9205"),
    ("cloud-base-rag", "http://127.0.0.1:8011"),
    ("cloud-base-agent", "http://127.0.0.1:8012"),
]

# local 离线段：关闭 Nacos 发现注册与 import 检查，lb:// 改走静态实例表。
# import-check 必须关闭：local 模式下 import 无 nacos 条目是预期形态，SCA 检查器会 fail-fast。
OFFLINE_SEGMENT = (
    "# local 离线段：不注册 Nacos 服务发现，lb:// 解析改走下表（声明即真相——\n"
    "# 条目指向的实例未启动时流量会打到死端口，属本模式已知语义）。\n"
    "# import-check 关闭：local 模式下 import 无 nacos 条目是预期，SCA 检查器会 fail-fast。\n"
    "spring:\n"
    "  cloud:\n"
    "    nacos:\n"
    "      config:\n"
    "        import-check:\n"
    "          enabled: false\n"
    "      discovery:\n"
    "        enabled: false\n"
    "    discovery:\n"
    "      client:\n"
    "        simple:\n"
    "          instances:\n"
    + "".join(f"            {name}: [{{uri: '{uri}'}}]\n" for name, uri in STATIC_INSTANCES)
)

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
    """为仓库内服务生成 application-local.yml（默认 local 启动 = 离线模式）。

    有 dataId 的服务：cloud-common.yaml + 服务 yaml 快照多文档合并 + 离线段；
    gateway 无 dataId：内置模板 = cloud-common.yaml 快照 + 离线段——网关验签必需
    cloud.jwt.secret，缺 common 快照时带 token 请求会以 HTTP 200 + code 500 形态失败（易误诊）。
    落在 <服务>/src/main/resources/application-local.yml（gitignored，含密钥真值勿手改勿提交），
    每次备份整体重生成（幂等），保证与 Nacos 备份同源同步。
    """
    common_file = snap_dir / "default" / "cloud-common.yaml"
    common_text = common_file.read_text(encoding="utf-8").rstrip() if common_file.exists() else ""
    header = (
        "# 本地离线配置：backup_nacos_config.py 从 Nacos 备份自动生成，勿手改勿提交（含密钥真值）。\n"
        "# 默认启动即带 local（见 scripts/dev-services.sh 与 CLAUDE.md）——local = 离线模式：\n"
        "# 不 import Nacos 配置、不注册 Nacos 服务发现，lb:// 走本文件末尾静态实例表。\n"
        "# 改 Nacos 配置后由启动脚本在下次启动自动重生成本文件；不带 local 启动 = 传统路径。\n"
    )
    gateway_header = (
        "# 网关本地离线配置：backup_nacos_config.py 从 Nacos 备份自动生成，勿手改勿提交（含密钥真值）。\n"
        "# 网关无服务专属 dataId，本文件 = cloud-common.yaml 快照（cloud.jwt.secret 验签必需）+ 离线段。\n"
        "# 默认启动即带 local（见 scripts/dev-services.sh 与 CLAUDE.md）——local = 离线模式：\n"
        "# 不 import Nacos 配置、不注册 Nacos 服务发现，lb:// 走本文件末尾静态实例表。\n"
        "# 改 Nacos 配置后由启动脚本在下次启动自动重生成本文件。\n"
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
        target.write_text(header + "\n---\n".join(parts) + "\n---\n" + OFFLINE_SEGMENT,
                          encoding="utf-8", newline="\n")
        generated.append(target.relative_to(CLOUD_BASE_DIR).as_posix())
    # gateway 分支：无 dataId 但 import 消费 cloud-common.yaml，须与快照同源刷新 local 面
    if common_text:
        gw_target = CLOUD_BASE_DIR / "cloud-gateway" / "src" / "main" / "resources" / "application-local.yml"
        gw_target.write_text(gateway_header + common_text + "\n---\n" + OFFLINE_SEGMENT,
                             encoding="utf-8", newline="\n")
        generated.append(gw_target.relative_to(CLOUD_BASE_DIR).as_posix())
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
        print(f"[OK] 已生成服务本地离线配置: {p}（默认启动即带 local 激活）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
