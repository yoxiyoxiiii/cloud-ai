# cloud-base-ai Nacos 注册 Implementation Plan（2026-10-12）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** cloud-base-ai 注册进 Nacos（临时实例 cloud-base-ai@127.0.0.1:8010，DEFAULT_GROUP@public）+ 网关 `/ai/**` 路由打通 + Python 侧 R<T> 统一返回基建；验证链全绿（注册可见 healthy → 强杀剔除 → 重启恢复 → 经网关带 token 调 AI R 200）。

**Architecture:** spec 见 `docs/superpowers/specs/2026-10-12-cloud-base-ai-nacos-registration-design.md`（D1-D12 决策记录 + 工程规范首版）；契约见 `docs/superpowers/contracts/2026-10-12-cloud-base-ai-nacos-registration-api.md`。SDK gRPC 注册（redo 自愈）+ lifespan 生命周期（startup 注册 / shutdown 先 deregister 再关连接）+ 注册失败告警降级（D7）。

**Tech Stack:** uv 0.13.0（绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`）/ Python 3.11.17 / FastAPI + pydantic-settings（传递依赖，零新增运行时包）/ nacos-sdk-python 3.2.0（import `v2.nacos`）/ pytest（**新增 dev 依赖**）/ 网关侧重编 Maven。

**环境事实（backend-agent 必读）：**
- 工作目录：`D:/source/cloud-ai/cloud-base-ai`（bash 每次调用独立，所有命令块自带 cd）；uv 绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`（不在 Git Bash PATH 假设内）；mvn 绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`
- **Nacos：原生 Windows 进程**（非 Docker），127.0.0.1:8848 HTTP + 9848/9849 gRPC，v2.4.3 standalone auth_enabled=false——本轮记档修正既有"Docker"记载
- Redis 6379（Docker）与 MySQL 127.0.0.1:3306（原生）常驻不动；登录链路依赖它们
- 端口：AI 一律 8010（8000 被既有 base-ais 服务占用）；sso 9201 / system 9202 / 网关 18080（**网关最后启动**）；本轮不起 bpmn（不参与链路）
- Windows 陷阱：Git Bash 后台 `$!` 是 MSYS 包装 PID——停服用 `netstat -ano | grep LISTENING | grep :<port>` 取真实 PID 再 `taskkill //F //PID <pid>`；Git Bash curl 发中文 JSON 是 GBK 会 500——本轮 curl 入参全 ASCII，**断言一律锚 ASCII 片段**（code/data，msg 中文在 console 显示乱码不影响）
- **grpc.aio 在 Windows 要求 SelectorEventLoop**（uvicorn 默认 Proactor 会挂）——main.py 模块顶层 `sys.platform=='win32'` 时 `asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())`（uvicorn 先 import app 模块后建 loop，顶层设置生效；阶段一 SDK 探测同款实证）
- 登录凭据：`admin / admin123`（e2e 主链同款，sys_user 种子 id=1）

**红线：** 基础设施只注册不改造（Nacos 注册属正常使用；不重启/不改 Nacos/Redis/MySQL）；测试断言完成后**清理注册实例并停全部进程**（服务验收收口即停，四端口 9201/9202/18080/8010 全空）；Java 侧只改网关 application.yml 一条路由（白名单零触碰）；不动 cloud-web；测试数据无 DB 涉入。

---

## 文件清单（预期全部改动）

