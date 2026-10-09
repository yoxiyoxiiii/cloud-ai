# Feign 统一接口层与降级策略 实施计划（2026-10-09）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 服务间 Feign 声明归位到提供方 `-api` 模块（cloud-bpmn-api、cloud-system-api）+ fallbackFactory(resilience4j) 等价降级 + 超时治理 1s/2s + 规范三层落位；端到端 code/msg 与现行逐字一致（契约与 e2e 断言零修订）。

**Architecture:** 见 `docs/superpowers/specs/2026-10-09-feign-api-fallback-design.md`（D1-D8 决策）。api 模块仅依赖 core-starter（bpmn-api 加 validation-api），fallbackFactory 经自动装配注册；消费方三件套 = 引 jar + 显式 clients + yml 开关超时；translate-remote-starter 豁免（仅 UserEntry import 改指向）。

**Tech Stack:** Spring Cloud OpenFeign 4.1.x + spring-cloud-starter-circuitbreaker-resilience4j（BOM 管版本）。

**环境：** mvn 用绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；MySQL 5.7 原生（root/空密码）；Nacos 8848 / Redis 6379 Docker 已运行。Git Bash curl 发中文 JSON 是 GBK 会 500——中文入参用 ASCII。

**总验收（全部任务完成后）：**
1. `$MVN -f cloud-base/pom.xml clean install` 全绿（含两新守护规则与 api 模块单测）
2. 停 cloud-bpmn：网关带 token `POST /system/leave` 返回 `code=3022, msg="审批服务不可用"` 且秒级返回（超时生效实证）
3. 停 cloud-system：`POST /sso/login` 返回 `code=2002, msg="用户服务不可用，请稍后重试"`
4. 全部起回：登录→请假→审批全链路冒烟正常；`cd cloud-e2e && npm run e2e` BP 全量绿（断言零变化）

---

## 后端任务章（纯后端需求，无前端章）

### Task 1: cloud-bpmn-api 模块（契约类平移 + client + fallbackFactory + 自动装配）

**Files:**
- Modify: `cloud-base/pom.xml`（modules 增 `cloud-bpmn-api`、`cloud-system-api` 两行——system-api Task 2 建，modules 行本任务一次加齐）
- Create: `cloud-base/cloud-bpmn-api/pom.xml`
- Create: `cloud-base/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/client/BpmnApprovalClient.java`
- Create: `cloud-base/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/fallback/BpmnApprovalClientFallbackFactory.java`
- Create: `cloud-base/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/BpmnApiAutoConfiguration.java`
- Create: `cloud-base/cloud-bpmn-api/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `cloud-base/cloud-bpmn-api/src/main/java/com/cloudai/bpmn/api/domain/` 下 6 个契约类（自 bpmn 本地平移，仅包名改）
- Test: `cloud-base/cloud-bpmn-api/src/test/java/com/cloudai/bpmn/api/fallback/BpmnApprovalClientFallbackFactoryTest.java`

- [ ] **Step 1: 父 pom modules 加一行（cloud-system-api 行留给 Task 2，避免 reactor 找不到目录）**

`cloud-base/pom.xml` 的 `<modules>` 中 `cloud-common` 之后插入：

```xml
        <module>cloud-bpmn-api</module>
```

- [ ] **Step 2: 写 cloud-bpmn-api/pom.xml**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.cloudai</groupId>
        <artifactId>cloud-base</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>cloud-bpmn-api</artifactId>
    <name>cloud-bpmn-api</name>
    <description>cloud-bpmn 服务间契约（Feign 客户端 + inner 契约模型 + 降级工厂）</description>

    <dependencies>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-core-starter</artifactId>
        </dependency>
        <!-- 契约模型校验注解（@NotBlank 等）编译期可见；运行时校验器由提供方服务自带 -->
        <dependency>
            <groupId>jakarta.validation</groupId>
            <artifactId>jakarta.validation-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

（设计 D1：依赖仅 core-starter + validation-api + openfeign 注解；版本全由父 pom BOM 收敛免版本号。若构建红提示 validation-api 无版本，则父 pom dependencyManagement 补 `jakarta.validation:jakarta.validation-api` 随 Boot BOM 行。）

- [ ] **Step 3: 平移 6 个契约类到 `com.cloudai.bpmn.api.domain`**

内容与 `cloud-bpmn/src/main/java/com/cloudai/bpmn/dto|vo/` 下同名类**逐字一致**，仅三处变：`package` 改 `com.cloudai.bpmn.api.domain`；类内引用 `VariableItem` 的 import 改 `com.cloudai.bpmn.api.domain.VariableItem`（同包可省 import）；javadoc 首行补一句「（cloud-bpmn-api 归位 2026-10-09）」。涉及：`ApprovalCreateInnerRequest`、`ApprovalCancelInnerRequest`、`ApprovalStatusQueryInnerRequest`、`VariableItem`、`InnerApprovalCreateVo`、`InnerApprovalStatusVo`。字段/注解/serialVersionUID 原样（校验注解 @NotBlank/@Size/@Valid/@NotEmpty 全保留）。

- [ ] **Step 4: 写 BpmnApprovalClient（fallbackFactory 版）**

```java
package com.cloudai.bpmn.api.client;

