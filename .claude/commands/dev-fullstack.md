---
description: 全栈轨道开发（SOP 三轨道）：架构→后端∥前端真并行→集成→cloud-e2e浏览器测试+视觉核对→双审
---

# 全栈轨道开发（多 Agent 协作）

需求描述：$ARGUMENTS

你是主控。按 `docs/multi-agent-dev-sop.md`「需求轨道」的**全栈轨道**执行（A 级——涉及新接口/新表/新页面），**superpowers 全程强制**。逐阶段推进，每阶段结束输出简报再进入下一阶段。

## ① 设计

- **阶段一（需求分析与讨论）**：派 `architect-agent`，任务书贴需求全文并指向既有 specs/contracts/plans；**只要求回报《需求分析》**（领域理解/边界/关键问题与候选方向/范围建议），不出方案；主控提炼要点呈用户讨论（关键分歧 AskUserQuestion 拍板），拍板结论经 SendMessage 带回**同一架构实例**
- **阶段二（方案设计）**：架构实例续跑产出三文档：技术方案 specs/ + API 契约 contracts/ + 实施计划 plans/（**后端/前端两章独立可并行**，每任务含文件清单与验收标准）
- 主控规格审查（可轻审）：契约完整性（入参/返回对象逐字段/错误码/权限标识）、与 CLAUDE.md 规范一致性、错误码与权限标识接续分配

## ② 并行实现（真并行）

- **两个 Agent 工具调用放同一条消息**派发 = 真并行（不同目录零冲突）：
  - `backend-agent`：按契约+计划实现 → 守护测试全绿 → curl 验收（先调 `/backend-spec` 技能——后端规范基准，不止 CRUD）
  - `frontend-agent`：按契约实现（后端未就绪先 mock）→ build + dev 联通（遵循 `/frontend-page` 技能）
- 处理 NEEDS_CONTEXT 上报；契约问题回流 architect-agent 修订，不得两端单方改

## ③ 集成

- 前端 dev proxy 指向网关 18080，起全栈（业务服务 → 网关最后 → dev 5173），链路冒烟

## ④ 浏览器自动化测试

- cloud-e2e 增/改本需求场景脚本（**场景脚本与功能代码同一 commit**），`npm run e2e` 有头跑 + 全量回归
- 关键截图（≥5 张：登录/错误态/列表/核心弹窗等）经 analyze_image 视觉核对；测试数据清理，截图验收后删

## ⑤ 双审与收尾

- 双审：按契约逐条 spec 审查 + 代码质量审查 → 修复循环至绿
- 汇总两端实施报告，**征得用户确认后** git 提交（信息含 `Co-Authored-By: Claude Code <noreply@anthropic.com>`）

## 红线

- 契约是前后端唯一对齐物，架构定稿后两端不得单方修改
- 按需引入红线（禁 `app.use(ElementPlus)`/全量样式）；e2e 测试数据纪律与黑盒纪律
