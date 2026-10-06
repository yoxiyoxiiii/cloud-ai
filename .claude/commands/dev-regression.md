---
description: 起全栈跑 cloud-e2e 全量回归（契约变更后的前端影响面验证 / 任意时点的整体回归）
---

# 全栈 e2e 回归

背景/目的：$ARGUMENTS（可空——默认例行回归）

你是主控。目标：起全栈 → `cloud-e2e` 全量回归 → 出结果报告。

## ① 起栈（已在监听的端口跳过，勿重复起）

前置（本机常驻）：MySQL 3306 / Redis 6379 / Nacos 8848。

按序启动并等端口监听（java -jar，路径见 CLAUDE.md）：
1. cloud-sso 9201
2. cloud-system 9202（及已实现的 bpmn 9203）
3. cloud-gateway 18080（**最后**）
4. dev server 5173（cloud-web 下 `npm run dev`，若未起）

冒烟：`curl http://localhost:5173/api/system/demo/ping` 应返回后端 R ok。

## ② 回归

- `cd cloud-e2e && npm run e2e`（**有头**模式，用户可在桌面看到全过程）
- 结果口径：15+ 场景全 PASS（已知 SKIP 项注明原因即算过）

## ③ 报告与清理

- 汇总 PASS/FAIL/SKIP 与异常清单（网络失败/console error）；FAIL 给定位分析（区分：回归缺陷 vs 已知环境抖动）
- 核验测试数据已清理（库里仅剩 admin）
- 截图按产物处置策略：报告交付后删除；是否停服征询用户