import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.common.core.domain.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * cloud-bpmn 审批平台 /inner 客户端（契约 2026-10-08-approval-platform-api §4：
 * create/status-list/cancel 三端点；网关屏蔽 /bpmn/inner/**，Feign 走注册发现直连内网）。
 * <p>统一声明归位 cloud-bpmn-api（2026-10-09）：消费方引 jar + @EnableFeignClients(clients=...) 显式接入；
 * 降级走 fallbackFactory（resilience4j，需消费方开 spring.cloud.openfeign.circuitbreaker.enabled=true），
 * 返回中性失败 R 由调用方既有 code!=SUCCESS 分支转译域码（system 侧 3022——等价迁移）。</p>
 */
@FeignClient(name = "cloud-bpmn", contextId = "systemBpmnApprovalClient", path = "/inner/approval",
        fallbackFactory = BpmnApprovalClientFallbackFactory.class)
public interface BpmnApprovalClient {

    /** 发起审批（uk 查重→insert→启动实例，bpmn 同事务）；返回审批单 id 与初始状态 */
    @PostMapping("/create")
    R<InnerApprovalCreateVo> create(@RequestBody ApprovalCreateInnerRequest req);

    /** 批量查状态（businessKey 全集回包，无审批单的键 null）；纠偏回源单批 ≤100 由调用方分批 */
    @PostMapping("/status-list")
    R<List<InnerApprovalStatusVo>> statusList(@RequestBody ApprovalStatusQueryInnerRequest req);

    /** 按业务键撤销审批（4010→4012→4011 校验序在 bpmn 侧） */
    @PostMapping("/cancel")
    R<Void> cancel(@RequestBody ApprovalCancelInnerRequest req);
}
```

- [ ] **Step 5: 写 BpmnApprovalClientFallbackFactory**

```java
package com.cloudai.bpmn.api.fallback;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * BpmnApprovalClient 降级工厂（设计 D3/D4）：log.error 根因（cause）后返回中性失败 R
 * （code=1002）——调用方走既有 code!=SUCCESS 分支转译域码（system 侧 3022「审批服务不可用」），
 * 端到端与现行 catch 路径逐字等价。bean 经 BpmnApiAutoConfiguration 注册（引 jar 即生效）。
 */
@Slf4j
public class BpmnApprovalClientFallbackFactory implements FallbackFactory<BpmnApprovalClient> {

    /** 中性降级码：1xxx 通用业务失败（语义演进的中性下游码记移交备忘，本轮等价迁移） */
    static final int DEGRADED_CODE = 1002;

    static final String DEGRADED_MSG = "cloud-bpmn 服务不可用";

