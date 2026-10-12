# cloud-base-ai RAG 包工程目录骨架实施计划（2026-10-12）

> 轨道：**纯后端（Python 单端）**——仅 backend 章节，无前端章节、无 Java 侧改动。设计文档：`docs/superpowers/specs/2026-10-12-cloud-base-ai-rag-skeleton-design.md`（D1-D6 + §5 分包细则 + §6 文件清单与 docstring 定稿文案——**文案以 spec §6 为权威，逐字使用**）。
>
> 本轮零编码：只建 rag/ 子包 10 文件（各含一行 docstring）+ README 增段 + 验证五项。不建 contracts 文件、无错误码/权限标识/DDL（spec §1 预判记档）。

## 后端任务

### Task 1 建 rag/ 子包（10 文件，每文件仅一行 docstring）

文件清单与 docstring 文案 = spec §6 表格逐字（此处不重复，防两处漂移）。执行要点：

1. 目录 `cloud-base-ai/src/cloud_base_ai/rag/`，10 文件：`__init__.py / router.py / service.py / schemas.py / ingestion.py / chunking.py / embedding.py / vectorstore.py / retrieval.py / generation.py`。
2. 每文件唯一内容 = 模块 docstring 一行（spec §6 第三列原文），文件内**禁止** import / 函数 / 类 / 常量 / `__all__`。
3. 编码 UTF-8（无 BOM）、行尾 LF；docstring 行末换行收尾。
4. 不建 `.gitkeep`、不建 `tests/rag/`、不改 pyproject / uv.lock / main.py / .gitignore。

**验收**：`find src/cloud_base_ai/rag -type f | sort` 恰好 10 行且文件名与 spec §6 一致；抽查任一文件内容 = 一行 docstring。

### Task 2 README 增「RAG Package Skeleton」节

在 `cloud-base-ai/README.md` 末尾（「Nacos Registration」节之后）追加（逐字）：

```markdown
## RAG Package Skeleton

- 业务域子包 `src/cloud_base_ai/rag/`（2026-10-12 骨架占位，零实现代码）：按 RAG 数据流阶段平铺——ingestion → chunking → embedding → vectorstore → retrieval → generation，横切件 router / service / schemas。
- 深度硬顶 2 层（rag/ 下无子包）；供应商预留为模块级单文件（embedding / vectorstore），config 零供应商键；分包细则见设计文档 §5。
- 依赖注意：langchain-text-splitters / langchain-community / vectorstore 实现包当前未装，RAG 功能实现轮再新增——目录骨架非可用链路。
- 设计见 docs/superpowers/specs/2026-10-12-cloud-base-ai-rag-skeleton-design.md。
```

**验收**：README 四条 bullet 与上文一致；既有两节零改动。

### Task 3 验证五项（主控定稿，全绿才算完成）

命令与期望（Git Bash；bash 调用相互独立，每块自带 `cd`；uv 绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`）：

1. **pytest 零回归**：`uv run pytest -q` → `19 passed`（不多不少，本轮零新测试）。
2. **起服冒烟 + 收口**：后台起 uvicorn（:8010）→ `curl -s http://127.0.0.1:8010/demo/ping` 含 `"code":200` 且 data 含 `cloud-base-ai running` → `curl -s http://127.0.0.1:8010/health` 含 `"nacos_registered":true` → `netstat -ano | grep LISTENING | grep :8010` 取 PID → `taskkill //F //PID <pid>`（`$!` 是 MSYS 包装 PID 不可用）→ 复核端口已释放。
3. **uv build 带走新子包**：`uv build` → `.venv/Scripts/python.exe -m zipfile -l dist/cloud_base_ai-0.1.0-py3-none-any.whl | grep "rag/"` 恰好 10 行 → 伴随项 `uv run python -c "import cloud_base_ai.rag, cloud_base_ai.rag.ingestion"` 输出 ok（注意：editable install 下 import 恒通，whl 清单才是「带走」硬证据）→ **验后 `rm -rf dist/`**（.gitignore 未含 dist/，防 git status 污染）。若 `uv build` 本身失败：属环境级新发现，如实上报，**不自行改 pyproject**（红线 ⑤）。
4. **目录树核对**：`find src/cloud_base_ai/rag -type f | sort` 与 spec §6 清单逐项核对一致。
5. **依赖零变更**：`git status --porcelain pyproject.toml uv.lock` 输出为空；全量 `git status --porcelain` 仅含预期变更（rag/ 10 文件新增 + README.md 修改，无其他）。

