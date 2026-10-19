# AI 对话会话管理实施计划（2026-10-19）

> 全栈轮：后端章（cloud-base-ai）与前端章（cloud-web + cloud-e2e）**两章独立可并行**——前端以契约
> `docs/superpowers/contracts/2026-10-19-ai-chat-session-api.md` 为唯一对齐物 mock 并行，真机联调依赖
> P1/P6。设计依据：`docs/superpowers/specs/2026-10-19-ai-chat-session-design.md`（决策代号 D1-D11
> 均在该文档定义）。
>
> 前置事实：工作区有未提交草稿（redis_store.py 已暂存 / 另两文件未跟踪）——P2 回退；
> 本机 docker 已有镜像 `redis:7` 与单机容器 `redis:6379`（后者不动，红线）。

## 后端章（cloud-base-ai）

### P1（环境）Redis Cluster 容器 + 配置默认端口

- 改动：
  - 起容器（命令见设计 §5，逐字可用）：`docker run -d --name redis-cluster --restart unless-stopped -p 127.0.0.1:7000:7000 -p 127.0.0.1:17000:17000 -v redis-cluster-data:/data redis:7 redis-server --port 7000 --cluster-enabled yes --cluster-config-file nodes.conf --cluster-node-timeout 5000 --cluster-announce-ip 127.0.0.1 --cluster-announce-port 7000 --cluster-announce-bus-port 17000 --appendonly yes`
  - 建簇（单节点不能用 `redis-cli --cluster create`——该工具强制 ≥3 主节点，实证记档设计 §5）：
    `docker exec redis-cluster sh -c 'redis-cli -p 7000 cluster addslots $(seq 0 16383)'`
  - `cloud-base-ai/src/cloud_base_ai/config.py`：`RedisSettings.port` 默认 6379 → **7000**（注释同步：
    指向 redis-cluster 容器；6379 单机容器归 Java 侧，互不影响）；
  - `cloud-base-ai/tests/test_settings.py`：`test_redis_defaults` 断言 port==7000（docstring 同步）；
  - `cloud-base-ai/.env.example`：补 Redis 段注释（`CLOUD_BASE_AI_REDIS__HOSTS/PORT/PASSWORD` 示例，
    默认 7000 零配置即连）；
  - `CLAUDE.md` 端口段补一行：redis-cluster 容器 7000/17000（单节点集群，AI 消息存储；6379 单机容器
    归 Java 会话，两者并存）。
- 验收：`docker exec redis-cluster redis-cli -p 7000 cluster info` → `cluster_state:ok`、
  `cluster_known_nodes:1`；`C:/Users/jy-root/.local/bin/uv.exe run --project cloud-base-ai python
  cloud-base-ai/scripts/smoke/smoke_redis.py` 输出 SMOKE PASS（known_nodes=1）；
  `docker start/stop redis`（6379）后 Java sso 登录冒烟一次不受影响；pytest 全绿。
- 依赖：无（首要任务）。

### P2（清理）工作区草稿回退

- 改动（三文件删除，随本轮 feat commit 出删除记录）：
  - `git restore --staged cloud-base-ai/src/cloud_base_ai/rag/redis_store.py`（解除暂存）后删除该文件；
  - 删除 `cloud-base-ai/tests/test_rag_redis_store.py`、`cloud-base-ai/scripts/smoke/smoke_redis_list.py`；
  - `rag/service.py` 的坏接线（`stream_chat_answer` 内无参 `redis_store.list_messages()` 调用等）
    **不单独回退**——P6 重写自然覆盖，验收以 P6 后全量测试为准。
  - f76b35c 已入库的 `redis_client.py`/`config.py` redis 段/`smoke_redis.py`/`test_settings.py`
    为活基础设施，**保留**（拍板口径）。
- 验收：`git status` 无上述三文件残留；全量 pytest 绿（此时 service.py 坏接线已随删除 import 报错——
  若 P6 未先行，需临时移除该 import 与调用行后再跑，P6 落地后以重写版为准）。
- 依赖：与 P1 并行可做。

### P3（Python）DDL + 实体 + DAO

