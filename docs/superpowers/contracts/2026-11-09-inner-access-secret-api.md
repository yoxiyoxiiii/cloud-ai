# 服务间 /inner 面准入契约（共享密钥）

- 日期：2026-11-09
- 状态：**inner 准入现行版（v1）**——由架构-agent 定稿（设计 `docs/superpowers/specs/2026-11-09-inner-shared-secret-design.md`）
- **域性质（先读）**：本契约是**后端服务间横切关注点**——`/inner/**` 面的准入语义，横跨 system 域（`2026-10-07-inner-api.md`）与 bpmn 域（`2026-10-08-approval-platform-api.md` §4）两份既有 inner 契约，**不是前端对齐物**：前端不得调用任何 /inner/** 端点（网关 403 是设计行为），本契约对前端零影响、零新端点、零载荷变化
- 约定：发现文档与实测不符，回报主控修订契约，不自行猜测

## 0. 变更点清单

| # | 对象 | 变更 | 性质 |
|---|---|---|---|
| 1 | 准入语义 | 全部 `/inner/**` 端点要求请求头 `X-Inner-Secret` 与配置密钥常量时间比对一致，否则 HTTP 401 | 行为变更（调用方须携带密钥；端点签名/载荷/成功响应零变化） |
| 2 | 组件 | 新建 `cloud-common-inner-starter`，sso/system/bpmn 引 jar 即生效 | 新公共组件 |
| 3 | 配置 | 新键 `cloud.inner.shared-secret`（Nacos per-service，双租户） | 配置新增 |
| 4 | 既有契约 | `2026-10-07-inner-api.md` §5.1「无认证」口径废止（附记原文见本文 §5）；`2026-10-08-approval-platform-api.md` §4 加附记 | 附记指向 |

## 1. 准入语义

- **请求头**：`X-Inner-Secret`，值为共享密钥原文（全体服务同一把；每服务独立密钥为演进项，见设计 §12）
- **校验**：被调方将 header 值与 `cloud.inner.shared-secret` 配置值做 `MessageDigest.isEqual` 常量时间比对（UTF-8 字节）——三态一律拒绝：**未携带 / 值不匹配 / 被调方未配置**（未配置 = fail-closed，拒绝全部，不阻断启动）
- **拒绝响应**：真实 HTTP 401 + R JSON 统一报文（网关 AuthGlobalFilter / MCP McpAuthFilter 先例——认证拒绝用真实状态码，与服务面「HTTP 200 + body.code」业务错误约定并存）：

```json
{ "code": 401, "msg": "inner 认证失败", "data": null }
```

- 统一报文**不区分**缺头/错值/未配置（不给攻击者校验进度 oracle）；服务端日志内部记录原因 + uri + remoteAddr 供运维排障
- **通过后的行为零变化**：请求进入既有 spring security 链，主体匿名（inner 调用方不发 X-User-*）；inner 端点本就无 @PreAuthorize，入参显式传 applyUser/operator 的既有口径不变——**调用方服务对这些参数自负其责**（密钥认证的是「服务」主体，不是用户）
- **鉴权边界不变**：网关 `inner-block-*` 三路由 SetStatus 403 保留为外层（纵深防御），本契约是服务侧第二层；MQ/xxl-job 不在本契约范围

## 2. 组件装配语义（cloud-common-inner-starter）

- **引 jar 即生效，无 enabled 开关**（配置即生效）：与 MCP 组件「`cloud.mcp.enabled` 默认关」的差异是刻意的——MCP 端点是可选特性需要开关，inner 端点在 system/bpmn 永远存在，没有「合法不保护」态（论证见设计文档 §4）
- 入站：`InnerAccessFilter` 以 `FilterRegistrationBean` 注册，URL 模式固定 `/inner/*`（不可配——inner 路径是本仓强约定），注册序 `HIGHEST_PRECEDENCE + 10`（字符编码过滤器之后、spring security 链之前；纯拒绝闸门不触碰 SecurityContext）。仅 servlet 环境装配
- 出站：`InnerSecretInterceptor` 声明为上下文 bean，spring-cloud-openfeign 按全局 `RequestInterceptor` 收集——**全部声明式 Feign client 自动携带密钥头，业务代码零改动**，登录链/MQ 消费线程/xxl-job 线程无差别覆盖。密钥未配置时跳过注入（不加空头）
- 启动哨兵：密钥为空时启动打 WARN（fail-closed 提示），不阻断启动
- **程序式特例（translate-remote-starter）**：`SystemTranslateClient` 经 FeignClientBuilder 程序式构建，全局拦截器继承性**实现时强制验证**（方法与未继承时的 fallback 见设计 §8——fallback 为在该 builder `customize()` 内显式补拦截器，豁免沿该 starter 既有特例先例记档）

## 3. 配置与 Nacos 放置

| 项 | 值 |
|---|---|
| 配置键 | `cloud.inner.shared-secret`（唯一键；无 enabled） |
| 放置 | Nacos **per-service yaml**：`cloud-sso.yaml` / `cloud-system.yaml` / `cloud-bpmn.yaml` 三份，各加同一键同值 |
| 双租户纪律 | `''` 与 `public` 两租户副本发布**完全相同的干净内容**（整文件全量重发，非追加）——否则 Nacos 3.x 同步合并出重复嵌套键、服务起不来（`cloud.mcp.client-secret` 实录）。共 3 服务 × 2 租户 = **6 次发布** |
| 真值纪律 | 密钥真值**仅存 Nacos**，不入仓库、不入设计/实施文档、不入实施报告、不落冒烟脚本（脚本经环境变量传入） |
| 网关/AI 基座 | 不配置不引组件——网关不发起 inner 调用（外层屏蔽照旧）；cloud-base-ai 只调 9202 `/mcp`（自有 MCP 密钥体系），不碰 /inner |

**发布 runbook（顺序即安全）**：① 向 Nacos 发键（旧 jar 不识别未知键，零影响）→ ② 重建并按下序重启：**sso → bpmn → system → 网关（最后）**（消费方先行，登录链全程零降级；窗口期瞬态 401 由既有降级链吸收且自愈，逐链路路径见设计 §6/§7）。

## 4. 消费方调用点对照表（现状全集，全局拦截器零改动覆盖）

| 调用链 | 客户端 | 线程形态 | 无用户 token | 窗口期降级（既有语义，零新代码） |
|---|---|---|---|---|
| sso→system 登录/refresh 取用户 | SystemUserClient | 登录请求线程 | 是 | 登录报 2002，重试恢复 |
| bpmn→system 审批人校验 | SystemUserClient | MQ 消费线程 | 是 | 消费重试（超窗 DLQ 人工） |
| bpmn→system 数据权限求值 | DataPermClient | 请求线程 | 服务级调用 | 求值降级 |
| bpmn→system 翻译回源 | SystemTranslateClient（程序式，继承性待验证） | 请求/缓存线程 | 是 | 空译文 |
| system→bpmn 发起/撤销审批 | BpmnApprovalClient | 请求+MQ 事务线程 | 是 | 中性失败 R 转译 3022 |
| system→bpmn 投影对账 | BpmnApprovalClient | xxl-job 线程 | 是 | 本轮放弃，下轮补拉 |

## 5. 既有契约附记原文（本轮落地时由 backend-agent 原样粘贴）

**（a）`docs/superpowers/contracts/2026-10-07-inner-api.md`** —— §5.1 第 1 条「内网信任」句末追加附记：

> **附记（2026-11-09）**：本条「inner 端点无认证」口径**废止**——inner 面现强制共享密钥准入（请求头 `X-Inner-Secret`，未携带/不匹配/服务未配置一律 HTTP 401 + R JSON），语义与配置见 `2026-11-09-inner-access-secret-api.md`；本域端点签名与载荷零变化。

**（b）`docs/superpowers/contracts/2026-10-08-approval-platform-api.md`** —— §4 章节标题下（既有 2026-10-09 附记之后）追加：

> **附记（2026-11-09）**：本节三端点已纳入 inner 面共享密钥准入（请求头 `X-Inner-Secret`，缺失/不匹配一律 HTTP 401）——语义与配置见 `2026-11-09-inner-access-secret-api.md`；端点签名与载荷零变化。

## 6. 验证口径

- 单测：过滤器四分支（无头/错值/未配置/正确）+ 拦截器两态（注头/跳过）+ 装配（bean 存在性、URL 模式、启动 warn）
- 真连接冒烟（脚本 `cloud-base/scripts/smoke/smoke_inner_secret.sh`，密钥经环境变量）：直连 9202 `/inner/user/all` 无密钥 401 / 带密钥 200；直连 9203 `/inner/approval/status-list` 无密钥 401；网关 18080 `/system/inner/user/all` 仍 403；经网关登录 200；翻译程序式链路（清 `trans:*` 后经网关取含翻译字段的 bpmn 列表，昵称非空）
- 回归：cloud-e2e 全量（退出码假绿陷阱——只认 `grep -c '[FAIL]'`）；xxl-job 对账下一分钟轮次无错

## 7. 错误码声明

**零新增错误码**：拒绝响应 body 中的 `401` 为 HTTP 状态码字面量镜像（网关/MCP 先例），不占 1xxx/2xxx/3xxx/5xxx 分段账本。消费方对 401 的处理复用既有 FeignException→fallbackFactory→`code!=SUCCESS` 转译链，零新代码。