- Create: `cloud-base-ai/src/cloud_base_ai/config.py`
- Create: `cloud-base-ai/src/cloud_base_ai/response.py`
- Create: `cloud-base-ai/src/cloud_base_ai/exceptions.py`
- Create: `cloud-base-ai/src/cloud_base_ai/exception_handler.py`
- Create: `cloud-base-ai/src/cloud_base_ai/nacos_registration.py`
- Modify: `cloud-base-ai/src/cloud_base_ai/main.py`（lifespan + handler 注册 + 两新端点；`/` 与 `/items/{id}` 原样保留）
- Create: `cloud-base-ai/tests/__init__.py`（空文件，包化测试目录）
- Create: `cloud-base-ai/tests/test_response.py`
- Create: `cloud-base-ai/tests/test_exception_handler.py`
- Create: `cloud-base-ai/tests/test_settings.py`
- Create: `cloud-base-ai/tests/test_nacos_registration.py`
- Modify: `cloud-base-ai/pyproject.toml`（`[dependency-groups] dev` 增 pytest）
- Modify: `cloud-base-ai/uv.lock`（uv 自动重算）
- Modify: `cloud-base/cloud-gateway/src/main/resources/application.yml`（routes 末尾增 cloud-base-ai 路由，spec D1 原文）
- Modify: `cloud-base-ai/README.md`（追加「Nacos Registration」小节）

## 契约/DDL 记档

零表变更、零 DDL。API 契约 = `docs/superpowers/contracts/2026-10-12-cloud-base-ai-nacos-registration-api.md`（网关路由约定 + R<T> 结构 + /demo/ping + /health）；错误码零新增（复用 Java 1xxx 通用段 200/500/1001/1002）；零权限标识（无管理端点）。

---

### Task 1: dev 依赖 pytest

**Files:**
- Modify: `cloud-base-ai/pyproject.toml`
- Modify: `cloud-base-ai/uv.lock`

- [ ] **Step 1: uv add --dev**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe add --dev pytest && C:/Users/jy-root/.local/bin/uv.exe lock --check
```

Expected: Resolved 成功；pyproject 出现 `[dependency-groups] dev = ["pytest>=..."]`；lockfile up-to-date。**运行时依赖零新增**（pydantic-settings 经 fastapi[standard] 传递已在 lock，spec D5）。

### Task 2: Python 侧六文件（含 main.py 改造）

**Files:** 见文件清单 Create/Modify 前六项。**编码遵循 spec §5 工程规范首版**（类型注解全量、方法 ≤50 行、禁裸 dict 返回——豁免清单外）。

- [ ] **Step 1: config.py**——`NacosSettings(BaseModel)` 五字段默认值（server_addr="127.0.0.1:8848" / namespace_id="" / group_name="DEFAULT_GROUP" / service_name="cloud-base-ai" / ip="127.0.0.1"）；`Settings(BaseSettings)` 持 `nacos` 嵌套 + `app_port: int = 8010`；`model_config = SettingsConfigDict(env_prefix="CLOUD_BASE_AI_", env_nested_delimiter="__")`；`@lru_cache get_settings()`。
- [ ] **Step 2: response.py**——`R(BaseModel, Generic[T])`：`code:int=200 / msg:str="操作成功" / data:T|None=None`；`ok(data=None)` → 200；`fail(msg, code=1002)`（一个方法覆盖 Java R.fail(String)/R.fail(int,String) 两形态）。码值逐项对齐 spec D4 表。
- [ ] **Step 3: exceptions.py**——`BusinessException(msg, code=1002)`。
- [ ] **Step 4: exception_handler.py**——`register_exception_handlers(app)` 注册三 handler，全部 `JSONResponse(status_code=200)`：BusinessException → warn 日志 + `R.fail(msg, code)`；RequestValidationError → `".".join(loc 数字段路径) + " " + msg + "; "` 拼接 → 1001（对齐 Java MethodArgumentNotValidException 口径）；Exception 兜底 → `logger.exception` 留痕 + code 500「系统异常，请稍后重试」（不泄细节）。
- [ ] **Step 5: nacos_registration.py**——`NacosRegistration`：

```python
class NacosRegistration:
    def __init__(self, settings: Settings) -> None: ...      # _service=None, _registered=False
    async def start(self) -> bool:
        # ClientConfigBuilder().server_address(...).namespace_id(...).log_level("ERROR").build()
        # create_naming_service → register_instance(RegisterInstanceParam(
        #   ip=settings.nacos.ip, port=settings.app_port, service_name=..., group_name=...,
        #   metadata={"lang": "python", "framework": "fastapi", "version": "0.1.0"}))   # ephemeral 默认 True，显式传亦可
        # 成功 log.info + _registered=True；异常 log.error("nacos register failed, degraded: ...") 不抛（D7）
    async def stop(self) -> None:
        # try: _registered 时 deregister_instance(DeregisterInstanceParam(ip, port, service_name, group_name))
        #      （异常仅 log.error）finally: await _service.shutdown()（D8 时序：先注销后关连接）
    @property
    def registered(self) -> bool: ...
