# SSO refresh 令牌反向索引定位（消除 KEYS 全量扫）实施计划（2026-10-19，纯后端 B 级）

> 需求原文：「不能出现 sso:refresh:* 是 O(N) 全量扫 这样的写法来刷新 token」。
> 背景：`TokenService.refresh` 用 `KEYS sso:refresh:*` 扫全量用户 refresh 槽、逐键反序列化比对
> 不透明 UUID 定位归属——O(N) 扫描且 KEYS 阻塞 Redis 单线程。优化方向是 2026-10-05 登录轮
> plan「移交备忘」预留的既定预约：以 token 自身为键反向索引，单次 GET 定位。
> **契约无变更**：/sso/refresh 对外行为（入参/返回/错误码 2004/2005/2002）零变化；
> 无表结构、无共享配置变更。

## 设计（双键模型，保留全部既定语义）

- 保留主槽 `sso:refresh:{userId}` = `{"tokenId","token"}` 纯 JSON（CLAUDE.md「Redis 会话契约」
  已记档；承载「一用户单活跃 refresh 槽」语义——后登录覆盖前者）。
- 新增反向索引 `sso:refresh-token:{token}` = userId 纯字符串，TTL 与主槽相同
  （签发时双写；键名沿用 2026-10-05 plan 预留的 `sso:refresh-token:` 前缀）。
- refresh 定位链：GET 索引 → miss 得 2005；hit 得 userId → GET 主槽 → **token 相等校验**
  （防陈旧索引错位：主槽已被新登录覆盖时索引残留指向旧 token，比对不符即 2005，行为与
  「首端 refresh 得 2005」既定语义一致）→ 后续流程不变。
- 清理路径：refresh 成功换发后 DEL 旧 token 的索引键；logout 先 GET 主槽取 token 再双删；
  登录覆盖旧槽时旧索引**不主动清**（残留无害——比对护栏挡住，TTL 自灭，避免登录路径加读）。

## Task 1：TokenService 双键改造

**文件**：改 `cloud-base/cloud-sso/src/main/java/com/cloudai/sso/service/TokenService.java`

**要点**：
- 新常量 `REFRESH_INDEX_KEY_PREFIX = "sso:refresh-token:"`；
- `issueTokens`：现 SET 主槽之后追加 SET 索引键（值 = userId 字符串，TTL = 同
  `jwtProperties.getRefreshTokenTtl()`）；
- `refresh`：删除 `keys(REFRESH_KEY_PREFIX + "*")` 扫描与循环比对，改为上述定位链；
  `issueTokens` 成功返回前 DEL 旧 token 的索引键（旧 token 串在方法入参手里）；
- `logout`：先 GET `sso:refresh:{userId}` 取 token（miss 则跳过），再 DEL 索引键 + DEL 主槽
  （现在只 DEL 主槽，索引会残留到 TTL——须补上）；
- 注释口径：写「做什么+为什么」；KEYS 扫描为何被替换（O(N)+阻塞）写进 refresh 方法注释。

**易错约束**：
- 索引值是 userId 纯字符串，不是 JSON（与主槽纯 JSON 区分，注释写明）；
- refresh 里 DEL 旧索引必须在 `issueTokens` 成功之后（失败路径旧 token 仍应可重试）；
- 不得改动 2004/2005/2002 的触发条件与文案。

**验收**：`refresh` 方法内不再出现 `keys(` 调用；全模块单测绿（Task 2 后）。

## Task 2：TokenServiceTest 适配 + 新用例

**文件**：改 `cloud-base/cloud-sso/src/test/java/com/cloudai/sso/service/TokenServiceTest.java`

**要点**：现有 5 处 `when(stringRedisTemplate.keys("sso:refresh:*"))` 的 stub 全部改为索引
定位链 stub（GET 索引 → GET 主槽）；新增用例：
1. 索引 miss → 2005（token 无效）；
2. 索引 hit 但主槽 token 不匹配（陈旧索引/被覆盖）→ 2005；
3. `issueTokens` 双写断言：主槽与索引键都 SET 且 TTL 相同；
4. logout 双删断言（主槽 + 索引键；主槽 miss 时索引不误删）；
5. refresh 成功后旧 token 的索引键被 DEL、新索引键已写。

**验收**：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-sso -am`
全绿（含既有 refresh 全用例组：权限快照回查/停用拒绝/只删绑定会话等语义不破）。

## Task 3：CLAUDE.md Redis 会话契约行同步

**文件**：改 `CLAUDE.md`「Redis 会话契约」条目

**要点**：`sso:refresh:{userId}` 行补反向索引键说明（键名/值形态/TTL 同主槽/refresh 单次
GET 定位替代 KEYS 扫描的一句话背景）。

**验收**：描述与实现一致，一句话增量，不扩段。

## Task 4：运行时 curl 实证（backend-agent 执行）

**前置**：Redis/MySQL/Nacos 容器在运行；起 system（9202）→ sso（9201）→ 网关（18080），
java -jar 绝对路径后台起。

**步骤**（全部走网关 `/sso/**`）：
1. admin 登录 → 取 accessToken/refreshToken；
2. `/sso/auth/refresh` 带新 token → 200，得到新双令牌；
3. **旧 token 再 refresh → code 2005**（换发后旧 token 即失效）；
4. Redis 抽查（redis-cli 或模板查询）：`sso:refresh:{userId}` 与 `sso:refresh-token:{新token}`
   双键存在、TTL 一致；
5. `/sso/auth/logout` 后：双键消失。

**兼容性影响（一次性，记档不处理）**：部署新版后，存量 Redis 主槽无索引相伴，旧 refresh
token 全部走索引 miss → 2005 需重新登录。仅影响当前在线会话，开发环境可接受；不做回退扫描
（回退即重新引入 KEYS）。

**验收**：5 步全过，输出进实施报告；验收完 kill 本轮所起服务进程。

## 移交备忘

- `onlineList()` 的 `KEYS sso:online:*` 扫描**保留不动**：管理端枚举语义上就需要全量列出，
  非本题范围；将来在线量大可换 SCAN 游标（另轮）。
- 双键模型语义（一用户单活跃 refresh 槽、首端 2005）维持 2026-10-05 登录轮拍板不变；
  「与 accessToken 多会话并存模型不一致，产品语义确认后统一」仍是该轮留下的开放项。
