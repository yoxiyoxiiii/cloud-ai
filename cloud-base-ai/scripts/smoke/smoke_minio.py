"""MinIO 真连接冒烟：验证 bucket 幂等就绪、对象写/读/删、预签名直链 HTTP 实取全链路。

为什么需要它：单测用 FakeMinio 替身，覆盖不到真实 MinIO 的签名方言与 HTTP 行为
（预签名 URL 是否真的免凭证可取，替身无法验证）。

运行前提：MinIO 容器已启动（宿主端口 9100），cloud-base-ai/.env 已写 MinIO 凭证。
运行方式（任意目录）：
    uv run --project cloud-base-ai python scripts/smoke/smoke_minio.py

临时对象名带时间戳、结束即删；cloud-ai 是应用 bucket，保留不删。
"""

import sys
import time
import urllib.request

from minio.error import S3Error

from cloud_base_ai.storage import get_storage

sys.stdout.reconfigure(encoding="utf-8")  # Windows 控制台默认 GBK，强制 UTF-8 防中文乱码


def main() -> int:
    storage = get_storage()
    name = f"e2e_smoke_minio_{int(time.time())}"
    payload = "minio smoke 对象内容".encode("utf-8")
    failures: list[str] = []
    try:
        storage.ensure_bucket()
        print(f"[1] bucket 就绪（不存在则创建，幂等）")

        storage.put_bytes(name, payload, content_type="text/plain")
        got = storage.get_bytes(name)
        print(f"[2] 写入后读回 {len(got)} 字节")
        if got != payload:
            failures.append(f"读回内容不一致：期望 {len(payload)} 字节，实际 {len(got)} 字节")

        url = storage.presigned_get_url(name, expires_seconds=300)
        via_url = urllib.request.urlopen(url, timeout=10).read()
        print(f"[3] 预签名直链实取 {len(via_url)} 字节")
        if via_url != payload:
            failures.append("预签名直链取回内容与写入不一致")

        storage.remove(name)
        print("[4] 已删除临时对象")
        try:
            storage.get_bytes(name)
            failures.append("删除后仍能读回对象")
        except S3Error as e:
            print(f"[5] 删除后读取按预期报 S3Error: code={e.code}")
            if e.code != "NoSuchKey":
                failures.append(f"期望 NoSuchKey，实际 {e.code}")
    finally:
        # 中途失败也尽力清理临时对象；bucket 是应用资产，保留
        try:
            storage.remove(name)
        except S3Error:
            pass

    if failures:
        print("\n冒烟失败：")
        for item in failures:
            print(f"  - {item}")
        return 1
    print("\n冒烟通过")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # 连不上 MinIO / 凭证错误等环境问题给友好提示，不甩整屏堆栈
        print(f"冒烟无法运行：{type(e).__name__}: {e}")
        print("请确认：MinIO 容器已启动（宿主端口 9100）、cloud-base-ai/.env 已写凭证")
        sys.exit(2)
