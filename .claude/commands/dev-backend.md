---
description: 纯后端轨道开发（SOP 三轨道）：判级→架构/轻量plan→backend-agent→守护测试+curl验收→契约变更加e2e回归
---

# 纯后端轨道开发（多 Agent 协作）

需求描述：$ARGUMENTS

你是主控。按 `docs/multi-agent-dev-sop.md`「需求轨道」的**纯后端轨道**执行，**superpowers 全程强制**（brainstorming → writing-plans → executing-plans；B 级走轻量 plan，不因单端需求跳过）。逐阶段推进，每阶段结束输出简报再进入下一阶段。

## ① 判级与设计

- 按 SOP「架构介入分级」判定 A/B 级（存疑按 A 级），开工简报说明判定理由与涉及面（哪些服务/表/契约）
- **A 级**：用 Agent 工具派 `architect-agent`，任务书贴需求全文并指向既有 specs/contracts/plans；先要求回报《需求分析》（领域/边界/关键问题与候选方向/范围建议，不出方案），主控呈用户讨论拍板后经 SendMessage 续跑同一实例产出三文档（**纯后端无前端章**）+「给 backend-agent 的任务清单」；随后主控做规格审查（契约完整性/规范一致性/错误码与权限标识接续）
- **B 级**：不派架构，主控直接按 superpowers:writing-plans 写轻量 plan（`docs/superpowers/plans/` 下，含文件清单与验证标准），无 contracts
- 改动若触及**既有契约**（改 API 行为）：在简报中显著标注，③ 阶段须加跑 e2e 回归

## ② 实现

- 派 `backend-agent`，贴方案/契约（或轻量 plan）+ 任务清单**全文**（不让其自读零散文件）；新增 CRUD 提醒其先调 `/backend-crud` 技能

## ③ 验收

- 构建：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install`（可 `-pl <模块> -am`）全绿，守护测试通过
- 运行时：起相关服务（业务服务 → 网关 18080 最后）按契约 curl 逐条实证（Windows 陷阱见 CLAUDE.md；中文 JSON 别用 curl 直发）
- **触及既有契约**：起全栈（含 dev 5173）后 `cd cloud-e2e && npm run e2e` 全量回归；红了拉 `frontend-agent` 进场修复（收尾升级为全栈轨道）
- 验收后按需停服（netstat 找 PID + taskkill）

## ④ 审查与收尾

- 双审：按契约逐条 spec 审查 + 代码质量审查 → 修复循环至绿
- 汇总实施报告（文件清单/测试计数/curl 真实输出/e2e 回归结果），**征得用户确认后** git 提交（信息含 `Co-Authored-By: Claude Code <noreply@anthropic.com>`）

## 红线

- 契约不得单方修改——发现缺失/冲突即停，回 architect-agent 修订
- 不动 cloud-web、不动守护测试规则；涉库测试数据 `e2e` 前缀+时间戳，绝不改 admin
