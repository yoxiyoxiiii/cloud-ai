# AI 对话会话管理设计（会话 CRUD + chat 挂接，2026-10-19）

> 全栈轮（cloud-base-ai 后端 + cloud-web 前端 chat 页数据源服务端化 + e2e）。
> 需求：AI 对话创建会话（session）、新建会话、查询用户 AI 会话列表；拍板结论（2026-10-19）：
> **混合存储**（会话元数据 MySQL + 消息 Redis Cluster）、**chat 挂接方向一①（breaking）**——
> 对话入参改「session_id + 本次提问」、服务端拼历史单源、SSE 帧协议零变更、本轮同出消息回显端点、
> **起新 Redis Cluster 容器**（单节点集群、另用端口、既有 6379 单机容器不动）、
> **撤草稿重设计**（工作区 redis_store 草稿三文件回退删除）、删除会话纳入、
> 全端点复用 `ai:chat:list` 零新种子、「创建会话」与「新建会话」同义。
>
> 契约：`docs/superpowers/contracts/2026-10-19-ai-chat-session-api.md`（前后端唯一对齐物）；
> 实施计划：`docs/superpowers/plans/2026-10-19-ai-chat-session.md`。

## 1. 目标与非目标

**目标（最小闭环）：**

1. 会话成为服务端实体：创建（POST）、查当前用户会话列表（分页 GET）、删除（DELETE）三端点；
2. chat 两端点挂接会话：入参换轨为 `session_id + question`，历史由服务端拼装与落档（单源）；
3. 消息回显端点：切会话/刷新后从服务端拉回历史（解决前端本地态刷新即丢的痛点）；
4. 前端 chat 页左栏数据源服务端化（新建/列表/切换/删除/回显对接，UI 结构不动）；
5. 本机 Redis Cluster 容器落地（RedisCluster 客户端的真实可用环境）。

**非目标（移交）：** 会话改名/搜索/置顶；会话跨用户共享（纯私有资源）；检索轮选库联动
（kb 权限轮移交备忘既有规划）；消息内容审计；流式逐 token 落档（本轮按「收口一次落」）。

## 2. 架构总览

```
浏览器 cloud-web chat 页
  │ ①新建/列表/删除/回显（axios）          ②发送（ssePost，body={session_id, question}）
  ▼                                        ▼
网关 :18080  /ai/rag/chat/sessions/**  |  /ai/rag/chat(/stream)   （AuthGlobalFilter 剥离伪造头，注入五身份头）
  ▼                                        ▼
cloud-base-ai :8010  router（require_perms("ai:chat:list") 路由级依赖，全端点共用）
  ▼
service 编排层 ──────────────┬──────────────────────────────┬─────────────────┐
  │ 会话元数据（真相源）        │ 消息（唯一存储）                │ LLM             │
  ▼                           ▼                              ▼
MySQL cloud_ai.ai_chat_session  Redis Cluster :7000           DeepSeek（ChatOpenAI 既有工厂）
  （id/user_id/title/审计/软删）  rag:messages:{session_id}      （历史=服务端拼装，尾 20 条）
                                  LIST 头插，上限 100，无 TTL
```

**职责一句话**：MySQL 回答「这个用户有哪些会话、会话归谁、标题是什么」；Redis 回答「这个会话里说过什么」；
LLM 消费「Redis 尾 N 条历史 + 本次提问」。会话存在性与归属的**每一次判定都以 MySQL 为准**，
Redis 键只在归属校验通过后才被触碰。

**关键事实（设计动机）**：SSE 帧协议由前端 `cloud-web/src/utils/sse.ts` 定死且契约冻结——帧内无法
回传 session_id，因此**会话创建必须先于首条消息**（显式创建端点），不能做「首问时懒创建」。

## 3. 存储设计

### 3.1 MySQL：ai_chat_session（D1 表设计，D2 索引）

```sql
CREATE TABLE ai_chat_session
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '会话ID（主键）',
    user_id     BIGINT      NOT NULL COMMENT '归属用户ID（sys_user.id，网关 X-User-Id 注入；主数据在 cloud_system 库，弱引用不建外键）',
    title       VARCHAR(64) NOT NULL DEFAULT '新对话' COMMENT '会话标题（默认"新对话"；首问由服务端取前16字符更新）',
    create_by   VARCHAR(30) NULL COMMENT '创建人账号',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（DB 时钟生成，应用层不传时间）',
    update_by   VARCHAR(30) NULL COMMENT '更新人账号',
    update_time DATETIME NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    KEY idx_user (user_id, deleted)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='AI 对话会话（元数据真相源；消息在 Redis Cluster，键 rag:messages:{id}）';
```

