# 上传链路原件入 MinIO 实施计划（2026-10-16，纯后端 B 级）

> **For agentic workers:** 本计划按任务顺序执行，步骤用 checkbox 跟踪。
> 需求原文：文件上传现在需要存储到对象服务器中，目前是 todo，加上这个功能。
> 即：`rag/service.py:195` TODO 位落 MinIO 原件存储（上一轮已交付 `MinioStorage` 工具）。

**Goal**: KB 上传链路把文件原件写入 MinIO（对象键 `kb/{kb_id}/{uuid}{后缀}`），`ai_knowledge_base_files.file_path` 从 uuid 占位升级为完整对象键；失败语义与 Milvus 同源（异常上抛回滚 MySQL）。

**Architecture**: 只改 `upload_file_by_kb`（TODO 原位置：pdfmuse 解析后、MySQL 写入前——MinIO 失败零 DB 写；后置失败留孤儿对象并入一致性欠账）；`MinioStorage` 经 `run_in_threadpool` 包装进 async 服务（SDK 同步）。零 DDL / 零契约 / 零前端。

**Tech Stack**: 既有 `cloud_base_ai.storage.get_storage()` + `fastapi.concurrency.run_in_threadpool`。

---

## 设计定案（不重议）

1. **对象键**：`f"kb/{kb_id}/{uuid.uuid4().hex}{Path(file.filename or '').suffix}"`——直接作为 `file_path` 落库（自描述，读取方 `storage.presigned_get_url(row.file_path)` 零推导）；`uk_kb_file_path(kb_id,file_path)` 唯一性由 uuid 保证。存量行 file_path 为旧 uuid 语义——当前无读取方（无下载端点），不受影响。
2. **顺序**：ensure_bucket → put_bytes 放 TODO 位（行 195 附近，`save_kb_file` 之前）。非 PDF 在更早的 `pdfmuse.to_text` 已拦（契约 500 语义不变），MinIO 不会被非 PDF 玷污。
3. **失败语义**：MinIO 异常直接上抛 → `@transactional` 回滚（此时零 DB 写）；MySQL/Milvus 后置失败 → 留 MinIO 孤儿对象，并入 handoff 既有一致性欠账（本轮只记账不治理）。
4. **content_type**：透传 `file.content_type`，None 时给 `application/octet-stream`。
5. **WIP 隔离红线**：`rag/service.py` 尾部 reembedding WIP（用户未提交半成品）与 `rag/router.py` **一行不碰**——只改 upload 函数区域。
6. **不动**：delete_kb（物理对象清理随孤儿向量欠账统一治理）、契约文档（API 行为零变更）、守护测试、cloud-web。

---

### Task 1: service 接线（rag/service.py，仅 upload 区域）

- [ ] **Step 1.1**: imports 区新增：`from pathlib import Path`（若未有）、`from cloud_base_ai.storage import get_storage`、`from fastapi.concurrency import run_in_threadpool`。
- [ ] **Step 1.2**: TODO 注释位（原行 195）替换为原件入 MinIO 块：

```python
    # 文件原件入 MinIO：对象键=kb/{kb_id}/{uuid}{后缀}，即文件表 file_path 的新语义
    # （旧 uuid 占位自此退役）。SDK 为同步客户端，run_in_threadpool 包装防阻塞事件循环；
    # 失败语义与 Milvus 同源——异常上抛回滚本事务（此时零 DB 写），后置步骤失败留孤儿对象
    # （handoff 一致性欠账统一治理）
    storage = get_storage()
    suffix = Path(file.filename or "").suffix
    object_name = f"kb/{kb_id}/{uuid.uuid4().hex}{suffix}"
    await run_in_threadpool(storage.ensure_bucket)
    await run_in_threadpool(
        storage.put_bytes, object_name, file_data, file.content_type or "application/octet-stream")
```

- [ ] **Step 1.3**: `save_kb_file(...)` 的 `file_path=uuid.uuid4().hex` 改为 `file_path=object_name`。
- [ ] **Step 1.4**: `git diff` 自查：改动仅 upload 函数区域 + imports；reembedding WIP 尾部行与 router.py 零触碰。

### Task 2: 单元测试（tests/test_rag_service.py 新增 3 用例）

对齐该文件既有 monkeypatch 模式（dao/chunking/pdfmuse/vectorstore 全替身）。替身要点：`pdfmuse.to_text`→定串；`chunking.parent_child_split_file`→`([], [child_doc])`，child_doc.metadata 带 `id/parent_id/type`；`dao.save_kb_file`→捕获入参、返回带 id 的实体；`dao.save_chunks`→原样返回（带自增 id）；`vectorstore.get_milvus`→带 `add_documents` 的假对象；`storage.get_storage`→记录调用的假存储（ensure_bucket/put_bytes）。