    @Override
    public BpmnApprovalClient create(Throwable cause) {
        return new BpmnApprovalClient() {

            @Override
            public R<InnerApprovalCreateVo> create(ApprovalCreateInnerRequest req) {
                log.error("审批服务降级（create）: businessKey={}", req.getBusinessKey(), cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }

            @Override
            public R<List<InnerApprovalStatusVo>> statusList(ApprovalStatusQueryInnerRequest req) {
                log.error("审批服务降级（statusList）: keys={}", req.getBusinessKeys() == null
                        ? 0 : req.getBusinessKeys().size(), cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }

            @Override
            public R<Void> cancel(ApprovalCancelInnerRequest req) {
                log.error("审批服务降级（cancel）: businessKey={}", req.getBusinessKey(), cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }
        };
    }
}
```

- [ ] **Step 6: 写自动装配 + imports 文件**

```java
package com.cloudai.bpmn.api;

import com.cloudai.bpmn.api.fallback.BpmnApprovalClientFallbackFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * cloud-bpmn-api 自动装配：注册降级工厂 bean——api 包不在消费方组件扫描范围，
 * @FeignClient(fallbackFactory=...) 要求其为 Spring bean（引 jar 即生效，零配置）。
 * circuitbreaker 开关关闭时 bean 空闲无害（不会被调用）。
 */
@AutoConfiguration
public class BpmnApiAutoConfiguration {

    @Bean
    public BpmnApprovalClientFallbackFactory bpmnApprovalClientFallbackFactory() {
        return new BpmnApprovalClientFallbackFactory();
    }
}
```

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 内容一行：

```
com.cloudai.bpmn.api.BpmnApiAutoConfiguration
```

- [ ] **Step 7: 写单测（先红后绿可跳过——新类新测，直接绿）**

```java
package com.cloudai.bpmn.api.fallback;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 降级返回中性失败（设计 D4 等价表）：三方法 code/msg 固定，data=null */
class BpmnApprovalClientFallbackFactoryTest {

    private final BpmnApprovalClientFallbackFactory factory = new BpmnApprovalClientFallbackFactory();

    @Test
    void create_degradesToNeutralFail() {
        BpmnApprovalClient client = factory.create(new RuntimeException("connection refused"));
        R<InnerApprovalCreateVo> resp = client.create(new ApprovalCreateInnerRequest());
        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getMsg()).isEqualTo("cloud-bpmn 服务不可用");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void statusList_degradesToNeutralFail() {
        BpmnApprovalClient client = factory.create(new RuntimeException("timeout"));
        R<List<InnerApprovalStatusVo>> resp = client.statusList(new ApprovalStatusQueryInnerRequest());
        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getData()).isNull();
    }

    @Test
    void cancel_degradesToNeutralFail() {
        BpmnApprovalClient client = factory.create(new RuntimeException("timeout"));
        R<Void> resp = client.cancel(new ApprovalCancelInnerRequest());
        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getMsg()).isEqualTo("cloud-bpmn 服务不可用");
    }
}
```

- [ ] **Step 8: 构建验证 + 提交**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-bpmn-api -am -q`
Expected: BUILD SUCCESS（3 单测绿）

```bash
git add cloud-base/pom.xml cloud-base/cloud-bpmn-api
git commit -m "feat: cloud-bpmn-api 契约模块（BpmnApprovalClient+fallbackFactory 自动装配+六契约类平移）"
```

**验收标准：** 模块独立构建绿；契约类与 bpmn 本地原版 diff 仅包名/javadoc 差异；fallbackFactory 三方法返回码 1002/msg「cloud-bpmn 服务不可用」。

---

### Task 2: cloud-system-api 模块（含 UserEntry 迁移与 translate 侧 import 批改——原子完成）

**Files:**
- Modify: `cloud-base/pom.xml`（modules 增 `cloud-system-api`）
- Create: `cloud-base/cloud-system-api/pom.xml`
- Create: `cloud-base/cloud-system-api/src/main/java/com/cloudai/system/api/client/SystemUserClient.java`
- Create: `cloud-base/cloud-system-api/src/main/java/com/cloudai/system/api/fallback/SystemUserClientFallbackFactory.java`
- Create: `cloud-base/cloud-system-api/src/main/java/com/cloudai/system/api/SystemApiAutoConfiguration.java`
- Create: `cloud-base/cloud-system-api/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Create: `cloud-base/cloud-system-api/src/main/java/com/cloudai/system/api/domain/LoginUserDTO.java`（system 原版平移）
- Create+Delete: `UserEntry.java` 自 `cloud-common-translate-starter` 迁至 `cloud-system-api`（translate 侧删旧建新）
- Modify: `cloud-base/cloud-common/cloud-common-translate-starter/pom.xml`、`cloud-base/cloud-common/cloud-common-translate-remote-starter/pom.xml`（加 cloud-system-api 依赖）
- Modify（import 批改 `com.cloudai.common.translate.domain.UserEntry` → `com.cloudai.system.api.domain.UserEntry`）:
  - `cloud-common/cloud-common-translate-starter/.../core/TranslationCacheService.java`
  - `cloud-common/cloud-common-translate-starter/.../provider/UserSourceProvider.java`
  - `cloud-common/cloud-common-translate-remote-starter/.../client/SystemTranslateClient.java`
  - `cloud-common/cloud-common-translate-remote-starter/.../provider/RemoteUserSourceProvider.java`
  - `cloud-system/.../service/translate/UserSourceProvider.java`
- Test: `cloud-base/cloud-system-api/src/test/java/com/cloudai/system/api/fallback/SystemUserClientFallbackFactoryTest.java`

- [ ] **Step 1: modules 加行 + 写 pom**

pom 与 Task 1 同构，差异：`artifactId/name/description` 为 cloud-system-api（"cloud-system 服务间契约"），**无 validation-api**（LoginUserDTO/UserEntry 无校验注解）。

- [ ] **Step 2: 平移 LoginUserDTO（system 原版为基准）**

`com.cloudai.system.api.domain.LoginUserDTO`：内容 = `cloud-system/.../dto/LoginUserDTO.java` 逐字（含 javadoc「sso 登录所需的用户聚合…」与字段注释），仅 package 改。**保留 `password` 字段原样**（含密码散列，仅内网 /inner 通道返回——sso 与 system 两消费方本就如此使用；api 模块不引 jackson 注解，不加 @JsonProperty，与现两份一致）。

- [ ] **Step 3: UserEntry 迁移（原子：建新+批改+删旧，一次提交）**

1. 新建 `com.cloudai.system.api.domain.UserEntry`：内容 = translate-starter 原版逐字改 package；javadoc 补「（cloud-system-api 归位 2026-10-09——/inner/user/all 双消费方统一契约）」。
2. 上述 5 个 translate/system 文件 import 批改（旧 `com.cloudai.common.translate.domain.UserEntry` → 新）。
3. 删除 `cloud-common-translate-starter/.../domain/UserEntry.java`。
4. 两个 translate 模块 pom `<dependencies>` 增：

```xml
        <!-- UserEntry 归位 system-api（设计 D2）：契约模型共享，纯 jar 无装配副作用 -->
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-system-api</artifactId>
        </dependency>
```

- [ ] **Step 4: 写 SystemUserClient（两端点合一）**

```java
package com.cloudai.system.api.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.LoginUserDTO;
import com.cloudai.system.api.domain.UserEntry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * cloud-system 内部用户客户端（契约 2026-10-07-inner-api §2.1/§2.2：/inner/user/{account} + /inner/user/all）。
 * <p>统一声明归位 cloud-system-api（2026-10-09）：sso（登录/refresh）与 bpmn（审批人投影）共用本声明；
 * 降级走 fallbackFactory——返回值按消费方既有终态定制（设计 D4 等价表：2002/1002），
 * 调用方既有 code!=SUCCESS 分支零改动即端到端等价。</p>
 */
@FeignClient(name = "cloud-system", contextId = "systemUserClient", path = "/inner/user",
        fallbackFactory = SystemUserClientFallbackFactory.class)
public interface SystemUserClient {