- 改动：
  - 新建 `cloud-base/scripts/sql/2026-10-19-ai-chat-session.sql`（增量，设计 §3.1 逐字）+
    `cloud-base/scripts/sql/cloud_ai.sql` 基线追加同表；pymysql utf8mb4 落库（沿中文入库规则）；
  - `cloud-base-ai/src/cloud_base_ai/rag/entities.py`：+`AiChatSession`（Base+AuditMixin+
    SoftDeleteMixin；id 沿 AiKbGrant 的 `BigInteger().with_variant(Integer, "sqlite")` 先例；
    `title` server_default `'新对话'`；类常量 `DEFAULT_TITLE = "新对话"`；每列 comment=——守护测试硬性）；
  - `cloud-base-ai/src/cloud_base_ai/rag/dao.py`：+五动词 `save_session` / `find_session_by_id`
    （deleted=0 活记录视角）/ `page_list_sessions(user_id, page, size)`（id DESC + total，`_clamp_page`
    复用）/ `update_session`（审计两值+merge+flush）/ `delete_session_by_id`（软删+审计两值，返回
    rowcount）——口径对齐既有 kb 动词（session 显式传参、flush 不提交、异常零转译）；
  - `cloud-base-ai/tests/test_rag_dao.py` / `test_rag_entities.py`：扩展用例（每动词≥1、软删不可见、
    审计回填、分页钳制）。
- 验收：DDL 落库后 pymysql 回查表结构含 `idx_user`；pytest 全绿（含 guard）。

### P4（Python）Redis 消息层 message_store

- 改动：
  - 新建 `cloud-base-ai/src/cloud_base_ai/rag/message_store.py`：`RAG_MESSAGE_KEY_PREFIX
    "rag:messages:"`、`MAX_MESSAGES_PER_SESSION = 100`；三函数 `append_message(session_id, role,
    content)`（LPUSH+LTRIM 0..99，**无 EXPIRE**——设计 D4）/ `list_messages(session_id,
    limit=None)`（升序，空会话短路返 []）/ `delete_messages(session_id)`（DEL，键不存在 no-op）；
    载荷 `RagChatMessage.model_dump_json()` 序列化 / `model_validate_json` 还原；模块 docstring 写明
    键结构与无 TTL 决策一句话+设计文档路径；
  - 新建 `cloud-base-ai/tests/test_rag_message_store.py`：FakeRedis 替身（沿被撤草稿替身思路重写），
    覆盖：读写往返升序 / limit 取尾 / 超上限恒 100 丢最旧 / **无 TTL（断言不调 expire）** / 空会话 /
    删除幂等。
- 验收：pytest 全绿；guard 绿（模块属 rag/ 下外部系统封装，不进 dao——ai-spec §一）。

### P5（Python）schemas + service 编排

- 改动：
  - `rag/schemas.py`：+`ChatSendReq{session_id: int ge=1 strict, question: str 1..4000 + field_validator
    strip 非空}`、`ChatSessionVo(VoTimeMixin){id,title,create_time}`、
    `SessionMessageVo{role,content}`、`SessionMessagesVo{messages: list[SessionMessageVo]}`；
    **删 `RagChatDto`**（`RagChatMessage` 保留为消息载荷模型）；`test_rag_schemas.py` 同步；
  - `rag/service.py`：
    - 新增 `create_session()`（身份缺失→1001；save+refresh；VO 转换在 service）、
      `page_list_sessions(page, size)`（身份缺失/None→空集）、
      `list_session_messages(session_id)`（校验→message_store 读→VO 转换）、
      `delete_session(session_id)`（校验→**Redis DEL 先**→`@transactional` 软删，rowcount=0 兜底 5012；
      Redis 异常 log.error 后上抛 500，MySQL 未动可重试——设计 D5）、
      归属校验私有助手 `_load_owned_session(session_id)`（@transactional 读：None→5012；
      user_id ≠ identity.user_id（含 None）→5013）、首问改题助手（仅 title==DEFAULT_TITLE 时
      question[:16] 更新，@transactional）；
    - **重写** `chat_answer` / `stream_chat_answer`（入参 ChatSendReq）：编排序 = 校验事务 → 问落
      Redis → 首问改题事务 → 尾 history_limit 条历史（含刚落的问）→ LLM → 答收口落档（设计 D7/D8）；
      流式 generator try/finally 收口统一落答（半截照落、零 token 不落），finally 落档失败 log.error
      后原样上抛；LLM 异常转译 5001 / 工厂 5002 透传——既有语义不动；
    - 错误码常量 `CODE_SESSION_NOT_EXISTS=5012` / `CODE_SESSION_NO_ACCESS=5013`（文案对齐契约 §1），
      注释标「AI 段下一可用 5014」；
  - `cloud-base-ai/tests/test_rag_service.py`：新增/重写用例——5012/5013/身份缺失三态、标题首问
    生成与默认名不改、双清顺序（monkeypatch message_store 断言 DEL 先于软删）、问先落答收口落、
    LLM 失败问悬置无答、流中断半截答保留（模拟消费者提前关闭 generator）、`RagChatDto` 旧用例删除。
- 验收：pytest 全绿；方法体≤50 行目标（编排方法拆助手）；service 不直接 new Redis 客户端（消费
  message_store）。

### P6（Python）router 挂接 + chat 端点换轨