- [ ] **2.1** `test_upload_stores_original_to_minio`：断言 put_bytes 收到 `object_name == 落库的 file_path`、内容==文件字节、content_type 透传；object_name 以 `kb/{kb_id}/` 开头且带原文件后缀。
- [ ] **2.2** `test_upload_minio_failure_zero_db_writes`：put_bytes 抛异常 → 上抛且 `dao.save_kb_file` 未被调用（零 DB 写）。
- [ ] **2.3** `test_upload_content_type_none_defaults`：`file.content_type=None` → put_bytes 收到 `application/octet-stream`。

### Task 3: 真链路验收（uvicorn 直连 + 手工鉴权 header）

- [ ] **3.1 前置自检**：MySQL/Nacos/Redis/Milvus/MinIO/Redis 基础设施在线；**Ollama 11434 在线**（上传链 embedding 依赖）——不在线先查 `netstat -ano | grep :11434`，仍不通则停下上报。
- [ ] **3.2 测试 PDF 生成与可解析自检**（python 内联，工程根 uv run）——最小合法 PDF 构造器（经典模板：catalog/pages/page/content/font 五对象 + xref）：

```python
def minimal_pdf(text: str) -> bytes:
    content = f"BT /F1 12 Tf 72 720 Td ({text}) Tj ET".encode("ascii")
    objs = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length " + str(len(content)).encode() + b" >>\nstream\n" + content + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out = bytearray(b"%PDF-1.4\n"); offsets = []
    for i, obj in enumerate(objs, 1):
        offsets.append(len(out)); out += f"{i} 0 obj\n".encode() + obj + b"\nendobj\n"
    xref = len(out)
    out += f"xref\n0 {len(objs)+1}\n".encode() + b"0000000000 65535 f \n"
    for off in offsets: out += f"{off:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objs)+1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)
```

**自检门**：`pdfmuse.to_text(minimal_pdf("..."))` 非空才继续；解析不出换 1.3 版头/双空格 xref 重试，仍不行停上报告（不硬闯）。
- [ ] **3.3 起服直连**：uvicorn 8010（uv 绝对路径命令见 CLAUDE.md）；**本地直连须手工带网关注入的鉴权 header**（`X-User-*`，具体键名以 `src/cloud_base_ai/` 安全模块实现为准，perms 需含 `ai:kb:add`——开工先读 `rag/router.py` 的 require_perms 用法确认）。
- [ ] **3.4 链路实证**（数据 e2e_ 前缀+时间戳、全 ASCII）：
  1. POST 建 KB `e2e_minio_upload_<ts>` → R code=0；
  2. curl -F multipart 上传测试 PDF（文件名 ASCII 带 .pdf 后缀）→ R code=0；
  3. pymysql 查 `ai_knowledge_base_files` 末行 `file_path` 以 `kb/{kb_id}/` 开头且 `.pdf` 结尾；
  4. uv python：`get_storage().get_bytes(file_path)` == 上传字节；`presigned_get_url` HTTP 实取一致；
  5. 清理：DELETE 端点删 KB（软删）；`storage.remove(file_path)` 物理删对象；kill uvicorn（netstat 找 PID + taskkill //F //T //PID）。
- [ ] **3.5 handoff 记账**：`docs/handoff/ai.md` 既有「向量写入与 MySQL 写入不在同一事务」条目扩为外部存储一致性欠账（标题含 MinIO 原件对象；待办补上传顺序 MinIO→MySQL→Milvus 的后置失败孤儿对象，随检索轮统一治理）。

### Task 4: 收尾核查

- [ ] **4.1** 全量 pytest（**工程根跑**）：与开工基线一致 = 仅 reembedding 守护红（179 passed/1 failed 不新增失败；本轮新增用例全绿）。
- [ ] **4.2** 自查：WIP 两文件中 router.py 零改动、service.py 尾部 WIP 行零触碰（git diff 逐行核）；守护测试零改动。
- [ ] **4.3** 实施报告：文件清单、pytest 计数、3.4 各步真实输出（file_path/字节数/URL 状态码）、清理证明。

---

## 验收标准

1. pytest 与基线一致（仅 WIP 守护红）；新增 3 用例绿。
2. 真链路：上传→对象存在且字节一致→presign HTTP 实取一致→file_path 落库为对象键；测试数据与对象清理净。
3. WIP 零触碰；契约/DDL/前端/守护零改动；handoff 记账更新。

## 移交备忘

- 读取方（下载/预签名端点）尚未存在——未来文件下载轮直接 `presigned_get_url(row.file_path)`。
- 孤儿对象与孤儿向量同族，检索轮 collection 隔离时统一补偿设计（handoff 已并条记账）。