    /** 按账号取登录聚合（账号不存在 data=null，登录失败语义由 sso 判定） */
    @GetMapping("/{account}")
    R<LoginUserDTO> getUserByAccount(@PathVariable("account") String account);

    /** 全量用户投影（含停用——翻译/审批人存在性校验宽松口径，契约 §2.2） */
    @GetMapping("/all")
    R<List<UserEntry>> listAll();
}
```

- [ ] **Step 5: 写 SystemUserClientFallbackFactory（按方法定制返回——等价迁移核心）**

```java
package com.cloudai.system.api.fallback;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.SystemUserClient;
import com.cloudai.system.api.domain.LoginUserDTO;
import com.cloudai.system.api.domain.UserEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * SystemUserClient 降级工厂（设计 D4 等价表）：返回码按消费方既有终态定制——
 * getUserByAccount 返回 2002（sso login/refresh 的 code!=200 分支透传后逐字等价）；
 * listAll 返回 1002（bpmn 审批人校验 users==null 分支 → BusinessException("用户服务不可用") 默认 1002）。
 * 中性降级码演进记移交备忘。
 */
@Slf4j
public class SystemUserClientFallbackFactory implements FallbackFactory<SystemUserClient> {

    @Override
    public SystemUserClient create(Throwable cause) {
        return new SystemUserClient() {

            @Override
            public R<LoginUserDTO> getUserByAccount(String account) {
                log.error("cloud-system 用户服务降级（getUserByAccount）: account={}", account, cause);
                return R.fail(2002, "用户服务不可用，请稍后重试");
            }

            @Override
            public R<List<UserEntry>> listAll() {
                log.error("cloud-system 用户服务降级（listAll）", cause);
                return R.fail(1002, "cloud-system 服务不可用");
            }
        };
    }
}
```

- [ ] **Step 6: 自动装配**（同 Task 1 模式：`SystemApiAutoConfiguration` 注册 `SystemUserClientFallbackFactory` bean + imports 文件一行 `com.cloudai.system.api.SystemApiAutoConfiguration`）

- [ ] **Step 7: 单测**（同 Task 1 模式：两方法各自断言——getUserByAccount code=2002 msg「用户服务不可用，请稍后重试」data null；listAll code=1002 msg「cloud-system 服务不可用」data null）

- [ ] **Step 8: 构建验证 + 提交**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system-api,cloud-common/cloud-common-translate-starter,cloud-common/cloud-common-translate-remote-starter -am -q`
Expected: BUILD SUCCESS（translate 两模块随 UserEntry 迁移编译绿）

```bash
git add cloud-base/pom.xml cloud-base/cloud-system-api cloud-base/cloud-common
git commit -m "feat: cloud-system-api 契约模块（SystemUserClient 两端点合一+fallbackFactory）+ UserEntry 归位批改 translate 侧 import"
```

**验收标准：** 全局唯一 UserEntry（旧文件已删、grep `com.cloudai.common.translate.domain.UserEntry` 零命中）；translate 两模块构建绿；fallbackFactory 两方法返回码按等价表。

---

### Task 3: cloud-bpmn 切换到 api 模块

**Files:**
- Delete: `cloud-bpmn/.../client/SystemUserClient.java`
- Delete: `cloud-bpmn/.../dto/ApprovalCreateInnerRequest.java`、`ApprovalCancelInnerRequest.java`、`ApprovalStatusQueryInnerRequest.java`、`VariableItem.java`；`cloud-bpmn/.../vo/InnerApprovalCreateVo.java`、`InnerApprovalStatusVo.java`
- Modify（import 批改）: `cloud-bpmn/.../controller/feign/InnerApprovalController.java`、`cloud-bpmn/.../service/ApprovalWorkflowService.java`（`com.cloudai.bpmn.dto.ApprovalXxxInnerRequest|VariableItem` / `com.cloudai.bpmn.vo.InnerApprovalXxxVo` → `com.cloudai.bpmn.api.domain.*`；`com.cloudai.bpmn.client.SystemUserClient` → `com.cloudai.system.api.client.SystemUserClient`）
- Modify: `cloud-bpmn/.../BpmnApplication.java`、`cloud-bpmn/pom.xml`、`cloud-bpmn/src/main/resources/application.yml`
- Modify（测试）: `cloud-bpmn/src/test/.../ApprovalWorkflowServiceTest.java` 等 mock SystemUserClient 的测试（import 批改）

- [ ] **Step 1: pom 加依赖**

`cloud-bpmn/pom.xml` `<dependencies>` 增：

```xml
        <!-- 服务间契约（自家 /inner/approval 契约类 + 消费 system 用户投影）——统一声明归位 2026-10-09 -->
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-bpmn-api</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-system-api</artifactId>
        </dependency>
        <!-- Feign 熔断包装（fallbackFactory 生效前提，设计 D3） -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
        </dependency>
```

- [ ] **Step 2: yml 追加开关与超时（设计 D5/D6——架构口径进仓库）**

`spring.cloud` 节点下追加（注意缩进层级在 `cloud:` 之内）：

```yaml
    openfeign:
      circuitbreaker:
        enabled: true
      client:
        config:
          default:
            connect-timeout: 1000
            read-timeout: 5000
```

（**read-timeout 5000 系设计 D5 修订（2026-10-09 实现期实证裁定，主控）**：原 2000 在 bpmn 重启后 Flowable 冷启动首调 >2s 触发 3022 致 BP 六场景连锁 FAIL；停服降级路径走 LB 无健康实例亚秒返回与 read 无关。）

并追加 TimeLimiter 处置（设计 D3 陷阱，(a) 起步）：

```yaml
spring:
  cloud:
    circuitbreaker:
      resilience4j:
        disable-time-limiter: true
```

（与上面同属 `spring.cloud`，合并书写；若启动日志显示 timelimiter 仍生效或属性不识别，改用 `resilience4j.timelimiter.instances.default.timeout-duration: 6s`（须 > read 5s）——实现时验证，二选一落地即可。）

- [ ] **Step 3: 删旧类 + import 批改 + 启动类显式 clients**

删 6 个本地契约类与 `client/SystemUserClient.java`（假 fallback 不存在于 bpmn）。`BpmnApplication`：

```java
@EnableFeignClients(clients = {SystemUserClient.class})
```

（import `com.cloudai.system.api.client.SystemUserClient`；bpmn 消费 bpmn-api 的场景无——自家端点不消费。）

- [ ] **Step 4: 批改消费代码 import（编译器逐个红的顺序处理）**

`ApprovalWorkflowService`：`systemUserClient.listAll()` 返回类型改 `R<List<UserEntry>>`（UserEntry import 改 `com.cloudai.system.api.domain.UserEntry`）——**方法体逻辑零改动**（等价迁移：catch 分支保留为二层兜底）。`InnerApprovalController`/`ApprovalWorkflowService` 中 Approval 系类 import 改 `com.cloudai.bpmn.api.domain.*`。

- [ ] **Step 5: 测试批改 + 构建 + 提交**

`ApprovalWorkflowServiceTest` 等处 mock 的 `com.cloudai.bpmn.client.SystemUserClient` import 改 `com.cloudai.system.api.client.SystemUserClient`（mock 语义不变）。

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-bpmn -am -q`
Expected: BUILD SUCCESS（含 bpmn 既有单测全绿）

```bash
git add cloud-base/cloud-bpmn
git commit -m "refactor: cloud-bpmn 切换 api 模块声明（删本地 SystemUserClient 与六契约类副本）+ circuitbreaker 开关与超时落地"
```

**验收标准：** bpmn 模块构建绿；`grep -r "@FeignClient" cloud-bpmn/src/main/java` 零命中（守护规则 1 预演）。

---

### Task 4: cloud-system 切换到 api 模块

**Files:**
- Delete: `cloud-system/.../client/BpmnApprovalClient.java`、`cloud-system/.../dto/ApprovalCreateRequest.java`、`ApprovalCancelRequest.java`、`ApprovalStatusQueryRequest.java`、`cloud-system/.../vo/ApprovalCreateVo.java`、`ApprovalStatusVo.java`、`cloud-system/.../dto/LoginUserDTO.java`
- Modify（import 批改）: `LeaveWorkflowService.java`、`LeaveManageService.java`（镜像 5 类 → `com.cloudai.bpmn.api.domain` 的 Inner* 原名）、`SysUserLinkageService.java`、`InnerUserController.java`（LoginUserDTO/UserEntry → `com.cloudai.system.api.domain`）
- Modify: `SystemApplication.java`、`cloud-system/pom.xml`、`cloud-system/src/main/resources/application.yml`
- Modify（测试）: 相关 Service 测试的 import 批改

- [ ] **Step 1: pom 加依赖**（同 Task 3 模式：`cloud-bpmn-api` + `spring-cloud-starter-circuitbreaker-resilience4j`；无 system-api——自家契约）+ **Step 2: yml 开关超时**（同 Task 3 逐字）

- [ ] **Step 3: 删旧类 + 批改 import + 启动类**

`SystemApplication`：`@EnableFeignClients(clients = {BpmnApprovalClient.class})`（import `com.cloudai.bpmn.api.client.BpmnApprovalClient`）。

镜像类删除后，`LeaveWorkflowService`/`LeaveManageService` 内类型名变化：`ApprovalCreateRequest`→`ApprovalCreateInnerRequest`、`ApprovalStatusQueryRequest`→`ApprovalStatusQueryInnerRequest`、`ApprovalCancelRequest`→`ApprovalCancelInnerRequest`、`ApprovalCreateVo`→`InnerApprovalCreateVo`、`ApprovalStatusVo`→`InnerApprovalStatusVo`（局部变量/方法签名处类型名同步替换；setter/getter 名不变，方法体逻辑零改动；`catch (Exception e)` 分支**保留**——二层兜底）。

- [ ] **Step 4: 构建验证 + 提交**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am -q`
Expected: BUILD SUCCESS（含 ArchitectureGuardTest 既有 18 规则绿）

```bash
git add cloud-base/cloud-system
git commit -m "refactor: cloud-system 切换 bpmn-api 声明（删镜像五类与本地 client）+ LoginUserDTO 归位 + 开关超时落地"
```

**验收标准：** system 构建绿；`LeaveWorkflowService`/`LeaveManageService` 的 3022 转译方法体 diff 仅类型名与 import。

---

### Task 5: cloud-sso 切换（假 fallback 清理 + TokenService refresh 微调）

**Files:**
- Delete: `cloud-sso/.../client/SystemUserClient.java`、`cloud-sso/.../client/SystemUserClientFallback.java`、`cloud-sso/.../dto/LoginUserDTO.java`
- Modify: `cloud-sso/.../service/TokenService.java`、`SsoApplication.java`、`cloud-sso/pom.xml`、`cloud-sso/src/main/resources/application.yml`
- Modify（测试）: TokenService 相关测试 import 批改

- [ ] **Step 1: pom + yml**（同 Task 3 模式：依赖 = `cloud-system-api` + resilience4j starter；yml 开关超时逐字同）

- [ ] **Step 2: 启动类**：`@EnableFeignClients(clients = {SystemUserClient.class})`（import `com.cloudai.system.api.client.SystemUserClient`）

- [ ] **Step 3: TokenService 批改（login 零改，refresh 微调一处）**

login 方法：仅 import 改（`LoginUserDTO` → `com.cloudai.system.api.domain.LoginUserDTO`、client → api 的）；`catch (FeignException)` 与 `resp.getCode() != 200` 分支**保留原样**（fallback 返回 2002 时经透传分支端到端逐字等价，见设计 D4 表 1a）。

refresh 处（约 105-117 行）改为：

```java
        LoginUserDTO latest;
        try {
            R<LoginUserDTO> resp = userClient.getUserByAccount(old.getAccount());
            if (resp == null || resp.getCode() != 200) {
                // 降级/远端失败统一 2002（设计 D4 表 1b：fallback 2002 走此分支端到端等价；
                // 原 getData()==null→2005 会把服务故障误报为会话失效——边缘路径语义修正记档）
                log.error("cloud-system 调用失败或返回失败，account={}, code={}", old.getAccount(),
                        resp == null ? null : resp.getCode());
                throw new BusinessException(2002, "用户服务不可用，请稍后重试");
            }
            latest = resp.getData();
        } catch (FeignException e) {
            log.error("cloud-system 调用失败，account={}", old.getAccount(), e);
            throw new BusinessException(2002, "用户服务不可用，请稍后重试");
        }
```

- [ ] **Step 4: 构建验证 + 提交**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-sso -am -q`
Expected: BUILD SUCCESS

```bash
git add cloud-base/cloud-sso
git commit -m "refactor: cloud-sso 切换 system-api 声明（清理未生效假 fallback）+ refresh 降级码等价微调"
```

**验收标准：** sso 构建绿；`grep -r "Fallback" cloud-sso/src/main/java` 零命中；login 方法体 diff 仅 import。

---

### Task 6: ArchitectureGuardTest 新增两守护规则

**Files:**
- Modify: `cloud-system/src/test/java/com/cloudai/system/ArchitectureGuardTest.java`

- [ ] **Step 1: 追加两 @Test 与正则常量**（复用既有 `listFiles` helper 与 `assertThat` 风格；跨模块经 `../` 相对路径——cwd=cloud-system，已有 DDL_SQL 先例）

```java
    /** Feign 声明归位：服务模块内不得出现 @FeignClient（合法地 = cloud-*-api 模块；common 程序式特例豁免） */
    @Test
    void feignClients_mustNotBeDeclaredInServiceModules() throws IOException {
        List<String> violations = new ArrayList<>();
        for (String svc : List.of("../cloud-sso", "../cloud-system", "../cloud-bpmn")) {
            Path root = Paths.get(svc, "src", "main", "java");
            for (Path file : listFiles(root, "*.java")) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("@FeignClient")) {
                    violations.add(svc + "/" + root.relativize(file));
                }
            }
        }
        assertThat(violations)
                .as("服务模块不得声明 @FeignClient——统一放提供方 cloud-<svc>-api 模块（CLAUDE.md 服务间 Feign 规范）")
                .isEmpty();
    }

    /** api 模块内 @FeignClient 必须声明 fallbackFactory（等价降级语义的前提） */
    @Test
    void feignClients_inApiModules_mustDeclareFallbackFactory() throws IOException {
        Pattern annotation = Pattern.compile("@FeignClient\\([^)]*\\)", Pattern.DOTALL);
        List<String> violations = new ArrayList<>();
        for (String api : List.of("../cloud-bpmn-api", "../cloud-system-api")) {
            Path root = Paths.get(api, "src", "main", "java");
            for (Path file : listFiles(root, "*.java")) {
                Matcher m = annotation.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    if (!m.group().contains("fallbackFactory")) {
                        violations.add(api + "/" + root.relativize(file));
                    }
                }
            }
        }
        assertThat(violations).as("api 模块 @FeignClient 必须声明 fallbackFactory").isEmpty();
    }