- **D1（表设计）**：薄元数据表——只有归属、标题与审计/软删列，无消息计数、无最后活跃时间冗余列
  （消息计数在 Redis llen 可得且当前无消费方；冗余列=双存储对账义务，不值）。`title` 由 DB DEFAULT
  给初值，实体 `server_default` 同步（refresh 回填，沿 save_kb 先例）。无唯一键：会话是私有资源，
  重名无害、无查重需求（与 kb 的 uk_name 形成对照——kb 名称是全局管理命名空间，会话标题只是展示文案）。
- **D2（索引）**：`idx_user(user_id, deleted)` 命中全表唯一高频查询「列表：WHERE user_id=? AND deleted=0
  ORDER BY id DESC LIMIT」。InnoDB 二级索引隐式追加主键 id，等值前缀 (user_id, deleted) 之后可沿 id
  逆序扫描，**免 filesort**——无需显式 (user_id, deleted, id) 三列索引。按 session_id 的点查走主键。
  软删 UPDATE 会移动索引项（deleted 进索引列），写频极低（仅删除动作），无代价。

DDL 落库：增量脚本 `cloud-base/scripts/sql/2026-10-19-ai-chat-session.sql` + 基线
`cloud-base/scripts/sql/cloud_ai.sql` 追加（pymysql utf8mb4 落库，沿中文入库规则）。

### 3.2 Redis：消息键（D3 键结构，D4 上限与无 TTL）

- **D3（键结构）**：`rag:messages:{session_id}`（十进制自增 id），LIST **头插**（索引 0 = 最新），
  元素为 JSON `{"role":"user|assistant","content":"..."}`（即既有 `RagChatMessage` 模型序列化）。
  读反转成时间升序。命令面 LPUSH/LTRIM/LLEN/LRANGE/DEL **全单键**——单节点集群零 CROSSSLOT 面，
  未来扩多主节点也天然兼容（无需 hash tag）。
- **D4（上限与 TTL）**：每会话上限 **100 条**（头插后即时 LTRIM 0..99；> LLM history_limit=20，
  留回显深度）。**不设 TTL**——决策理由：回显是产品功能不是缓存，TTL 会让历史在闲置 N 天后凭空
  消失，恰好违背本轮「刷新不丢历史」的目标；单会话内存由 100 条上限约束，空会话（建了没问）根本
  不产生 Redis 键；生命周期终点统一收敛到删除端点（双清，见 3.3）。容器侧以 AOF + volume 持久化
  防重建丢数据（见 §5）。替代方案「滑动 TTL（如 7 天）」被否：列表仍在而消息蒸发，产品语义残缺。

### 3.3 混合一致性（D5 删会话双清）

MySQL 是会话存在性与归属的**唯一真相源**；Redis 键无人独立引用（一切入口先过 MySQL 校验）。
由此推论：

- **孤儿键不可能自然产生**（只有删除端点会终结会话），唯一风险窗口在删除动作本身的两步之间；
- **D5（双清顺序）**：校验归属 → **先 Redis DEL，后 MySQL 软删事务**。正向推演两序的失败面：
  - Redis 先删、MySQL 提交失败 → 会话还在、消息空（重删即愈：DEL 幂等，键不存在为 no-op）；
  - MySQL 先删、Redis DEL 失败 → **永不过期的孤儿键**（D4 无 TTL 放大了此风险），且用户重试删除
    会因 5012（活记录视角）被拒，孤儿**无法自愈**。
  两害相权取前者。极端窗口（Redis 删成、MySQL 提交失败后用户不再重试）留「空会话」残留，接受，
  不引入对账任务（记档 §10）。

## 4. chat 链路挂接

### 4.1 入参换轨（D6）

两端点（`/rag/chat`、`/rag/chat/stream`）入参从 `RagChatDto{messages[]}` 换为
`ChatSendReq{session_id: int(≥1, strict), question: str(1..4000, strip 后非空)}`。
旧客户端形态 `{"messages":[...]}` 在新模型下缺必填字段 → 既有 1001 通道拒绝（pydantic 默认忽略
多余字段，不会误收）。出参零变化（`R<RagChatAnswerVo>` / SSE 帧协议）。
`RagChatMessage` 保留——降级为消息载荷模型（Redis 存取复用）。