```

- [ ] **Step 6: main.py 改造**——模块顶层（import 之后、app 定义之前）：

```python
import sys
if sys.platform == "win32":
    import asyncio
    asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())  # grpc.aio Windows 硬要求
```

`@asynccontextmanager lifespan(app)`：start() 注册 → `app.state.nacos = reg` → `yield` → `stop()`。`app = FastAPI(lifespan=lifespan)`；`register_exception_handlers(app)`；`/demo/ping` 返回 `R.ok("cloud-base-ai running")`（函数签名 `-> R[str]`）；`/health(request: Request)` 返回 `{"status": "UP", "nacos_registered": <reg and reg.registered>}`（裸 JSON，D11 豁免）；`/` 与 `/items/{item_id}` 原样保留（上轮验收锚点）。

- [ ] **Step 7: 静态冒烟**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run python -c "
from fastapi.testclient import TestClient
from cloud_base_ai.main import app
c = TestClient(app)  # 不用 with：不触发 lifespan，不碰真 Nacos
print('ping:', c.get('/demo/ping').json())
print('root:', c.get('/').json())"
```

Expected: `ping: {'code': 200, 'msg': '操作成功', 'data': 'cloud-base-ai running'}`（console 中文乱码不影响，code/data 为准）；`root: {'Hello': 'World'}`。

### Task 3: 单测五文件（零基础设施依赖，SDK 一律 monkeypatch）

**Files:** tests/ 下五个文件。

- [ ] **Step 1: test_response.py**——`R.ok("x")` code=200/data="x"；`R.fail("bad")` code=1002/data=None；`R.fail("bad", 500)` code=500；msg 默认「操作成功」。
- [ ] **Step 2: test_exception_handler.py**——独立 `FastAPI()` 小 app（**不挂 lifespan**）+ `register_exception_handlers`：造三路由分别抛 `BusinessException("自定义", 1002)` / 触发路径参数校验失败（`/x/{n:int}` 传非数字）/ 抛 `RuntimeError`。断言三响应 **HTTP 均为 200** 且 body.code 分别 1002 / 1001（msg 含字段名）/ 500（msg 恒「系统异常，请稍后重试」不含 RuntimeError 细节）。
- [ ] **Step 3: test_settings.py**——默认值五项断言；`monkeypatch.setenv("CLOUD_BASE_AI_NACOS__IP", "10.0.0.9")` → `Settings().nacos.ip == "10.0.0.9"`；同法验 `CLOUD_BASE_AI_NACOS__SERVER_ADDR` 与 `CLOUD_BASE_AI_APP_PORT`（嵌套字段双下划线——单 `_` 对含下划线内层字段静默失效，实现期修正记档见 spec D5）。
- [ ] **Step 4: test_nacos_registration.py**——模块级 `FakeNacosNamingService`（记录调用序列与 register 参数；`create_naming_service` 为 async classmethod 工厂）；`monkeypatch.setattr("cloud_base_ai.nacos_registration.NacosNamingService", FakeNacosNamingService)` 后 `with TestClient(app) as c:`（**with 触发 lifespan**）断言：
  - register 参数：ip="127.0.0.1"、port=8010、service_name="cloud-base-ai"、group_name="DEFAULT_GROUP"、ephemeral=True、metadata 三键齐；
  - with 退出后调用序列恰为 `["register_instance", "deregister_instance", "shutdown"]`（D8 时序）；
  - 降级分支：Fake 的 register_instance 抛 `RuntimeError` → lifespan 启动**不抛**、`c.get("/health").json()["nacos_registered"] is False`；
  - stop 幂等：`app.state.nacos.stop()` 二次调用不抛。