```

- [ ] **Step 2: 运行守护 + 提交**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml test -pl cloud-system -q`
Expected: 全绿（Task 3-5 已删服务模块 @FeignClient；api 模块注解均含 fallbackFactory）

```bash
git add cloud-base/cloud-system/src/test
git commit -m "test: 守护规则扩展（Feign 声明归位 api 模块 + fallbackFactory 强制）"
```

**验收标准：** 两规则绿；人为在服务模块放一个 @FeignClient 验证变红（验后撤销）。

---

### Task 7: 规范落地（CLAUDE.md + /backend-spec 技能）

**Files:**
- Modify: `CLAUDE.md`（模块拓扑 + 关键约定）
- Modify: `.claude/skills/backend-spec/SKILL.md`（新增「步骤 7：服务间 Feign 客户端」章节）

- [ ] **Step 1: CLAUDE.md 模块拓扑图** 在 `cloud-base/` 树的 `cloud-gateway` 行前插入两行：

```
├── cloud-bpmn-api/                # bpmn 服务间契约 jar（Feign 客户端+fallbackFactory+inner 契约模型，自动装配注册降级 bean）
├── cloud-system-api/              # system 服务间契约 jar（同上；UserEntry/LoginUserDTO 归位）
```

- [ ] **Step 2: CLAUDE.md「关键约定」追加条目**（文案 = 设计 §6.1 全文，标题「**服务间 Feign 规范**」；置于「鉴权数据流」条目之后）