### 4.2 写路径时序（D7 非流式 / 流式，D8 半截答）

**非流式 `/rag/chat`**：

```
① 校验事务（@transactional 读）：会话存在(deleted=0)？→ 5012；归属=Identity.user_id？→ 5013
② 问落 Redis（LPUSH+LTRIM）
③ 首问改题（仅当 title='新对话'，@transactional 小事务 UPDATE）
④ 读历史：list_messages 尾 history_limit 条（含刚落的问）→ to_lc_messages
⑤ LLM ainvoke（无事务持有，沿既有 chat_answer 无 @transactional 先例）
⑥ 答落 Redis
⑦ 返回 RagChatAnswerVo
```

顺序理由：**校验严格先于任何写**（先写 Redis 再校验=可向他人会话键注入消息的越权面）；
改题放问之后（问确实发生了才改），改题失败不回滚问（最坏=标题停留「新对话」，下一问补齐）。

**流式 `/rag/chat/stream`**：①②③④同上，然后 `async for` 逐 token yield（router 帧化），
**收口路径统一落答**：服务层 generator 以 try/finally 收口，finally 把累计文本一次 LPUSH——
正常结束、LLM 异常、客户端断开（用户点「停止生成」→ AbortController → Starlette 关闭响应流）
三条路径都经过 finally。

- **D8（半截答保留）**：流中断/失败时，已生成的半截 assistant 消息**照落**——回显「所见即所得」：
  刷新后用户看到的就是中断前屏幕上看到的。LLM 零 token 即失败 → 无答行（问悬置）。悬置的问保留
  （忠实记录「问过没答成」），重试重发会产生重复问——接受（替代方案「失败补偿 LPOP 删问」被否：
  补偿本身可失败且抹掉真实发生的提问记录）。此语义同时简化实现——失败路径无需任何补偿分支。

**落答失败的转译**：finally 中 Redis 写失败 → log.error 后原样上抛（不吞不转 5001——5001 语义是
LLM 调用失败）。非流式路径 ⑥ 失败同理上抛走全局 500；此时答已生成但未落档，前端拿到了错误、
服务端少一条答——与「问悬置」同族的已知偏差，记档 §10。

**同步 Redis 调用口径**：LPUSH/LTRIM 级内存操作为毫秒级，service 直接同步调用，不套
run_in_threadpool（与 Milvus 重调用包 threadpool 的先例区分，量大时再议）。

## 5. Redis Cluster 容器方案（D9）

**现状矛盾**：`redis_client.py` 是 RedisCluster 集群客户端（用户拍板保留），本机 6379 是单机容器
（集群发现实证失败）。**方案：另起新容器跑单节点集群，6379 单机容器不动**（Java 侧 sso 会话在用，
属「基础设施容器不碰」红线）。

- **D9（单节点集群而非 3 主节点）**：拍板口径「单节点集群即可满足 RedisCluster 客户端」。注意
  `redis-cli --cluster create` **强制 ≥3 主节点**（官方工具行为，2026-10-19 检索实证），单节点集群
  须手工 `CLUSTER ADDSLOTS` 全量 16384 槽；建簇命令在容器内 sh 执行（`$(seq 0 16383)` 约 100KB
  参数，Linux execve 上限内可行；Git Bash 宿主侧拼同样长参数有 Windows 32KB 限制风险）。
- **announce 三件套是关键**：容器内节点默认通告容器内网 IP（172.x），宿主上的 redis-py 拿到拓扑后
  不可达——`--cluster-announce-ip 127.0.0.1` + 显式 port/bus-port 让拓扑通告宿主可达地址。
  单节点无跨容器 gossip，通告 127.0.0.1 无副作用。
- **持久化**：volume `/data` + `--appendonly yes`；`cluster-config-file nodes.conf` 相对路径落
  工作目录 /data（官方镜像）→ 节点 id 与槽分配随卷存活，容器重启免重建簇（卷被清才需重跑 ADDSLOTS）。