e2e 全量回归不强制（零网关/认证面触碰）；Java 侧零改动无需 mvn。

## 移交后续阶段的备忘

沿 spec §11 五条（RAG 实现轮必增依赖 / 供应商与 LLM key 轮 / 首个端点轮 main.py 解除零触碰 / dist 入 .gitignore 时机 / 深度硬顶突破须回架构再议），此处不重复。

---

## 给 backend-agent 的任务清单（自包含执行卡，可直接贴走）

> 你在 Windows Git Bash 执行。bash 每次调用相互独立：**每个命令块必须自带 `cd /d/source/cloud-ai/cloud-base-ai`**。uv 绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`。本轮**零编码红线**：新文件内禁止 import / 函数 / 类 / 常量；pyproject / uv.lock / main.py / .gitignore / 既有模块**零触碰**。
>
> **必读文档**：`D:/source/cloud-ai/docs/superpowers/specs/2026-10-12-cloud-base-ai-rag-skeleton-design.md`——**§6 表格第三列 = 每个文件的 docstring 定稿文案，逐字使用**（含全角标点与括号，勿改写）。
>
> **环境事实**：AI 端口 8010（8000 被占勿用）；Nacos 原生 Windows 进程 127.0.0.1:8848 常驻（起服会正常注册/强杀后 ~5s 剔除，均为既有行为）；停服纪律 `netstat -ano | grep LISTENING | grep :8010` 取 PID → `taskkill //F //PID <pid>`（`$!` 不可信）；curl 断言只锚 ASCII 片段。

**Task 1（建 10 文件）**：mkdir 后用**文件编辑工具（Write/Edit，UTF-8 保证）逐个写入**——Git Bash `cat` heredoc 写中文有 GBK 风险（CLAUDE.md 陷阱同源），下示 heredoc 仅作文件形态示意、不作推荐写入方式。每文件 = spec §6 对应 docstring 一行：

```bash
cd /d/source/cloud-ai/cloud-base-ai
mkdir -p src/cloud_base_ai/rag
cat > src/cloud_base_ai/rag/__init__.py <<'EOF'
"""RAG（检索增强生成）业务域：ingest → chunk → embed → store → retrieve → generate 管线与编排。"""
EOF
# 其余 9 文件同法：router / service / schemas / ingestion / chunking / embedding / vectorstore / retrieval / generation，docstring 逐字取 spec §6
find src/cloud_base_ai/rag -type f | sort   # 期望恰好 10 行
```

**Task 2（README）**：按本计划 Task 2 的四条 bullet 逐字追加到 `cloud-base-ai/README.md` 末尾。

**Task 3（验证五项，顺序执行全绿）**：

```bash
# ① pytest 零回归
cd /d/source/cloud-ai/cloud-base-ai
C:/Users/jy-root/.local/bin/uv.exe run pytest -q        # 期望 19 passed

# ② 起服冒烟 + 收口
C:/Users/jy-root/.local/bin/uv.exe run uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010 &
sleep 5
curl -s http://127.0.0.1:8010/demo/ping                 # 期望含 "code":200 与 cloud-base-ai running
curl -s http://127.0.0.1:8010/health                    # 期望含 "nacos_registered":true
netstat -ano | grep LISTENING | grep :8010              # 取真实 PID
taskkill //F //PID <上一步PID>
netstat -ano | grep LISTENING | grep :8010 || echo "port released"   # 期望 port released

# ③ uv build 带走新子包（验后删 dist/）
C:/Users/jy-root/.local/bin/uv.exe build
.venv/Scripts/python.exe -m zipfile -l dist/cloud_base_ai-0.1.0-py3-none-any.whl | grep "rag/"   # 期望恰好 10 行
C:/Users/jy-root/.local/bin/uv.exe run python -c "import cloud_base_ai.rag, cloud_base_ai.rag.ingestion; print('rag import ok')"
rm -rf dist/

# ④ 目录树核对：对照 spec §6 逐项
find src/cloud_base_ai/rag -type f | sort

# ⑤ 依赖零变更
git status --porcelain pyproject.toml uv.lock           # 期望空输出
git status --porcelain                                  # 期望仅 rag/ 10 文件 + README.md
```

**完成判据**：五项全绿 + 目录树与 spec §6 一致；之后停主控做规格审查（审查通过前不 commit）。