- [ ] **Step 3: backend-spec SKILL.md 新增章节**（插在「步骤 6：Controller」之后、「通用约束」之前），内容：

  - 模板 A client（`cloud-<svc>-api`，`com.cloudai.<svc>.api.client`）：@FeignClient(name="cloud-<svc>", contextId="<消费方语义>Client", path="/inner/xxx", fallbackFactory=XxxFallbackFactory.class) + 方法级 @PostMapping/@GetMapping + @PathVariable 显式命名；类名与 API 契约术语一致
  - 模板 B fallbackFactory（`fallback/` 包）：implements FallbackFactory<XxxClient>，create(cause) 返回匿名实现，**每方法 log.error 根因（cause 作最后参）**后 R.fail(码, msg)——返回码按消费方既有终态定制（等价迁移口径；中性降级码演进记移交）
  - 模板 C domain（契约模型）：Serializable + serialVersionUID + 校验注解；禁实体直出（inner 模型 = 契约子集投影）
  - 模板 D 自动装配：@AutoConfiguration + @Bean 注册 fallbackFactory + META-INF/spring/...AutoConfiguration.imports
  - 消费方接入三件套：pom（api jar + spring-cloud-starter-circuitbreaker-resilience4j）/ @EnableFeignClients(clients={...}) 显式 / yml（circuitbreaker.enabled + connect 1000 read 5000 + disable-time-limiter——read 5000 系设计 D5 修订）
  - 陷阱两条：TimeLimiter 默认 1s 截短 read 超时；开关关闭时 fallback 静默不生效（调用方 catch 二层兜底必须保留）
  - 检查清单两条：服务模块无 @FeignClient（守护）；api 模块 @FeignClient 带 fallbackFactory（守护）

