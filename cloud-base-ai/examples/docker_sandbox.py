"""DockerSandbox 完整案例：继承 BaseSandbox 只实现 4 个抽象成员，沙箱 = 一次性 Docker 容器。

BaseSandbox 的派生机制（源码 deepagents/backends/sandbox.py）：
- ls / read / glob / grep / edit 全部由基类编译成 python3 -c 脚本经 execute() 执行
  ——所以镜像必须自带 python3，这就是选 python:3.12-slim 的原因
- write = execute() 建父目录（预检脚本）+ upload_files() 传文件内容
- 异步方法（aexecute/aupload_files/...）由协议层 asyncio.to_thread 自动桥接，只需实现同步版

本案例不引 docker SDK，直接 subprocess 调 docker CLI——命令到协议的映射一目了然；
生产环境建议换 docker SDK（避免每次 spawn 进程）或做每会话容器池。

运行前提：Docker Desktop 在线；LLM key 同 hello.py（cloud-base-ai/.env）。
"""

import base64
import os
import posixpath
import shlex
import subprocess
import tempfile
import uuid

from deepagents import create_deep_agent
from deepagents.backends.protocol import (
    ExecuteResponse,
    FileDownloadResponse,
    FileUploadResponse,
)
from deepagents.backends.sandbox import BaseSandbox
from langchain_core.messages import HumanMessage

from cloud_base_ai.models import model_factory


class DockerSandbox(BaseSandbox):
    """一次性容器沙箱：构造即起容器，stop() 销毁，一个实例对应一个容器。"""

    DEFAULT_TIMEOUT_SECONDS = 120

    def __init__(self, image: str = "python:3.12-slim", workdir: str = "/workspace") -> None:
        self._container = f"deepagents-sandbox-{uuid.uuid4().hex[:12]}"
        self._workdir = workdir
        # sleep infinity 让容器常驻等 docker exec；--network none 断外网（agent 要 pip
        # install 依赖时去掉该参数）；内存/CPU 限额防失控命令拖垮宿主
        self._check(
            ["docker", "run", "-d", "--rm", "--name", self._container,
             "--network", "none", "--memory", "512m", "--cpus", "1",
             image, "sleep", "infinity"],
        )
        try:
            self._check(["docker", "exec", self._container, "mkdir", "-p", workdir])
        except Exception:
            self.stop()  # 半初始化不留孤儿容器
            raise

    # ---------- 4 个抽象成员（BaseSandbox 仅欠这些） ----------

    @property
    def id(self) -> str:
        """沙箱唯一标识：容器名。"""
        return self._container

    def execute(self, command: str, *, timeout: int | None = None) -> ExecuteResponse:
        """shell 命令 → docker exec 容器内 sh -c；stdout+stderr 合并返回（协议口径）。"""
        effective = self.DEFAULT_TIMEOUT_SECONDS if timeout is None else timeout
        try:
            proc = subprocess.run(
                ["docker", "exec", "-w", self._workdir, self._container, "sh", "-c", command],
                capture_output=True,
                timeout=effective,
            )
        except subprocess.TimeoutExpired:
            # 超时不能抛异常：转成带退出码的结果让模型看到并自行调整
            return ExecuteResponse(
                output=f"Command timed out after {effective}s and was killed.",
                exit_code=124,
            )
        output = proc.stdout.decode("utf-8", errors="replace")
        err = proc.stderr.decode("utf-8", errors="replace")
        return ExecuteResponse(output=output + err, exit_code=proc.returncode)

    def upload_files(self, files: list[tuple[str, bytes]]) -> list[FileUploadResponse]:
        """(容器路径, 内容) → 宿主临时文件 → docker cp。逐文件兜错而非抛出（协议要求部分成功）。"""
        responses: list[FileUploadResponse] = []
        for path, content in files:
            try:
                parent = posixpath.dirname(path)
                if parent:
                    self._check(["docker", "exec", self._container, "mkdir", "-p", parent])
                # docker cp 不能直接喂字节内容，先落宿主临时文件再拷进容器
                fd, tmp = tempfile.mkstemp()
                try:
                    with os.fdopen(fd, "wb") as f:
                        f.write(content)
                    self._check(["docker", "cp", tmp, f"{self._container}:{path}"])
                finally:
                    os.unlink(tmp)
                responses.append(FileUploadResponse(path=path))
            except Exception as e:  # noqa: BLE001 - 协议要求逐文件兜错
                responses.append(FileUploadResponse(path=path, error=f"upload failed: {e}"))
        return responses

    def download_files(self, paths: list[str]) -> list[FileDownloadResponse]:
        """容器文件 → base64 经 stdout 取回（直接 cat 二进制会破坏字节流，base64 安全）。"""
        responses: list[FileDownloadResponse] = []
        for path in paths:
            proc = subprocess.run(
                ["docker", "exec", self._container, "sh", "-c",
                 f"base64 < {shlex.quote(path)}"],
                capture_output=True,
            )
            if proc.returncode != 0:
                err = proc.stderr.decode("utf-8", errors="replace").strip()
                responses.append(FileDownloadResponse(path=path, error=err or "read failed"))
                continue
            responses.append(FileDownloadResponse(path=path, content=base64.b64decode(proc.stdout)))
        return responses

    # ---------- 生命周期与内部工具 ----------

    def stop(self) -> None:
        """销毁容器（docker rm -f 幂等，重复调用无害）。"""
        subprocess.run(["docker", "rm", "-f", self._container], capture_output=True)

    def __enter__(self) -> "DockerSandbox":
        """支持 with：任何异常/提前返回路径都保证容器回收（finally 语义收进类里）。"""
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.stop()

    def _check(self, args: list[str]) -> None:
        """跑 docker 命令，非零退出即抛 RuntimeError（构造期失败要让调用方知道）。"""
        proc = subprocess.run(args, capture_output=True)
        if proc.returncode != 0:
            detail = proc.stderr.decode("utf-8", errors="replace")[:300]
            raise RuntimeError(f"docker command failed ({args[:3]}...): {detail}")


async def main() -> None:
    # with 语义：冒烟失败、LLM 未配置、构造异常、Ctrl+C——任何路径容器都回收
    with DockerSandbox() as sandbox:
        # 先直连验证通路（不经过 LLM）：execute / write / read 三原语
        print("[smoke] execute:", sandbox.execute("echo hello && python3 -V").output.strip())
        print("[smoke] write:", sandbox.write("/workspace/note.txt", "hello deepagents"))
        print("[smoke] read:", sandbox.read("/workspace/note.txt").file_data)

        agent = create_deep_agent(
            model=model_factory.get_chat_model(),
            system_prompt=(
                "你是编程助手，拥有一个隔离沙箱：用 ls/read_file/write_file/edit_file/glob/grep "
                "查看与修改文件，用 execute 运行 shell 命令。所有代码和命令都在沙箱里执行。"
            ),
            backend=sandbox,  # 传入 sandbox 后端后，agent 自动获得全套文件工具 + execute 工具
        )
        result = await agent.ainvoke({"messages": [HumanMessage(content=(
            "在沙箱里写一个 fib.py：打印斐波那契数列前 10 项，然后运行它，把输出原样告诉我"
        ))]})
        print("\n[agent] 最终回答：")
        print(result["messages"][-1].content)


if __name__ == "__main__":
    import asyncio

    asyncio.run(main())
