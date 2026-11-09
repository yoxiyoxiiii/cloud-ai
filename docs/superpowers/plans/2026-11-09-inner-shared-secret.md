# 实施计划：/inner 面共享密钥准入控制

- 日期：2026-11-09
- 关联：设计 `docs/superpowers/specs/2026-11-09-inner-shared-secret-design.md`；契约 `docs/superpowers/contracts/2026-11-09-inner-access-secret-api.md`（两文档已定稿，本计划是唯一实施依据）
- 轨道判定：**纯后端**（前端零感知——无新端点、无载荷变化，故仅后端章节）；架构分级 A
- 阅读对象：backend-agent。每任务自带文件清单与验收标准，可独立执行。
- 本仓 agent 不做任何 git 提交——工作树改动留给主控处置，本计划不含提交/推送指令。

## 0. 任务总览与执行顺序

```
T1 组件落地 ──→ T2 三服务接线 ──→ T3 Nacos 发键 ──→ T5 重启+验证
                                              T4 文档附记（与 T2 起并行，验证前完成）
```

前置状态：本计划开工时全部服务正在运行旧 jar。T3 发键对旧 jar 零影响（不识别未知键），可提前执行；重启（T5）必须等 T1/T2 构建产物就绪且 T4 完成。

## 1. 后端任务

### T1 组件落地：cloud-common-inner-starter

新建模块（设计 §5 已定全部细节，照做即可）：