- [ ] **Step 5: 全量跑**

```bash
cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe run pytest tests/ -q
```

Expected: 全绿（约 15+ 断言，0 failed 0 error）。任何真 Nacos 网络调用出现在测试中 = 用例写错（必须 mock）。

### Task 4: 网关路由 yml + 重编

**Files:**
- Modify: `cloud-base/cloud-gateway/src/main/resources/application.yml`

- [ ] **Step 1: Edit 追加路由**（cloud-bpmn 路由块之后、globalcors 之前，缩进对齐既有路由）

```yaml
        - id: cloud-base-ai
          uri: lb://cloud-base-ai
          predicates:
            - Path=/ai/**
          filters:
            - StripPrefix=1
```

- [ ] **Step 2: 重编网关（含上游 common 模块，不跳测试）**

```bash
D:/software/apache-maven-3.8.4/bin/mvn -f D:/source/cloud-ai/cloud-base/pom.xml clean install -pl cloud-gateway -am
```

Expected: `BUILD SUCCESS`（输出乱码看此行与行号即可）。失败停止上报，不重试。

### Task 5: 真机验证链 + 收口（**断言后清理注册实例，服务验收收口即停**）

- [ ] **Step 1: 起 AI（8010）+ 注册断言**

```bash
cd D:/source/cloud-ai/cloud-base-ai && (C:/Users/jy-root/.local/bin/uv.exe run uvicorn cloud_base_ai.main:app --host 127.0.0.1 --port 8010 > /tmp/ai.log 2>&1 &) ; sleep 10; curl -s http://127.0.0.1:8010/health; echo; curl -s "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=cloud-base-ai"
```

Expected: health 返回 `"status":"UP","nacos_registered":true`；instance/list JSON 含 `"ip":"127.0.0.1"`、`"port":8010`、`"healthy":true`、`"ephemeral":true`、`"lang":"python"`（metadata）。health 的 registered=false = 注册失败降级（D7），看 /tmp/ai.log 排查后如实上报。

- [ ] **Step 2: 本地 R 断言**

```bash
curl -s http://127.0.0.1:8010/demo/ping | grep -o '"code":200'; curl -s http://127.0.0.1:8010/demo/ping | grep -o 'cloud-base-ai running'
```

Expected: 两片段各命中一次。

- [ ] **Step 3: 强杀剔除（崩溃保护）**

```bash
netstat -ano | grep LISTENING | grep :8010   # 取 PID
taskkill //F //PID <PID>
sleep 20; curl -s "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=cloud-base-ai" | grep -o '"healthy":false'
sleep 15;  curl -s "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=cloud-base-ai" | grep -o '"hosts":\[\]'
```

Expected: `hosts:[]` 剔除断言命中（healthy:false 中间态 grep 可能空手——不影响判定）。**实测修正记档（2026-10-12 执行轮）**：gRPC 模式断连即剔除（~5s hosts:[]，两轮一致），无 15s 不健康窗口；原 20s/35s 时序为 HTTP 心跳模式口径。

- [ ] **Step 4: 重启恢复（重注册）**

复跑 Step 1 起 AI → instance/list 恢复 `"healthy":true`。

- [ ] **Step 5: 网关全链路（sso → system → 网关最后；AI 保持运行）**

