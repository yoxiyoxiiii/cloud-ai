---
description: 纯前端轨道开发（SOP 三轨道）：判级→架构/轻量plan→frontend-agent→build+联通+cloud-e2e场景+视觉核对
---

# 纯前端轨道开发（多 Agent 协作）

需求描述：$ARGUMENTS

你是主控。按 `docs/multi-agent-dev-sop.md`「需求轨道」的**纯前端轨道**执行，**superpowers 全程强制**（brainstorming → writing-plans → executing-plans；B 级走轻量 plan，不因单端需求跳过）。逐阶段推进，每阶段结束输出简报再进入下一阶段。

## ① 判级与设计

- 按 SOP「架构介入分级」判定 A/B 级（存疑按 A 级），开工简报说明判定理由与涉及面（页面/组件/路由/接口对接）
- **A 级**（UI 架构级改动/新页面模式/需新契约）：派 `architect-agent`，任务书贴需求全文；产出三文档（**纯前端无后端章**；需后端新接口则实为全栈轨道，建议改用 `/dev-fullstack`）+「给 frontend-agent 的任务清单」；主控规格审查
- **B 级**（样式/交互微调/文案/既有契约内对接）：主控直接写轻量 plan，无 contracts

## ② 实现

- 派 `frontend-agent`，贴方案 + 契约/计划 + 任务清单**全文**；提醒其遵循 `/frontend-page` 技能（工程定版/请求封装/按需引入/测试规范）
- 纯前端轨道后端无改动：对既有后端联调即可，无需 mock 等待

## ③ 验收

- 构建：**连续两次** `npm run build` 全绿（按需引入下 components.d.ts 首次生成，二次起类型更严）
- 联通：dev server 起后 `curl http://localhost:5173/api/system/demo/ping` 返回后端 R（需后端栈在线：sso/system/网关）
- 浏览器测试：cloud-e2e 增/改对应场景，`npm run e2e` **有头**跑（用户桌面可见）+ 全量回归；关键截图经 analyze_image 视觉核对
- 测试数据纪律：`e2e` 前缀+时间戳，绝不改 admin；结束清理；**截图验收后删**（artifacts 不进 git）

## ④ 审查与收尾

- 双审：按契约逐条 spec 审查 + 代码质量审查 → 修复循环至绿
- 汇总实施报告（文件清单/构建结果/e2e 场景与回归结果/视觉核对结论），**征得用户确认后** git 提交（信息含 `Co-Authored-By: Claude Code <noreply@anthropic.com>`）

## 红线

- 契约缺口/不符 → 上报主控协调，不得猜接口、不得单方改契约
- 不动 cloud-base 代码；e2e 黑盒纪律：cloud-e2e 禁止 import 前端内部代码