- 改动：
  - `rag/router.py`：+`session_router = APIRouter(prefix="/rag/chat/sessions",
    dependencies=[Depends(require_perms("ai:chat:list"))])` 四端点（POST ""→create / GET "/list"→
    分页 / DELETE "/{session_id}" / GET "/{session_id}/messages"→回显——两行式返回、javadoc、
    `@PathVariable` 等价显式路径参数）；chat 两端点入参 `RagChatDto`→`ChatSendReq`，SSE event_gen
    结构不动（异常收口沿用；5012/5013 经 BusinessException 在 StreamingResponse 构造前抛出→
    全局 handler R JSON，非 SSE 帧）；router event_gen 消费 service generator 需显式 aclosing
    （finally 落答依赖 generator 被正确关闭——客户端断开路径）；
  - `main.py`：+`session_router` 注册；
  - `cloud-base-ai/tests/test_rag_router.py`：四新端点 + chat 换轨断言（路径/权限依赖/入参模型）。
- 验收：pytest 全绿；路由路径/方法/权限与契约 §1 表逐行一致（对照检查）。

### P7（Python）双路实证 + 契约验证记录回填

- 验证脚本 `cloud-base-ai/scripts/p5_*.py`（直连矩阵 stage_a / 网关全链 gateway），测试数据
  `e2e_session_` 思路（会话无名称列，以创建返回 id 记录跟踪）：
  1. 直连 :8010（手工头 `X-User-Perms/X-User-Id/X-User-Account`）：admin 创建→列表含新行（默认
     「新对话」）→chat 一问→回显含问答两条→pymysql 回查 title=问句前 16 字符；
  2. 归属矩阵：X-User-Id=2 对 admin 会话 chat/回显/删除 → 5013；X-User-Id 缺失 → list 空集 /
     create 1001；session_id=999999 → 5012；二次删除 → 5012；
  3. 1001 分支：session_id 数字串/0/缺失、question 空/4001 字/纯空白；旧形态 `{"messages":[…]}`
     → 1001；
  4. 上限与无 TTL：直灌 105 条消息 → 回显恒 100 条丢最旧；`TTL rag:messages:{id}` = -1（无过期）；
  5. 半截答（实现时验证项）：真 LLM 流中途断开客户端 → 回显含半截 assistant；
  6. 网关全链：admin 登录经 18080 全流程（创建→stream 对话逐帧增量到达→列表→回显→删除）；
  7. 回归：kb 域列表/创建冒烟零变化；sso 登录（Java 6379 Redis）不受影响。
- 改动：验证记录回填契约 `2026-10-19-ai-chat-session-api.md` §9；`docs/handoff/ai.md` 视实证结果
  补记（半截答掐流若有偏差按实际记档）。
- 验收：验证记录含请求/响应/DB/Redis 回查要素；测试会话清零（列表 total 回基线）；服务按开发期
  自管约定停服交还。
- 依赖：P1-P6 全部完成（联调段）。

## 前端章（cloud-web + cloud-e2e）

### F1（前端）类型与 API 封装

- 改动：
  - `cloud-web/src/types/api.ts`：+`ChatSessionVo{id:number; title:string; create_time:string}`、
    `SessionMessageVo{role:'user'|'assistant'; content:string}`、`SessionMessagesVo{messages:
    SessionMessageVo[]}`、`ChatSessionPageQuery{page:number; size:number}`、
    `ChatSendPayload{session_id:number; question:string}`；注释标契约 §2/§3/§5/§6；
  - `cloud-web/src/api/rag.ts`：+`createChatSession(): Promise<ChatSessionVo>`（POST
    `/ai/rag/chat/sessions`）、`pageChatSessions(query): Promise<AiPageResult<ChatSessionVo>>`（GET
    `/list`，params page/size）、`deleteChatSession(sessionId): Promise<null>`（DELETE
    `/${sessionId}`）、`listSessionMessages(sessionId): Promise<SessionMessagesVo>`（GET
    `/${sessionId}/messages`）；**`chatRag` 换轨**（入参 `ChatSendPayload`，body 直传——契约 §6）。
- 验收：`npm run typecheck`（vue-tsc）通过；无 Element Plus 全量引入（红线）；函数注释含错误码
  分支（5012/5013 拦截器统一 toast——delete/列表路径；stream 路径见 F2 已知边界）。
- 依赖：契约定稿（可 mock 并行；真机联调依赖 P1/P6）。

### F2（前端）chat 页数据源服务端化