```bash
(java -jar D:/source/cloud-ai/cloud-base/cloud-sso/target/cloud-sso-1.0.0-SNAPSHOT.jar > /tmp/sso.log 2>&1 &)
(java -jar D:/source/cloud-ai/cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar > /tmp/sys.log 2>&1 &)
sleep 30
(java -jar D:/source/cloud-ai/cloud-base/cloud-gateway/target/cloud-gateway-1.0.0-SNAPSHOT.jar > /tmp/gw.log 2>&1 &)
sleep 15
# 登录取 token（入参纯 ASCII）
TOKEN=$(curl -s -X POST http://localhost:18080/sso/auth/login -H "Content-Type: application/json" -d '{"account":"admin","password":"admin123"}' | grep -o '"accessToken":"[^"]*"' | cut -d'"' -f4)
echo "token len: ${#TOKEN}"
# 经网关调 AI
curl -s http://localhost:18080/ai/demo/ping -H "Authorization: Bearer $TOKEN" | grep -o '"code":200'
curl -s http://localhost:18080/ai/demo/ping -H "Authorization: Bearer $TOKEN" | grep -o 'cloud-base-ai running'
# 无 token 401（认证链路继承）
curl -s -o /dev/null -w "%{http_code}\n" http://localhost:18080/ai/demo/ping
# 既有面抽查（既有路由零变更）
curl -s http://localhost:18080/system/demo/ping -H "Authorization: Bearer $TOKEN" | grep -o '"code":200'
```