| 文件 | 内容 |
|---|---|
| `cloud-base/cloud-common/cloud-common-inner-starter/pom.xml` | parent=cloud-common；依赖 core-starter(compile)、jakarta.servlet-api(provided)、io.github.openfeign:feign-core(provided)、spring-boot-starter-test(test)——版本全归根 pom 免版本号 |
| `.../src/main/java/com/cloudai/common/inner/auth/InnerAccessFilter.java` | 纯 Servlet Filter；常量 `HEADER_INNER_SECRET = "X-Inner-Secret"`；常量时间比对 `MessageDigest.isEqual`；三态（无头/错值/未配置）统一拒绝 = HTTP 401 + R JSON `{"code":401,"msg":"inner 认证失败","data":null}`（序列化失败兜底字面量，写法照抄 cloud-common-mcp-starter 的 McpAuthFilter.deny）；拒绝时 log.warn(uri+remoteAddr+内部原因)；不触碰 SecurityContext |
| `.../src/main/java/com/cloudai/common/inner/client/InnerSecretInterceptor.java` | implements feign.RequestInterceptor；密钥已配置→applyTemplate 加头；未配置→跳过（不加空头、不抛） |
| `.../src/main/java/com/cloudai/common/inner/config/InnerProperties.java` | @ConfigurationProperties(prefix="cloud.inner")，唯一字段 `sharedSecret`（默认空串） |
| `.../src/main/java/com/cloudai/common/inner/config/CloudInnerAutoConfiguration.java` | @AutoConfiguration + @EnableConfigurationProperties；构造器哨兵 WARN（密钥空→warn fail-closed 语义）；嵌套配置一：@ConditionalOnWebApplication(SERVLET) 注册 FilterRegistrationBean（urlPatterns="/inner/*"，order=HIGHEST_PRECEDENCE+10，@ConditionalOnMissingBean("innerAccessFilterRegistration")）；嵌套配置二：@ConditionalOnClass(feign.RequestInterceptor.class) + @ConditionalOnMissingBean 注册 InnerSecretInterceptor bean |
| `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | 单行：com.cloudai.common.inner.config.CloudInnerAutoConfiguration |
| `.../src/test/java/com/cloudai/common/inner/auth/InnerAccessFilterTest.java` | 四分支：无头 401 / 错值 401 / 未配置 401 / 正确值放行（断言 FilterChain 被调、拒绝响应体 JSON 精确、状态码 401） |
| `.../src/test/java/com/cloudai/common/inner/client/InnerSecretInterceptorTest.java` | 两态：已配置→模板含 X-Inner-Secret 头；未配置→无头不抛 |
| `.../src/test/java/com/cloudai/common/inner/config/CloudInnerAutoConfigurationTest.java` | ApplicationContextRunner：密钥已配→过滤器 bean（URL 模式 /inner/*、order 正确）+拦截器 bean 存在；密钥空→bean 仍在（fail-closed 不拆装配） |
| `cloud-base/cloud-common/pom.xml` | `<modules>` 追加 `<module>cloud-common-inner-starter</module>` |

**验收标准**：
- `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-inner-starter -am` 绿（全部单测过）
- 代码符合 CLAUDE.md 编码规范通用约束（注释「做什么+为什么」、无决策代号裸引用——引用设计文档写明路径）

### T2 三服务接线（代码零改动，仅依赖+守护）

| 文件 | 变更 |
|---|---|
| `cloud-base/cloud-sso/pom.xml` | `<dependencies>` 加 cloud-common-inner-starter（消费方：注入） |
| `cloud-base/cloud-system/pom.xml` | 同上（一体两面：提供 /inner/user、/inner/dict、/inner/data-perm + 消费 bpmn） |
| `cloud-base/cloud-bpmn/pom.xml` | 同上（提供 /inner/approval + 消费 system） |
| `cloud-base/cloud-system/src/test/java/com/cloudai/system/ArchitectureGuardTest.java` | 增补一条守护：本服务 pom.xml 必须包含 `cloud-common-inner-starter` 依赖（读 pom 断言，失败信息写明「提供方漏依赖 = /inner 静默裸奔」） |
| `cloud-base/cloud-bpmn/src/test/java/com/cloudai/bpmn/ArchitectureGuardTest.java` | 同上（sso 不加守护：消费方漏依赖表现为登录立即坏、e2e 必红，无需构建期拦截——设计 §10 已记档取舍） |

**验收标准**：
- `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-sso,cloud-system,cloud-bpmn -am` 绿（三服务 + 上游含新 starter 全量构建）
- 既有全部守护测试不回归（只新增断言，不动既有规则）

### T3 Nacos 配置发布（运维步骤，6 次发布）

无仓库文件产出（真值仅 Nacos——纪律见契约 §3）。步骤：

1. 生成一把随机密钥（如 `openssl rand -base64 32`），存于本次会话临时变量，**不写入任何仓库文件/报告**
2. Nacos 控制台（http://127.0.0.1:8848）逐服务编辑：`cloud-sso.yaml`、`cloud-system.yaml`、`cloud-bpmn.yaml` 三份各加 `cloud.inner.shared-secret: <真值>`——**整文件全量干净内容重发**（非追加）
3. **双租户**：每份配置在 `''` 与 `public` 两租户各发一次完全相同内容（3×2=6 次）——漏发/内容不一致会触发 Nacos 3.x 同步合并出重复嵌套键、服务起不来（cloud.mcp.client-secret 实录，CLAUDE.md「Nacos 双写坑」）
4. 发布后核对：两租户同键同值、YAML 无重复键；旧 jar 运行中服务无任何异常（不识别未知键）

**验收标准**：6 次发布完成且双租户内容一致；运行中旧服务无重启无异常。

### T4 契约附记与 CLAUDE.md（与 T2 起并行）

| 文件 | 变更 |
|---|---|
| `docs/superpowers/contracts/2026-10-07-inner-api.md` | §5.1 第 1 条句末粘贴契约 §5(a) 附记原文（一字不差） |
| `docs/superpowers/contracts/2026-10-08-approval-platform-api.md` | §4 标题下追加契约 §5(b) 附记原文 |
| `CLAUDE.md` | 三处：①「模块拓扑」cloud-common 子模块清单加一行 cloud-common-inner-starter（描述：inner 面共享密钥准入——入站过滤器+Feign 全局拦截器，配置即生效 fail-closed）；②「关键约定·服务间 Feign 规范」段末补一句「服务间调用由 cloud-common-inner-starter 全局拦截器自动携带 X-Inner-Secret 共享密钥头（2026-11-09，契约 2026-11-09-inner-access-secret-api.md）」；③「关键约定」新增条目「**服务间 inner 准入（2026-11-09）**：/inner/** 服务侧强制共享密钥——引 cloud-common-inner-starter 即生效无开关，cloud.inner.shared-secret 已配置则校验+注入、未配置则恒 401 fail-closed 不阻断启动；拒绝=HTTP 401+R JSON 统一报文常量时间比对；密钥在 Nacos per-service 三服务配置（双租户同内容）；登录链/MQ/xxl-job 无用户态线程天然覆盖」 |

**验收标准**：两份契约附记落位且指向新契约路径正确；CLAUDE.md 三处更新与契约语义一致。

### T5 重启与验证（顺序敏感）

**重启（按设计 §7 推荐序——消费方先行，登录链零降级）**：
1. 停服：`netstat -ano | grep LISTENING | grep :<port>` 找 PID 再 `taskkill //F //PID <pid>`（Git Bash `$!` 是 MSYS 包装 PID，不可用）——逐个重启顺序 **sso(9201) → bpmn(9203) → system(9202) → 网关(18080，最后)**
2. 启动：`java -jar cloud-base/<svc>/target/<svc>-1.0.0-SNAPSHOT.jar`（新 jar 来自 T2 构建）
3. 每个服务启动日志核对：无「cloud.inner.shared-secret 未配置」WARN（已配置态）；网关 ping `curl http://localhost:18080/system/demo/ping` 通