（Task 7 全文照设计 §6 抄录，与本方案口径一字不差——规范是硬交付。）

- [ ] **Step 4: 提交**

```bash
git add CLAUDE.md .claude/skills/backend-spec/SKILL.md
git commit -m "docs: 服务间 Feign 规范三层落位（CLAUDE.md 条目 + backend-spec 步骤7 模板）"
```

**验收标准：** 两文件条目与设计 §6 一致；后续新服务接入有可照抄模板。

---

### Task 8: 契约回流（无新契约文件）

**Files:**
- Modify: `docs/superpowers/contracts/2026-10-07-inner-api.md`（§2.1/§2.2/§4）
- Modify: `docs/superpowers/contracts/2026-10-08-approval-platform-api.md`（§4 头部附记）

- [ ] **Step 1: inner-api.md**：
  - §2.1 末尾加：「- 消费客户端：cloud-system-api `SystemUserClient#getUserByAccount`（2026-10-09 统一声明归位；sso 经 fallbackFactory 等价降级 2002）」
  - §2.2 末尾加：「- 消费客户端：cloud-system-api `SystemUserClient#listAll`（bpmn 审批人校验，降级 1002）；translate-remote-starter 程序式 SystemTranslateClient 为记档特例（设计 D8）」
  - §4 表三行消费方列更新为 cloud-system-api 归位口径 + 表下补一行说明
- [ ] **Step 2: approval-platform-api.md §4 标题行下加**：

> **附记（2026-10-09）**：消费端统一为 cloud-bpmn-api `BpmnApprovalClient`（fallbackFactory 中性降级 → system 侧既有转译链不变——端点行为零变化，等价迁移）。

- [ ] **Step 3: 提交**