- 改动：`cloud-web/src/views/rag/chat/index.vue`（UI 结构与样式不动，只换数据源）：
  - 会话态：`LocalSession{id:number; title:string}` + 消息缓存 `Map<number, LocalMsg[]>`（流式中途
    切换会话不影响本流目标——既有行为保持：发送时捕获目标会话引用）；
  - onMounted：`pageChatSessions({page:1, size:50})` 拉列表（首屏 50 条，滚动分页移交）→ 自动激活
    首条并 `listSessionMessages` 回显（无会话→空态文案现状）；
  - `newSession()`：`createChatSession()` → unshift `{id, title:'新对话'}` → 激活（消息区空）；
  - `removeSession(id)`：`ElMessageBox.confirm`（删除不可恢复）→ `deleteChatSession` → 移出列表与
    缓存 → 激活邻位或回空态；5012 静默收敛（他端已删场景，列表刷新自然对齐）；
  - 切换会话：缓存命中直接用，否则拉 `listSessionMessages` 映射为 LocalMsg（id 用索引合成）；
  - `send()`：无激活会话时先 `createChatSession()`（用户直接输入不点新建的路径，现状保持）→ 推
    user 气泡 → 首问乐观更新标题（`question.slice(0,16)` 同口径，契约 §6）→ `ssePost
    '/api/ai/rag/chat/stream', {session_id, question}`（**body 换轨**，sse.ts 零触碰）→ assistant
    气泡流式渲染/停止生成逻辑现状不动；onDone 后**不**主动拉回显（服务端已落档，刷新时回显）；
  - 已知边界注释：5012/5013 经 sse.ts 完成兜底=空气泡静默（契约 §6 记档），本页不特殊处理。
- 验收：`npm run lint` + typecheck 通过；Playwright MCP + 视觉模型真机冒烟（后端 P6 就绪后）：
  新建→发送→左栏标题更新→**刷新页面→列表与消息回显一致**→切换两会话→删除→空态；流式中途切换
  会话再切回，气泡内容完整。
- 依赖：F1；真机联调依赖 P1/P6。

### F3（前端）e2e 重构

- 改动：`cloud-e2e/run-ai-chat-e2e.mjs`（沿既有 route mock 分层，`cloud-e2e/package.json` 的
  `e2e:aichat` 脚本名不变）：
  - 新增四路由 mock：POST `**/api/ai/rag/chat/sessions`（返回固定 id/「新对话」）、GET
    `**/api/ai/rag/chat/sessions/list`（可变 mock 状态：标题随「已发问」置为 mock 问句前 16 字符）、
    DELETE `**/api/ai/rag/chat/sessions/:id`、GET `**/api/ai/rag/chat/sessions/:id/messages`
    （mock 问答各一条）；
  - stream mock：请求体断言 `postDataJSON()` 含 `session_id` 与 `question` 且无 `messages` 键
    （breaking 换轨锚）；AC1-AC5（菜单可达/流式渲染/停止生成/断流兜底/错误帧）场景不变；
  - **AC6 重写**（原「会话本地态零 API」废止）：新建对话→断言 POST sessions 发生；发送→断言
    stream body 换轨 + 左栏乐观标题；**刷新页→回显 mock messages 气泡断言**；删除→断言 DELETE
    发生且列表移除；AC7 场景注记「真实落档/回显由 P7 网关全链实证，mock 层只验前端行为」。
- 验收：`cd cloud-e2e && npm run e2e:aichat` 全绿（退出码陷阱按既有口径 `grep -c '[FAIL]'` 判定）；
  既有 kb 系列 e2e（`e2e:aikb`/`e2e:kbperm`）回归通过（本页改动不涉 kb 页）。
- 依赖：F2。

## 移交后续阶段的备忘

1. **活跃置顶**：列表现为创建倒序；做活跃重排需每轮消息 UPDATE 会话行或 last_active_at 列（DDL +
   排序口径演进），设计 §10-1。
2. **会话改名/搜索/置顶**：本轮无入口无端点；改名需 PUT 端点 + 前端标题编辑交互，届时契约 additive。
3. **消息分页与上限放开**：回显现全量 ≤100 条；放开需 Redis 分段读或消息迁 MySQL（存储选型复评）。
4. **空会话自动清理**：建而不问的会话只占元数据行；累积后再议策略（如创建 N 天无消息回收）。
5. **多主集群化**：单节点集群无副本，节点盘故障丢消息（元数据仍在）；键命令面全单键，扩 3 主
   零改造——设计 §10-6。
6. **检索轮联动**：会话挂知识库（聊天选库、检索历史注入）沿 kb 权限轮移交备忘第 1 条规划，
   届时会话表加 kb 关联列 + 检索链消费 `list_visible_kb_ids`。
7. **SSE 会话错误的前端呈现**：5012/5013 走 sse.ts 完成兜底=空气泡静默（契约 §6 记档）；若产品
   要求可感知，需 sse.ts 解析 R JSON——属帧协议相邻变更，须回架构修契约。