**冒烟脚本**（新建 `cloud-base/scripts/smoke/smoke_inner_secret.sh`，密钥经环境变量 `INNER_SECRET` 传入，脚本内不落真值）：

| # | 断言 |
|---|---|
| 1 | 直连 9202 `GET /inner/user/all` 无密钥头 → HTTP 401 + body code 401 |
| 2 | 直连 9202 同端点带 `X-Inner-Secret: $INNER_SECRET` → HTTP 200 + code 200 |
| 3 | 直连 9203 `POST /inner/approval/status-list`（合法入参 body）无密钥头 → 401 |
| 4 | 网关 18080 `GET /system/inner/user/all` → 403（外层 inner-block 未动实证） |
| 5 | 经网关登录（ASCII 账号密码——Git Bash curl 发中文 JSON 是 GBK 会 500）→ 200 拿 token（覆盖 sso→system 调用链） |
| 6 | 直连 9202 `GET /inner/dict/items/{dictKey}` 带密钥 → 200（字典回源数据路径） |

**翻译程序式 client 验证（设计 §8 强制项）**：
1. 清翻译缓存：经 6379 Redis 容器 redis-cli 清 `trans:*` 键（容器名以 `docker ps` 为准）
2. 经网关带 token 请求含翻译字段的 bpmn 列表端点（具体端点实现时核对，候选审批列表分页接口）→ 断言昵称字段非空
3. **判定**：昵称正常 = 全局拦截器被 FeignClientBuilder 继承，验证结束；昵称空且冒烟 #2 直连带密钥 200 = 未继承 → 执行 fallback：`cloud-common/cloud-common-translate-remote-starter/.../CommonTranslateRemoteAutoConfiguration.java` 的 builder `customize()` 内补 `builder.requestInterceptor(...)` 注入同一密钥头（该文件为 T5 唯一可能的额外改动；豁免在类注释记档沿其特例先例），重新构建 bpmn 并重启复验
4. 结论（继承/fallback）回报主控，落档移交备忘