- **端口**：7000（节点）/ 17000（集群总线，必须一并映射）；与在用端口清单（18080/9201-9203/8010/
  18081/19202-3/8848/9848-9/6379/3306/19530/9100/5173/11434）无冲突。镜像用**本机已有 `redis:7`**
  （docker images 实证，免拉取）。

```bash
docker run -d --name redis-cluster --restart unless-stopped \
  -p 127.0.0.1:7000:7000 -p 127.0.0.1:17000:17000 \
  -v redis-cluster-data:/data \
  redis:7 \
  redis-server --port 7000 --cluster-enabled yes \
    --cluster-config-file nodes.conf --cluster-node-timeout 5000 \
    --cluster-announce-ip 127.0.0.1 --cluster-announce-port 7000 --cluster-announce-bus-port 17000 \
    --appendonly yes

docker exec redis-cluster sh -c 'redis-cli -p 7000 cluster addslots $(seq 0 16383)'
docker exec redis-cluster redis-cli -p 7000 cluster info   # 验收锚点：cluster_state:ok
```

配套改动：`RedisSettings.port` 默认 6379 → **7000**（hosts/password 不变）；`tests/test_settings.py`
默认值断言同步；`.env.example` 补注释段；CLAUDE.md 端口段补记。`redis_client.py` 零改动（活基础设施）。

## 6. 会话标题与列表口径（D10/D11）

- **D10（标题生成）**：**服务端生成**——创建时默认「新对话」；首问落档时若仍为默认值，取问句
  （strip 后）前 16 字符更新（沿用前端既有口径 `slice(0, 16)`，生成责任收敛服务端保证多端一致）。
  前端在首问发送时做**同口径乐观更新**（左栏即时变标题，免一次列表刷新；刷新后以服务端为准）。
  截断口径差异记档：Python 按码点、JS 按 UTF-16 代码单元，含 emoji 的问句理论上有 1 字符差，
  接受（展示文案，无对账语义）。
- **D11（列表排序与分页）**：id 倒序（创建倒序，新建居首）——与前端本地态既有行为一致（新建
  unshift 到顶、聊旧会话位置不动），也与 kb 列表先例一致；**不做活跃置顶**（需每轮消息 UPDATE
  会话行 + 列表乱序跳动，移交）。分 page/size（AI 域参数名，钳制 1..200，沿既有 `_clamp_page`）。

## 7. 分层落位（文件级）

```
cloud-base-ai/src/cloud_base_ai/
├── config.py                    # RedisSettings.port 默认 7000（唯一改动）
├── redis_client.py              # 零改动（活基础设施）
├── rag/
│   ├── message_store.py         # 新建：消息存储（append/list/delete + 上限常量；Redis Cluster 实现）
│   ├── entities.py              # +AiChatSession（Base+AuditMixin+SoftDeleteMixin，列必带 comment）
│   ├── dao.py                   # +save/find/page_list/update/delete 五动词（session 显式传参、flush 不提交）
│   ├── schemas.py               # +ChatSendReq/ChatSessionVo/SessionMessagesVo；RagChatDto 删除
│   ├── service.py               # +create/page_list/delete/list_messages；chat_answer/stream_chat_answer 重写挂接
│   └── router.py                # +session_router（prefix /rag/chat/sessions，require_perms("ai:chat:list")）
│                                #   chat 两端点入参换轨
└── main.py                      # +session_router 注册
cloud-base-ai/tests/             # test_rag_message_store.py 新建（FakeRedis 替身）；dao/service/router/
                                 #   schemas/entities/settings 各既有文件扩展或更新
```

草稿回退（拍板第 4 条）：删除工作区 `rag/redis_store.py`、`tests/test_rag_redis_store.py`、
`scripts/smoke/smoke_redis_list.py`（redis_store.py 处于已暂存状态，先 unstage 再删）；
`service.py` 的坏接线不单独回退——本轮重写自然覆盖。`scripts/smoke/smoke_redis.py` 与
`redis_client.py`、`config.py` redis 段、`test_settings.py` 是 f76b35c 入库的**活基础设施**，保留。

## 8. 错误处理与错误码

