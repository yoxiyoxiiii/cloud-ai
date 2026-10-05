# 多 Agent 协作开发 SOP

三角色协作：**架构-agent →（后端-agent ∥ 前端-agent）→ 集成与浏览器自动化测试**。角色定义见 `.claude/agents/`（主控在任何会话中用 Agent 工具按名调用）。

## 流程总览

```
用户需求
   │
   ▼
①架构-agent（superpowers: brainstorming → writing-plans）
   输出三文档：技术方案 specs/ + API 契约 contracts/ + 实施计划 plans/（后端/前端任务两章，标注并行）
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
3. **主控（会话本身）职责**：拆解派发、给子代理完整任务上下文（贴契约/计划全文，不让其自读零散文件）、处理 NEEDS_CONTEXT 上报、跑两阶段审查、git 操作与合并。
4. **模型分配**：架构/后端/前端实现 = 会话默认模型（glm-5.3）；截图视觉核对/简单冒烟 = 轻量档（≈glm-5.3-flash），由主控在派发时指定。
5. **测试金字塔**：后端单测+守护测试（构建期）→ 接口 curl（后端验收）→ 浏览器自动化+视觉核对（集成验收，装 Playwright MCP 后生效）。

## Playwright MCP 安装（一次性，用户终端执行）

```
claude mcp add playwright -- npx @playwright/mcp@latest
```

装好后重启 Claude Code 会话；未装期间前端-agent 按其定义走降级验证（build+curl+webReader）。

## 试点

首个跑通全流程的任务：登录页 + 布局 + 用户管理页（对接既有后端：POST /sso/auth/login、GET /system/user/page 等，见 `docs/superpowers/contracts/` 对应契约）。
