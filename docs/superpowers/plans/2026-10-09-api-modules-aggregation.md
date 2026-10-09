# api 模块目录聚合 实施计划（2026-10-09，B 级轻量 plan）

> 纯 Maven 目录结构调整，零 API 行为变化、零契约触碰。聚合结构对齐 `cloud-common/` 既有惯例（聚合 pom + 子模块 parent 指聚合模块）。

**Goal:** `cloud-bpmn-api`、`cloud-system-api` 两模块自 cloud-base 根迁入新聚合目录 `cloud-base/cloud-api/`，结构与 cloud-common 同构；GAV 坐标不变（消费方 pom 零感知）；构建/守护/运行时行为零变化。

**环境：** mvn 绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；git mv 保历史。

**总验收：**
1. `mvn -f cloud-base/pom.xml clean install` 全绿（311 单测 + 守护 19 规则——含路径改后的两 Feign 守护）
2. 起服（9201→9202→9203→18080）冒烟：登录 200 + `GET /system/leave/page` 200 带译文（Feign 声明式 + translate 程序式两链路装配完整）
3. 停 bpmn 降级一次：`POST /system/leave` → 3022「审批服务不可用」亚秒（自动装配 imports 在新路径 jar 内完整）
4. `cd cloud-e2e && npm run e2e:bpmn` BP 子集全绿（断言零修改）
5. kill 全部服务进程，端口清空

---

## Task 1: 聚合目录与搬家

**Files:**
- Create: `cloud-base/cloud-api/pom.xml`（packaging pom，parent=cloud-base，artifactId=cloud-api，modules 两行——照 cloud-common/pom.xml 同构，description「服务间契约 api 模块聚合」）
- Rename(git mv): `cloud-base/cloud-bpmn-api` → `cloud-base/cloud-api/cloud-bpmn-api`；`cloud-base/cloud-system-api` → `cloud-base/cloud-api/cloud-system-api`
- Modify: 两 api 模块 pom 的 `<parent>`：artifactId `cloud-base` → `cloud-api`（groupId/version 继承不变；默认 relativePath `../pom.xml` 随之指向 cloud-api/pom.xml，与 cloud-common 下 starter 同构）
- Modify: `cloud-base/pom.xml` `<modules>`：删 `cloud-bpmn-api`、`cloud-system-api` 两行，`cloud-common` 之后加一行 `<module>cloud-api</module>`；**dependencyManagement 中两 api 条目 GAV 不变零改动**

## Task 2: 路径引用批改

**Files:**
- Modify: `cloud-system/src/test/.../ArchitectureGuardTest.java` L306 扫描路径：`../cloud-bpmn-api, ../cloud-system-api` → `../cloud-api/cloud-bpmn-api, ../cloud-api/cloud-system-api`（规则 1 的三服务路径不变）
- Modify: `CLAUDE.md` 模块拓扑：两 api 行自根层级改入 `cloud-api/` 小树（照 cloud-common 缩进格式，置于 cloud-common 树之后、cloud-gateway 之前）
- Modify: `docs/superpowers/specs/2026-10-09-feign-api-fallback-design.md` §2.1 依赖图头部加一行修订记档：「2026-10-09 目录聚合：两 api 模块迁入 cloud-api/ 聚合目录（结构对齐 cloud-common，GAV 不变），见 plans/2026-10-09-api-modules-aggregation.md」
- 核对零遗漏：`grep -rn 'cloud-bpmn-api\|cloud-system-api' --include='*.xml' --include='*.java' cloud-base/ | grep -v 'cloud-api/'`——除 artifactId 引用（pom dependency/dependencyManagement，合法）外无路径引用残留

## Task 3: 验证收口

1. 全量构建（总验收 1）
2. 起服冒烟 + 停 bpmn 降级一次（总验收 2/3）
3. `npm run e2e:bpmn` BP 子集（总验收 4）
4. kill 全部进程核对端口清空（总验收 5）

**红线：** git 零操作（改动留工作区由主控统一提交）；消费方 pom（sso/system/bpmn/translate 两模块）**零改动**（GAV 不变）；e2e 断言零修改；不动 cloud-web/cloud-gateway。