| code | 语义 | 触发 | 文案 |
|---|---|---|---|
| 403 | 无操作权限 | X-User-Perms 不含 `ai:chat:list` | 无操作权限（既有） |
| 1001 | 参数校验失败 | 字段约束 / question 纯空白 / **创建时 X-User-Id 缺失或非整数** | 既有 handler 拼接 |
| 5001/5002 | LLM 调用失败/未配置 | 既有语义零变化 | 既有 |
| **5012** | 会话不存在 | session_id 无活记录（含软删、二次删除）——活记录视角，沿 5004 先例 | 会话不存在 |
| **5013** | 无权访问该会话 | 会话存在但 user_id ≠ 当前身份（含身份头缺失）——沿 5011「存在但无权」先例 | 无权访问该会话 |
| 500 | 系统异常 | Redis/MySQL 基础设施异常等 | 既有兜底 |

AI 段占用至 5013，下一可用 **5014**。

**SSE 层次（沿母契约三分层，扩入 5012/5013）**：403/1001/5012/5013 均在 StreamingResponse 构造前
抛出 → HTTP 200 + R JSON（**非 SSE 帧**）；5001/5002 在流内 → msg 帧 + [DONE]。前端 sse.ts 对
R JSON 的完成兜底既有行为覆盖 5012/5013（空气泡静默）——已知衍生边界，不触碰 sse.ts。

**身份缺失语义（两类端点刻意不同）**：读端点（list）身份缺失 = 空集（total=0，沿 kb 权限轮
「身份头缺失=空集，不算错误」先例）；写/归属校验端点（create/chat/messages/delete）身份缺失 =
1001（create，无法落归属）/5013（其余，归属永不匹配）。网关全链下 X-User-Id 恒在（JWT subject），
此差异只影响本地直连调试——契约记「本地直连须手工带 X-User-Id」。

## 9. 测试策略

- **单测（不连真实外部系统）**：message_store 用 FakeRedis 替身（沿被撤草稿的替身思路重写：
  读写往返/升序/上限修剪/空会话/删除）；dao 用 SQLite 内存替身（`tests/db_testing.py` 既有，
  五动词各≥1 用例 + 软删不可见 + 审计回填）；service 用 `make_service_session_factory` +
  monkeypatch message_store（覆盖 5012/5013/标题首问/双清顺序 Redis 先/问先落答收口落/半截答保留/
  LLM 失败问悬置）；router/schemas 按既有文件扩展；test_settings 端口断言更新。
- **真连接冒烟**：`scripts/smoke/smoke_redis.py` 对新容器真跑（cluster_info known_nodes=1）；
  message_store 的 LIST 命令面在 P7 双路实证中对真集群覆盖。
- **双路实证（P7）**：直连矩阵（归属 5013/5012/1001/标题 DB 回查/100 条上限真灌/半截答掐流验证）+
  网关全链（admin 全流程 + SSE 逐帧 + 旧入参形态 1001）+ 回归（kb 域端点、sso 登录不受 7000 容器影响）。
- **e2e（F3）**：`run-ai-chat-e2e.mjs` 重构——session 四端点 route mock + stream 负载断言
  {session_id, question}；AC6 从「本地态零 API」改为「服务端行为断言」。
- 全量 pytest（含 `test_architecture_guard.py`）全绿；`npm run e2e:aichat` 全绿（退出码陷阱按
  既有口径 grep 判定）。

## 10. 移交与已知取舍（记档）

1. **活跃置顶**：列表按创建倒序，不随消息活跃重排——若要做，每轮消息时 UPDATE 会话行（或定时
   回填 last_active_at 列），届时 DDL + 排序口径一并演进。
2. **悬置问/失答偏差**：LLM 失败后问保留无答、重试产生重复问；落答 Redis 失败时答已生成但未存档
   ——均为「忠实历史」语义的接受面，不做补偿。
3. **删会话极端窗口残留**：Redis 删成、MySQL 提交失败且用户不再重试 → 空会话残留（软删未落），
   重删即愈；不引入对账任务。
4. **空会话累积**：建了不问不删的会话只占 MySQL 一行（无 Redis 键）；量大后再议自动清理策略。
5. **半截答落档的掐流验证**：属 P7 实证项（真 LLM 流掐断），单测以 FakeRedis + 模拟异常路径覆盖。
6. **单节点集群无副本**：节点盘故障丢消息（元数据仍在 MySQL，可重建空会话）——生产化时升
   3 主 3 从，键单键命令面无需改造。
7. **检索轮联动**：会话挂知识库（聊天选库）沿 kb 权限轮移交备忘第 1 条规划，届时会话表加关联列。
