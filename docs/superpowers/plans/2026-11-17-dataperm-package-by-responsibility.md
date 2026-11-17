# 数据权限 starter 按对象职责分包实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `cloud-common-dataperm-starter` 主代码 21 类自包根扁平结构改为按对象职责分包（镜像 translate/mcp/inner 三 starter 的分包惯例），消费者 import 同步改写。

**Architecture:** 纯包结构重构——类零改动（仅 package/import 行与切面切点字面量），契约面、bean 装配、运行语义零变化。git mv 保 rename 历史。

**Tech Stack:** Maven 多模块 / Spring Boot 自动装配。

---

## 分包映射（21 主类 + config 不动）

| 新包 | 类 | 职责 |
|---|---|---|
| `annotation` | DataPermEvaluate、DataPermVo | 标注面：方法级求值标注 / VO 列载体白名单标注 |
| `evaluate` | DataPermEvaluateAspect、DataPermEvaluation、DataPermEvaluationProvider | 求值链：切面、求值结果与 holder、后端 SPI |
| `column` | DataPermColumnAdvice、DataPermColumnApplier、DataPermColumnContext、DataPermColumnContextFilter、DataPermColumns | 列裁剪链：advice、裁剪执行、ThreadLocal 上下文、请求级双清、VO 列声明断言 |
| `domain` | ColumnScope、DataScope、DataPermOperation、FilterColumn、FilterColumns、FilterColumnTypeEnum、PredicateOperatorEnum、RowFilter、RowGroup、RowPredicate | 规则与求值结果模型：行过滤组、列集、数据范围、谓词枚举、可过滤列声明与断言（行谓词元数据）、操作常量 |
| `util` | EntityFieldResolver | 支撑工具：实体字段取值（行谓词求值入参） |
| `config` | （两自动装配不动） | 自动装配 |

定性依据（易混淆两组）：FilterColumn/FilterColumns 断言的是**实体**字段（行谓词可过滤列，D32）→ domain；DataPermColumns 断言的是 **@DataPermVo VO** 字段（列裁剪声明）→ column。测试类与 fixture 留在包根 `com.cloudai.common.dataperm`（切面用例横跨 evaluate+column 两链，聚合更顺；fixture 全 public 无可见性问题）。

## 改动文件清单

- git mv：starter 主代码 21 文件（config 2 文件不动）
- starter 内部：package 声明 + 跨新包子引用补 import + **切面切点字面量**（`@annotation(com.cloudai.common.dataperm.DataPermEvaluate)` → `...dataperm.annotation.DataPermEvaluate`——字面量切点是 -parameters 陷阱的既定取舍，FQCN 必须同步）
- 消费者 import 改写 33 文件：cloud-system（main 12 + test 8）、cloud-bpmn（main 4 + test 1）、cloud-system-api（3）、dataperm-remote-starter（4）
- 文档：CLAUDE.md 模块树注释 + 「数据权限组件」约定段的包描述

## 验证标准

1. `mvn clean install`（-Dtest='!RedisUtilKeysTest'）全绿，surefire XML 聚合 1058+ tests / 0 failures / 0 errors
2. 全仓 grep `com\.cloudai\.common\.dataperm\.[A-Z]`（包根直引类名）零残留——所有引用均走子包或为 remote 模块自身包名
3. 切面切点字面量与新 FQCN 一致（守护：DataPermEvaluateAspectTest 织入真切面，切点错即红）

## 移交

- 无契约/接口/SQL 变化；bean 名与装配条件零变化
- 后续新增类按同职责归位：标注进 annotation、机器进 evaluate/column、纯模型进 domain
