# 多 Agent 协作开发 SOP

三角色协作：**架构-agent →（后端-agent ∥ 前端-agent）→ 集成与浏览器自动化测试**。角色定义见 `.claude/agents/`（主控在任何会话中用 Agent 工具按名调用）。

**日常开发入口（自定义命令，`.claude/commands/`）**：`/dev-backend <需求>` 纯后端轨道 · `/dev-frontend <需求>` 纯前端轨道 · `/dev-fullstack <需求>` 全栈轨道 · `/dev-regression` 起全栈跑 e2e 回归。命令是薄编排层（本 SOP 的阶段清单化），流程细节仍以本文档为唯一来源。

## 需求轨道（按改动面组装；superpowers 全轨道强制——任何轨道 spec/plan 先行，不得因单端需求跳过）

| 轨道 | 判定 | 阶段组装 | 验收终点 |
|---|---|---|---|
| 全栈 | 前后端都动（新功能/契约新增） | 架构 → 后端∥前端 → 集成 → 浏览器测试 | 浏览器自动化 + 视觉核对 |
| 纯后端 | 仅 cloud-base 动 | A 级走架构 / B 级主控直带 → backend-agent | 单测+守护测试 → curl 逐条；**无 UI 不做浏览器测试** |
| 纯前端 | 仅 cloud-web 动 | A 级走架构 / B 级主控直带 → frontend-agent | build+代理联通 → 浏览器测试（cloud-e2e 增/改场景） |

**架构介入分级**：

- **A 级（必须架构-agent）**：新接口/新表/契约变更/跨服务/技术选型/UI 架构级改动 → 输出三文档，**只写涉及端的章节与任务清单**（纯后端需求无前端章）。
- **B 级（主控直接带实现 agent）**：bug 修复/样式与交互微调/文案/既有契约内的参数补全 → 无 contracts，但 superpowers 轻量 plan 仍必须（一份 plans 文档含验证标准）。
- **升级红线**：凡改动会变 API 行为/表结构/共享配置 → 一律升 A 级（B 级判定存疑时按 A 级走）。

**跨轨道规则（契约变更的前端影响面）**：纯后端需求若修改**既有契约**，前端虽不开发但受影响——主控必须跑 `cloud-e2e` 全量回归（`cd cloud-e2e && npm run e2e`）确认既有页面不破；回归红了才拉 frontend-agent 进场跟进。

## 流程总览（全栈轨道完整形态；单端轨道按上表裁剪）

```
用户需求
   │
   ▼
①架构-agent（superpowers: brainstorming → writing-plans）
   阶段一 需求分析与讨论：回报《需求分析》（领域理解/边界/关键问题与候选方向/范围建议），不出方案
   │  └─ 主控提炼要点呈用户讨论，关键分歧 AskUserQuestion 拍板；结论经 SendMessage 带回同一架构实例续跑
   阶段二 方案设计（输入 = 分析 + 拍板结论）：输出三文档：技术方案 specs/ + API 契约 contracts/ + 实施计划 plans/（后端/前端任务两章，标注并行）
   │
   ├── 主控对方案做规格审查（可轻审：契约完整性/规范一致性）
   │
   ▼
②并行派发（两个 Agent 工具调用放同一条消息 = 真并行；不同目录零冲突）
   ├── backend-agent：按契约+计划实现 → 守护测试全绿 → curl 验收
   └── frontend-agent：按契约实现（后端未就绪先 mock）→ build+dev 联通
   │
   ▼
③集成（前端-agent）：dev proxy 指向网关 18080，起全栈
   │
   ▼
④浏览器自动化测试（前端-agent；需 Playwright MCP）
   导航/表单/断言 + 截图 → analyze_image 视觉模型核对 UI
   │
   ▼
⑤双审（spec 审查按契约逐条 + quality 审查）→ 修复循环 → 合并 main
```

## 关键规则

1. **契约先行**：API 契约（`docs/superpowers/contracts/`）是前后端唯一对齐物，架构 agent 定稿后**两端都不得单方修改**；变更须回架构 agent 出契约修订。
2. **并行不阻塞**：前端拿契约即可开工（mock 模式），后端实现有 16 项守护测试保契约稳定；集成阶段切换真实联调。
3. **主控（会话本身）职责**：拆解派发、架构阶段一的讨论中转（提炼《需求分析》要点呈用户，拍板结论带回同一架构实例——子代理不直接面对用户）、给子代理完整任务上下文（贴契约/计划全文，不让其自读零散文件）、处理 NEEDS_CONTEXT 上报、跑两阶段审查、git 操作与合并。
4. **模型分配**：架构/后端/前端实现 = 会话默认模型（glm-5.3）；截图视觉核对/简单冒烟 = 轻量档（≈glm-5.3-flash），由主控在派发时指定。
5. **测试金字塔（按轨道取顶）**：后端单测+守护测试（构建期）→ 接口 curl（后端验收）→ 浏览器自动化+视觉核对（**有 UI 改动轨道**的集成验收；纯后端轨道止于 curl）。

## Playwright MCP 安装（一次性，用户终端执行）

```
claude mcp add playwright -- npx @playwright/mcp@latest
```

装好后重启 Claude Code 会话；未装期间前端-agent 按其定义走降级验证（build+curl+webReader）。

## 试点

首个跑通全流程的任务：登录页 + 布局 + 用户管理页（对接既有后端：POST /sso/auth/login、GET /system/user/page 等，见 `docs/superpowers/contracts/` 对应契约）。