Expected: token len > 0；AI 双片段命中；401（真实 HTTP 状态码）；system demo 200。任何一步失败看对应 /tmp/*.log 与 /tmp/ai.log。

- [ ] **Step 6: 收口（清理实例 + 停全部进程）**

```bash
for P in 8010 9201 9202 18080; do netstat -ano | grep LISTENING | grep ":$P" | awk '{print $NF}' | sort -u | while read pid; do taskkill //F //PID $pid; done; done
netstat -ano | grep LISTENING | grep -E ":8010|:9201|:9202|:18080"   # 期望空输出
sleep 35; curl -s "http://127.0.0.1:8848/nacos/v1/ns/instance/list?serviceName=cloud-base-ai" | grep -o '"hosts":\[\]'   # 注册实例零残留（剔除路径收口）
```

Expected: 四端口全空；cloud-base-ai hosts 空。Nacos/Redis/MySQL 不动。

### Task 6: README 小节

- [ ] Read `cloud-base-ai/README.md` 后文件末尾追加：

```markdown
## Nacos Registration

- 服务名 `cloud-base-ai`（临时实例，DEFAULT_GROUP@public），注册/心跳/断线自愈由 nacos-sdk-python gRPC 承载；停机先 deregister 再关连接（lifespan）。
- 配置 env 覆盖（pydantic-settings，前缀 `CLOUD_BASE_AI_`，嵌套分隔 `__`）：`NACOS__SERVER_ADDR` / `NACOS__NAMESPACE_ID` / `NACOS__GROUP_NAME` / `NACOS__SERVICE_NAME` / `NACOS__IP` / `APP_PORT`。
- 网关路由：`/ai/**` → `lb://cloud-base-ai`（StripPrefix=1，JWT 认证链路继承）；本地冒烟 `curl :8010/demo/ping`；设计见 docs/superpowers/specs/2026-10-12-cloud-base-ai-nacos-registration-design.md。
```

### 回滚方式

```bash
cd D:/source/cloud-ai && git checkout -- cloud-base-ai cloud-base/cloud-gateway/src/main/resources/application.yml
C:/Users/jy-root/.local/bin/uv.exe sync --project D:/source/cloud-ai/cloud-base-ai
D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-gateway -am
```

已提交则 `git revert`。Nacos 残余实例最长 30s 自动剔除，无需手动清。

---

## 移交后续阶段的备忘

1. **真实断连自愈演练未做**（需断 Nacos/断网窗口，触碰基础设施红线）：SDK redo 机制以源码论证记档（spec D7）；有运维窗口时人工演练一次（起 AI → 停 Nacos → 起 Nacos → 观察 redo 自动补注册）。
2. **`/ai/demo` 免鉴权不对称**：与 Java 三同胞 demo 免 token 冒烟不同，AI demo 需 JWT（网关白名单是 Java 代码改动，本轮范围外）——若需对称，立项改 `AuthGlobalFilter.WHITELIST` 增 `/ai/demo`。
3. **首个 `/inner` 端点约定**：Java 服务免网关直调 AI 时，必须与网关 inner-block-ai 屏蔽路由同任务落地（契约 §5）。
4. **首个业务端点轮**：X-User-* 消费约定（信任网关）、业务错误码段（如需 Python 专属段届时议定）、VO 模型规范全量适用——本轮仅 /demo/ping 与 /health 两个非业务端点。
5. **跨机/容器化部署**：改 `CLOUD_BASE_AI_NACOS__IP` 为物理网卡 IP（对齐 xxl-job B7 钉 IP 先例），届时评估防火墙与 9848 可达性。
6. **Windows loop 策略耦合**：main.py 顶层 SelectorEventLoopPolicy 是 grpc.aio 硬要求；未来引入依赖 Proactor 的库（asyncio 子进程管道等）需重评。
7. **e2e 回归欠账**：本轮零既有行为变更不强制；下轮凡触网关路由/认证面的改动须跑全量 e2e。
8. **多 worker 形态未验**：uvicorn 多 worker 会多进程各自注册（同 ip:port 幂等 upsert，理论无碍）；真要横向扩容时专项验证。
9. **LLM key 与 Nacos 配置中心消费**：config 通路已实证可用（上轮+本轮双实证），留 LLM 供应商轮一并立项（上轮移交 #4 仍有效）。
10. **CLAUDE.md 修正随主控 ④ 执行**（spec §10 建议）：Nacos 原生进程修正句 + 模块拓扑/端口 8010 增量描述。

---

## 给 backend-agent 的任务清单（自包含执行卡，可直接贴走）

> 你在 Windows Git Bash 执行。bash 每次调用相互独立：每个命令块必须自带 `cd`。uv 绝对路径 `C:/Users/jy-root/.local/bin/uv.exe`；mvn 绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`。工作目录 `D:/source/cloud-ai/cloud-base-ai`（Task 4/5 例外，命令自带路径）。
>
> **必读文档**：设计 `D:/source/cloud-ai/docs/superpowers/specs/2026-10-12-cloud-base-ai-nacos-registration-design.md`（D1-D12 + §5 工程规范——**编码规范以此为准**）；契约 `D:/source/cloud-ai/docs/superpowers/contracts/2026-10-12-cloud-base-ai-nacos-registration-api.md`；本计划同目录 `plans/2026-10-12-cloud-base-ai-nacos-registration.md`。
>
> **环境事实**：Nacos **原生 Windows 进程** 127.0.0.1:8848（gRPC 9848/9849，v2.4.3 无鉴权）；Redis 6379 / MySQL 3306 常驻不动；AI 端口 8010（8000 被占勿用）；sso 9201 / system 9202 / 网关 18080 最后起；本轮不起 bpmn。Windows 陷阱：停服 `netstat -ano | grep LISTENING | grep :<port>` 取 PID → `taskkill //F //PID <pid>`（`$!` 不可信）；curl 断言只锚 ASCII 片段（中文 console 乱码无碍）；**grpc.aio 需 SelectorEventLoop——main.py 顶层设 policy（Task 2 Step 6 骨架已含，勿删）**。登录凭据 admin/admin123。
>
> **红线**：基础设施只注册不改造（Nacos 注册属正常使用）；**测试断言完成后清理注册实例并停全部四进程**（9201/9202/18080/8010 全空 + Nacos instance/list hosts 空）；Java 侧只改网关 application.yml 一条路由（白名单零触碰）；不动 cloud-web；单测零真基础设施调用（SDK 一律 monkeypatch）。

1. **dev 依赖**：`cd D:/source/cloud-ai/cloud-base-ai && C:/Users/jy-root/.local/bin/uv.exe add --dev pytest && C:/Users/jy-root/.local/bin/uv.exe lock --check` → Resolved 成功 + pyproject 出现 `[dependency-groups] dev`。运行时依赖零新增。
2. **六文件**（文件清单与逐文件接口签名严格按计划 Task 2 Step 1-6 骨架；`/` 与 `/items/{id}` 原样保留）：config.py（NacosSettings 五字段默认值 + Settings + env 前缀 CLOUD_BASE_AI_ 嵌套分隔 "__" + lru_cache get_settings）、response.py（R 泛型模型 ok/fail，码值 200/1002/500 对齐 D4 表）、exceptions.py（BusinessException）、exception_handler.py（三 handler 全 HTTP 200：BusinessException→fail 码、RequestValidationError→1001 字段拼接、Exception 兜底→500「系统异常，请稍后重试」+ logger.exception）、nacos_registration.py（NacosRegistration start/stop/registered——log_level("ERROR") 抑制 SDK 噪音；stop 的 try/finally 保证 deregister 失败也 shutdown）、main.py（顶层 selector policy + lifespan + register_exception_handlers + /demo/ping 返 `R.ok("cloud-base-ai running")` + /health 裸 JSON）。
3. **静态冒烟**：计划 Task 2 Step 7 命令 → `ping: {'code': 200, ...'data': 'cloud-base-ai running'}` + `root: {'Hello': 'World'}`。
4. **单测五文件**（计划 Task 3 Step 1-4 断言点全量落地；exception_handler 测试用独立无 lifespan 小 app；nacos_registration 测试 monkeypatch `cloud_base_ai.nacos_registration.NacosNamingService` + `with TestClient(app)` 触发 lifespan 断言参数/顺序/降级/幂等四组）→ `uv.exe run pytest tests/ -q` 全绿。测试中出现真网络调用 = 用例写错。
5. **网关路由 + 重编**：Edit `D:/source/cloud-ai/cloud-base/cloud-gateway/src/main/resources/application.yml` 在 cloud-bpmn 路由后追加计划 Task 4 Step 1 四行（id cloud-base-ai / lb://cloud-base-ai / Path=/ai/** / StripPrefix=1，缩进对齐既有路由）→ `D:/software/apache-maven-3.8.4/bin/mvn -f D:/source/cloud-ai/cloud-base/pom.xml clean install -pl cloud-gateway -am` → BUILD SUCCESS（失败停止上报不重试）。
6. **真机验证链**（计划 Task 5 Step 1-6 逐字执行）：起 AI→health nacos_registered:true + OpenAPI 实例五断言（ip/port/healthy/ephemeral/metadata）→ 本地 /demo/ping 双片段 → 强杀→hosts:[]（实测 ~5s 即剔除——gRPC 断连即时；healthy:false 中间态不可观测） → 重启恢复 healthy:true → 起 sso+system（sleep 30）+网关（sleep 15）→ admin/admin123 登录取 accessToken（grep -o 提取）→ `/ai/demo/ping` 带 token code:200 + data 片段、无 token HTTP 401、`/system/demo/ping` 带 token 200（既有面抽查）→ **收口**：taskkill 四端口全部进程 + netstat 确认空 + sleep 35 后 instance/list hosts:[]（实例零残留）。任何一步失败：看 /tmp/ai.log、/tmp/sso.log、/tmp/sys.log、/tmp/gw.log 排查后重跑该步，如实回报。
7. **README 追加**：Read 后在 `D:/source/cloud-ai/cloud-base-ai/README.md` 末尾照贴计划 Task 6 小节（不提交——git 操作归主控）。
8. **回报口径**：三文档外另附——pytest 输出（全绿计数）、真机验证链每步关键输出（health/instance-list/401/200 片段）、四进程收口 netstat 空输出、`git status --short` 留档（HEAD 有用户中途 GUI 提交即如实回报勿覆盖）。