```bash
git add docs/superpowers/contracts
git commit -m "docs: 契约回流（inner-api/approval-platform 消费客户端归位附记）"
```

**验收标准：** 两契约中消费客户端描述与代码实况一致；无端点/字段/错误码改动。

---

### Task 9: 验证收口（构建 + 起服实证 + e2e）

- [ ] **Step 1: 全量构建**：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -q` → BUILD SUCCESS（全部单测含两新守护规则绿）
- [ ] **Step 2: 起服**（agent 自管，java -jar 顺序 9201→9202→9203→18080；netstat 验证端口监听）
- [ ] **Step 3: 冒烟正常路径**：`curl http://localhost:18080/system/demo/ping` → PONG；登录（ASCII 入参）拿 token → `GET /system/leave/page` 200 正常（fallback 不误伤）
- [ ] **Step 4: 降级实证 A（链路 3+超时）**：`netstat -ano | grep LISTENING | grep :9203` 找 PID `taskkill //F //PID <pid>` 停 bpmn → 带 token `POST http://localhost:18080/system/leave`（ASCII 入参）→ 断言 `code=3022, msg="审批服务不可用"` 且**响应耗时秒级**（connect 超时 1s 生效——对照迁移前默认 60s）；system 日志含「审批服务降级」根因栈
- [ ] **Step 5: 降级实证 B（链路 1）**：停 system（9202）→ `POST http://localhost:18080/sso/login`（ASCII）→ 断言 `code=2002, msg="用户服务不可用，请稍后重试"`
- [ ] **Step 6: 恢复验证**：起回 system/bpmn → 冒烟 Step 3 复跑正常（半开恢复不影响小流量场景——熔断默认参数下不触发打开）
- [ ] **Step 7: e2e 回归**：`cd cloud-e2e && npm run e2e` → BP 全量绿（断言零变化——等价迁移最终裁判）；FAIL 留栈排查
- [ ] **Step 8: kill 全部服务进程交还用户；最终提交（如有零散 diff）**

**验收标准：** 见头部「总验收」四条全满足。

---

## 移交后续阶段的备忘

1. **降级语义演进**：1xxx 新增中性「下游服务不可用」码（如 1010），fallback 统一返回中性码、调用方统一转译——消除 api 模块 fallback 按消费方定制返回码的过渡形态（设计 §8.1）
2. **TimeLimiter 属性口径**：Task 3 落地 `disable-time-limiter` 后，观察启动日志确认生效方式（属性名/版本差异），结论回写 backend-spec 陷阱条
3. **熔断参数精细化**：压测若见误开/需快开，按 `resilience4j.circuitbreaker.instances.<contextId>.*` 调参并评估 actuator 暴露
4. **translate-remote-starter 归拢重评估**：若未来扫描机制演进（如 basePackages 放开），再评估并入 system-api 声明式
5. **新服务接入**：提供 /inner 端点的新服务按 backend-spec 步骤 7 建 cloud-<svc>-api；首个端点与网关屏蔽规则同任务落地（既有铁律）
6. **e2e 预热纪律**（设计 D5 修订同源教训）：停服降级实证后重启的服务，跑 e2e 前先打一次跨服务链路（如发起一笔 e2e 前缀请假单）预热引擎/连接池，避免冷启动首调超时污染回归结果

---

## 给 backend-agent 的任务清单（可粘发）

```
任务：Feign 统一接口层与降级策略（纯后端，等价迁移——端到端 code/msg 逐字不变）
必读：docs/superpowers/specs/2026-10-09-feign-api-fallback-design.md（D1-D8 决策与等价核对表）
     docs/superpowers/plans/2026-10-09-feign-api-fallback.md（Task 1-9 逐步执行，代码已给全）

执行顺序与硬性要求：
1. Task 1→2 建两 api 模块（模块独立构建绿后再动服务模块；Task 2 的 UserEntry 迁移必须原子完成——建新+批改 5 文件 import+删旧+translate 两 pom 加依赖，一次提交）
2. Task 3→4→5 切换三服务：pom（api jar + resilience4j starter，BOM 免版本号）→ yml（circuitbreaker.enabled + connect 1000/read 5000 + disable-time-limiter，read 5000 系设计 D5 修订）→ 启动类显式 clients → 删旧类 import 批改（编译器红的顺序逐个处理）
3. 等价性红线：调用方 Service 的转译/分支逻辑方法体零改动（仅类型名与 import 变）；唯一例外 = TokenService refresh 微调（plan Task 5 Step 3 代码逐字）；调用方 catch(FeignException) 一律保留（二层兜底）
4. Task 6 守护两规则照抄 plan；Task 7 规范文案照设计 §6 逐字（CLAUDE.md 条目 + backend-spec 步骤7）
5. Task 9 验证：全量构建绿 → 起服 9201→9202→9203→18080 → 冒烟 → 停 bpmn 实证 3022 秒级返回（超时生效）→ 停 system 实证 2002 → 起回全链路正常 → cloud-e2e BP 全量绿 → kill 全部进程
6. 完成后核对：服务模块 @FeignClient 零命中；全局 UserEntry 唯一；契约两文件回流完成；mvn 绝对路径 D:/software/apache-maven-3.8.4/bin/mvn
```