**链路回归**：
1. cloud-e2e 全量：`cd cloud-e2e && npm run e2e`（有头）——**只认 `grep -c '[FAIL]'` 输出为 0**，exit 0 是假绿（harness 不设失败退出码）；覆盖登录链、请假发起 MQ 链（system→bpmn create + bpmn→system listAll）、审批列表（数据权限求值+翻译）
2. xxl-job 对账：发布后等下一分钟轮次，admin 控制台（http://127.0.0.1:18081，admin/123456）任务 id 7 调度日志无异常、system 服务日志无 Feign 401（对账线程调用链实证）
3. 验证全部通过后按主控惯例 kill 全部服务进程交还（联调+冒烟通过即交还，不滞留）

**验收标准**：冒烟 6 断言全过；翻译程序式验证有明确结论（继承成立或 fallback 已落并复验）；e2e `[FAIL]` 计数为 0；对账轮次无错。

## 2. 移交备忘（下一轮规划前必读）

1. **每服务独立密钥**（拍板演进项）：共享一把的失守半径是全域；演进方向 = per-target 密钥映射 + 拦截器按目标服务名取键 + 过滤器多键并行过渡（设计 §12）
2. **mTLS**：内网明文 HTTP 下密钥可被窃听/重放，彻底解法在传输层
3. **REST 面直连伪造维持部署约束**：本轮只闭合 /inner 面；直连端口+伪造 X-User-* 调正常端点仍可行，若闭合需单独立轮
4. **翻译程序式 client 继承性结论**：已验证（2026-11-09 T5）——**继承成立**：FeignClientBuilder 命名子上下文收集父上下文全局 RequestInterceptor，translate-remote-starter 零改动，fallback 未启用；后续该 starter 演进无须为密钥头单独维护豁免点
5. **Nacos auth 未开**：密钥真值安全性强依赖 Nacos 控制台不可达，部署侧待办

## 3. 给 backend-agent 的任务清单（完整任务书）

**任务**：落地 /inner 面共享密钥准入控制。三份文档已定稿：设计 `docs/superpowers/specs/2026-11-09-inner-shared-secret-design.md`（组件全部细节在 §5）、契约 `docs/superpowers/contracts/2026-11-09-inner-access-secret-api.md`（语义与配置纪律）、本计划（任务分解与验收）。**开工前通读三文档**；实现细节冲突时以契约为准、仍歧义回报主控问架构，不自行猜测。

**执行序**（本计划 §0/§1，逐任务过验收）：
1. **T1 新建组件** cloud-common-inner-starter——四个主类（InnerAccessFilter / InnerSecretInterceptor / InnerProperties / CloudInnerAutoConfiguration）+ 自动装配注册文件 + 三个单测类 + 两处 pom（模块自建与 cloud-common 模块表）。过滤器拒绝写法照抄 cloud-common-mcp-starter 的 McpAuthFilter（常量时间比对 + 401 + R JSON + 字面量兜底）
2. **T2 三服务 pom 接线**（sso/system/bpmn 各加依赖）+ system/bpmn 两个 ArchitectureGuardTest 各加一条 pom 依赖断言（sso 不加）
3. **T3 Nacos 发键**：随机密钥 3 服务 × 双租户 = 6 次发布，整文件干净内容，真值不进任何仓库文件与回报
4. **T4 两份既有契约附记**（契约 §5 原文一字不差）+ CLAUDE.md 三处更新（内容见 T4 表）
5. **T5 重启与验证**：重启序 sso→bpmn→system→网关；冒烟脚本 6 断言；翻译程序式 client 强制验证（清 trans:* → 网关取 bpmn 列表看昵称；未继承则 translate-remote-starter builder customize() 补拦截器后重建复验）；cloud-e2e 全量只认 `grep -c '[FAIL]'`=0；对账任务下一轮无错；通过后 kill 全部服务交还

**红线**：不做任何 git 提交/推送；密钥真值不写入任何文件与回报；不改网关任何文件；不动既有 fallbackFactory/超时/熔断配置；不改两份既有契约的既有条文（只加附记）；CLAUDE.md 只改 T4 列明的三处。
