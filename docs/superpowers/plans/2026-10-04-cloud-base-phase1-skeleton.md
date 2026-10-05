# cloud-base 阶段 1：工程骨架 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 cloud-base 聚合工程骨架：父 POM + 4 个公共模块 + 4 个可启动微服务 + Nacos 注册/配置接入 + 网关路由 + 统一返回/异常。

**Architecture:** Maven 多模块聚合工程（cloud-base 父 POM 统一版本管理）。公共能力沉淀在 cloud-common 的 4 个子模块（core/security/mybatis/redis），用 Spring Boot 3 自动装配（`AutoConfiguration.imports`）引入即生效。网关经 Nacos 服务发现以 `lb://` 转发到三个业务服务。

**Tech Stack:** JDK 17、Spring Boot 3.3.4、Spring Cloud 2023.0.3、Spring Cloud Alibaba 2023.0.3.3（Nacos）、MyBatis-Plus 3.5.7、jjwt 0.12.6、Hutool 5.8.32、Lombok、SpringDoc 2.6.0。

**设计文档:** `docs/superpowers/specs/2026-10-04-cloud-base-backend-design.md`（本计划覆盖其"阶段 1"；阶段 2/3/4 在本阶段执行完成后另立计划）

---

## 前置条件（执行前核对）

1. **JDK 17**：`java -version` 输出 17.x。
2. **Maven 3.8+**：`mvn -v` 正常。
3. **Nacos 已运行**（用户已有环境）：默认客户端端口 `8848`。执行前**询问用户实际 Nacos 地址**；若非本机默认，启动服务时设置环境变量 `NACOS_ADDR=<实际地址>`。
4. **端口占用检查**：本工程占用 18080（gateway）、9201（sso）、9202（system）、9203（bpmn）。（2026-10-05 执行时实测：本机 8080 已被 RocketMQ Dashboard 容器占用，网关端口由 8080 调整为 18080。）
5. 工作目录：仓库根 `D:/source/cloud-ai`（Git Bash 路径 `/d/source/cloud-ai`）。所有 Maven 命令统一用 `-f cloud-base/pom.xml`。
6. 提交信息末尾附 `Co-Authored-By: Claude Code <noreply@anthropic.com>`（计划中的提交命令已含）。

## 文件结构总览（本计划产出）

```
cloud-base/
├── pom.xml                                          # Task 1 父POM
├── README.md                                        # Task 13
├── cloud-common/
│   ├── pom.xml                                      # Task 1
│   ├── cloud-common-core/
│   │   ├── pom.xml                                  # Task 1
│   │   └── src/main/java/com/cloudai/common/core/
│   │       ├── domain/R.java                        # Task 2
│   │       ├── domain/PageQuery.java                # Task 3
│   │       ├── domain/PageResult.java               # Task 3
│   │       ├── exception/ErrorCode.java             # Task 2
│   │       ├── exception/BusinessException.java     # Task 4
│   │       ├── exception/GlobalExceptionHandler.java# Task 4
│   │       └── config/CommonJacksonAutoConfiguration.java  # Task 5
│   │   └── src/test/java/com/cloudai/common/core/...（各任务测试）
│   │   └── src/main/resources/META-INF/spring/
│   │       └── org.springframework.boot.autoconfigure.AutoConfiguration.imports  # Task 4/5
│   ├── cloud-common-security/
│   │   ├── pom.xml                                  # Task 1
│   │   └── src/main/java/com/cloudai/common/security/util/JwtUtil.java  # Task 6
│   ├── cloud-common-mybatis/
│   │   ├── pom.xml                                  # Task 1
│   │   └── src/main/java/com/cloudai/common/mybatis/
│   │       ├── domain/BaseEntity.java               # Task 7
│   │       ├── handler/AuditMetaObjectHandler.java  # Task 7
│   │       └── config/CommonMybatisAutoConfiguration.java  # Task 7
│   │   └── src/main/resources/META-INF/spring/...imports    # Task 7
│   └── cloud-common-redis/
│       ├── pom.xml                                  # Task 1
│       └── src/main/java/com/cloudai/common/redis/
│           ├── util/RedisUtil.java                  # Task 8
│           └── config/CommonRedisAutoConfiguration.java    # Task 8
│       └── src/main/resources/META-INF/spring/...imports    # Task 8
├── cloud-gateway/
│   ├── pom.xml                                      # Task 1
│   └── src/main/java/com/cloudai/gateway/GatewayApplication.java  # Task 12
│   └── src/main/resources/application.yml           # Task 12
├── cloud-sso/
│   ├── pom.xml                                      # Task 1
│   └── src/main/java/com/cloudai/sso/SsoApplication.java          # Task 9
│   └── src/main/java/com/cloudai/sso/controller/DemoController.java # Task 9
│   └── src/main/resources/application.yml           # Task 9
├── cloud-system/
│   ├── pom.xml                                      # Task 1
│   └── src/main/java/com/cloudai/system/SystemApplication.java    # Task 10
│   └── src/main/java/com/cloudai/system/controller/DemoController.java # Task 10
│   └── src/main/resources/application.yml           # Task 10
└── cloud-bpmn/
    ├── pom.xml                                      # Task 1
    └── src/main/java/com/cloudai/bpmn/BpmnApplication.java        # Task 11
    └── src/main/java/com/cloudai/bpmn/controller/DemoController.java # Task 11
    └── src/main/resources/application.yml           # Task 11
```

---

## Task 1: 聚合工程骨架（父 POM + 全部模块 POM）

**Files:**
- Create: `.gitignore`（仓库根）
- Create: `cloud-base/pom.xml`
- Create: `cloud-base/cloud-common/pom.xml`
- Create: `cloud-base/cloud-common/cloud-common-core/pom.xml`
- Create: `cloud-base/cloud-common/cloud-common-security/pom.xml`
- Create: `cloud-base/cloud-common/cloud-common-mybatis/pom.xml`
- Create: `cloud-base/cloud-common/cloud-common-redis/pom.xml`
- Create: `cloud-base/cloud-gateway/pom.xml`
- Create: `cloud-base/cloud-sso/pom.xml`
- Create: `cloud-base/cloud-system/pom.xml`
- Create: `cloud-base/cloud-bpmn/pom.xml`

- [ ] **Step 1: 确认构建环境**

Run: `java -version && mvn -v`
Expected: java 17.x；Maven 3.8+。不满足则停止并报告用户。

- [ ] **Step 2: 写仓库根 `.gitignore`**

```gitignore
target/
*.class
*.jar
!.mvn/wrapper/*.jar

.idea/
*.iml
.vscode/
.settings/
.classpath
.project

logs/
*.log
.DS_Store
```

- [ ] **Step 3: 写父 POM `cloud-base/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.cloudai</groupId>
    <artifactId>cloud-base</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>cloud-base</name>
    <description>企业应用基座平台 - 后端聚合工程</description>

    <modules>
        <module>cloud-common</module>
        <module>cloud-gateway</module>
        <module>cloud-sso</module>
        <module>cloud-system</module>
        <module>cloud-bpmn</module>
    </modules>

    <properties>
        <java.version>17</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <project.reporting.outputEncoding>UTF-8</project.reporting.outputEncoding>
        <spring-boot.version>3.3.4</spring-boot.version>
        <spring-cloud.version>2023.0.3</spring-cloud.version>
        <spring-cloud-alibaba.version>2023.0.3.3</spring-cloud-alibaba.version>
        <mybatis-plus.version>3.5.7</mybatis-plus.version>
        <jjwt.version>0.12.6</jjwt.version>
        <springdoc.version>2.6.0</springdoc.version>
        <hutool.version>5.8.32</hutool.version>
    </properties>

    <!-- 所有子模块继承：Lombok 与测试依赖 -->
    <dependencies>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>${spring-cloud.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>${spring-cloud-alibaba.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <!-- 内部模块 -->
            <dependency>
                <groupId>com.cloudai</groupId>
                <artifactId>cloud-common-core</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.cloudai</groupId>
                <artifactId>cloud-common-security</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.cloudai</groupId>
                <artifactId>cloud-common-mybatis</artifactId>
                <version>${project.version}</version>
            </dependency>
            <dependency>
                <groupId>com.cloudai</groupId>
                <artifactId>cloud-common-redis</artifactId>
                <version>${project.version}</version>
            </dependency>
            <!-- 三方 -->
            <dependency>
                <groupId>com.baomidou</groupId>
                <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
                <version>${mybatis-plus.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-api</artifactId>
                <version>${jjwt.version}</version>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-impl</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>io.jsonwebtoken</groupId>
                <artifactId>jjwt-jackson</artifactId>
                <version>${jjwt.version}</version>
                <scope>runtime</scope>
            </dependency>
            <dependency>
                <groupId>org.springdoc</groupId>
                <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
                <version>${springdoc.version}</version>
            </dependency>
            <dependency>
                <groupId>cn.hutool</groupId>
                <artifactId>hutool-all</artifactId>
                <version>${hutool.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <build>
        <pluginManagement>
            <plugins>
                <plugin>
                    <groupId>org.springframework.boot</groupId>
                    <artifactId>spring-boot-maven-plugin</artifactId>
                    <version>${spring-boot.version}</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId>
                    <version>3.2.5</version>
                </plugin>
                <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <version>3.13.0</version>
                    <configuration>
                        <release>${java.version}</release>
                    </configuration>
                </plugin>
            </plugins>
        </pluginManagement>
    </build>
</project>
```

- [ ] **Step 4: 写 `cloud-base/cloud-common/pom.xml`**

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

    <artifactId>cloud-common</artifactId>
    <packaging>pom</packaging>
    <name>cloud-common</name>

    <modules>
        <module>cloud-common-core</module>
        <module>cloud-common-security</module>
        <module>cloud-common-mybatis</module>
        <module>cloud-common-redis</module>
    </modules>
</project>
```

- [ ] **Step 5: 写 `cloud-base/cloud-common/cloud-common-core/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.cloudai</groupId>
        <artifactId>cloud-common</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>cloud-common-core</artifactId>
    <name>cloud-common-core</name>
    <description>统一返回体、异常、错误码、Jackson 配置</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>
        <dependency>
            <groupId>cn.hutool</groupId>
            <artifactId>hutool-all</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 6: 写 `cloud-base/cloud-common/cloud-common-security/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.cloudai</groupId>
        <artifactId>cloud-common</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>cloud-common-security</artifactId>
    <name>cloud-common-security</name>
    <description>JWT 工具与资源端安全配置</description>

    <dependencies>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 7: 写 `cloud-base/cloud-common/cloud-common-mybatis/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.cloudai</groupId>
        <artifactId>cloud-common</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>cloud-common-mybatis</artifactId>
    <name>cloud-common-mybatis</name>
    <description>MyBatis-Plus 配置、审计填充、基础实体</description>

    <dependencies>
        <dependency>
            <groupId>com.baomidou</groupId>
            <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 8: 写 `cloud-base/cloud-common/cloud-common-redis/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>com.cloudai</groupId>
        <artifactId>cloud-common</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>

    <artifactId>cloud-common-redis</artifactId>
    <name>cloud-common-redis</name>
    <description>Redis 配置与工具封装</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-data-redis</artifactId>
        </dependency>
    </dependencies>
</project>
```

- [ ] **Step 9: 写 `cloud-base/cloud-sso/pom.xml`**

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

    <artifactId>cloud-sso</artifactId>
    <name>cloud-sso</name>
    <description>认证中心</description>

    <dependencies>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 10: 写 `cloud-base/cloud-system/pom.xml`**

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

    <artifactId>cloud-system</artifactId>
    <name>cloud-system</name>
    <description>系统管理服务</description>

    <dependencies>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 11: 写 `cloud-base/cloud-bpmn/pom.xml`**

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

    <artifactId>cloud-bpmn</artifactId>
    <name>cloud-bpmn</name>
    <description>工作流服务</description>

    <dependencies>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 12: 写 `cloud-base/cloud-gateway/pom.xml`**

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

    <artifactId>cloud-gateway</artifactId>
    <name>cloud-gateway</name>
    <description>API 网关</description>

    <dependencies>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-gateway</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
        </dependency>
        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-actuator</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 13: 全量构建验证**

Run: `mvn -f cloud-base/pom.xml clean install`
Expected: `BUILD SUCCESS`，10 个模块全部编译安装成功（common 4 + common 父 + gateway + sso + system + bpmn + 父）。

- [ ] **Step 14: Commit**

```bash
git add .gitignore cloud-base/
git commit -m "feat(cloud-base): 初始化 Maven 聚合工程骨架（父POM+4公共模块+4服务模块）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 2: common-core 错误码 ErrorCode 与统一返回体 R（TDD）

**Files:**
- Create: `cloud-base/cloud-common/cloud-common-core/src/main/java/com/cloudai/common/core/exception/ErrorCode.java`
- Create: `cloud-base/cloud-common/cloud-common-core/src/main/java/com/cloudai/common/core/domain/R.java`
- Test: `cloud-base/cloud-common/cloud-common-core/src/test/java/com/cloudai/common/core/domain/RTest.java`

- [ ] **Step 1: 写失败测试 `RTest.java`**

```java
package com.cloudai.common.core.domain;

import com.cloudai.common.core.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RTest {

    @Test
    void ok_shouldReturn200WithDefaultMsg() {
        R<Void> r = R.ok();
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getMsg()).isEqualTo("操作成功");
        assertThat(r.getData()).isNull();
    }

    @Test
    void ok_shouldCarryData() {
        R<String> r = R.ok("pong");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).isEqualTo("pong");
    }

    @Test
    void fail_withCodeAndMsg() {
        R<Void> r = R.fail(500, "系统异常");
        assertThat(r.getCode()).isEqualTo(500);
        assertThat(r.getMsg()).isEqualTo("系统异常");
    }

    @Test
    void fail_withErrorCodeEnum() {
        R<Void> r = R.fail(ErrorCode.PARAM_ERROR);
        assertThat(r.getCode()).isEqualTo(1001);
        assertThat(r.getMsg()).isEqualTo("参数校验失败");
    }

    @Test
    void fail_withOnlyMsg_defaultsToBusinessError() {
        R<Void> r = R.fail("用户名或密码错误");
        assertThat(r.getCode()).isEqualTo(1002);
        assertThat(r.getMsg()).isEqualTo("用户名或密码错误");
    }
}
```

- [ ] **Step 2: 运行测试确认失败（编译错误）**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core`
Expected: COMPILATION ERROR（R/ErrorCode 不存在）。

- [ ] **Step 3: 实现 `ErrorCode.java`**

```java
package com.cloudai.common.core.exception;

import lombok.Getter;

/**
 * 统一错误码：1xxx 通用 / 2xxx 认证 / 3xxx system / 4xxx bpmn（后续阶段按需追加）
 */
@Getter
public enum ErrorCode {

    SUCCESS(200, "操作成功"),
    SYSTEM_ERROR(500, "系统异常，请稍后重试"),
    UNAUTHORIZED(401, "认证失败或未登录"),
    FORBIDDEN(403, "无操作权限"),
    PARAM_ERROR(1001, "参数校验失败"),
    BUSINESS_ERROR(1002, "业务处理失败");

    private final int code;
    private final String msg;

    ErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
```

- [ ] **Step 4: 实现 `R.java`**

```java
package com.cloudai.common.core.domain;

import com.cloudai.common.core.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一返回体
 */
@Data
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    private int code;
    private String msg;
    private T data;

    public static <T> R<T> ok() {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), null);
    }

    public static <T> R<T> ok(T data) {
        return build(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMsg(), data);
    }

    public static <T> R<T> fail(String msg) {
        return build(ErrorCode.BUSINESS_ERROR.getCode(), msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return build(code, msg, null);
    }

    public static <T> R<T> fail(ErrorCode errorCode) {
        return build(errorCode.getCode(), errorCode.getMsg(), null);
    }

    public static <T> R<T> fail(ErrorCode errorCode, String msg) {
        return build(errorCode.getCode(), msg, null);
    }

    private static <T> R<T> build(int code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core`
Expected: `Tests run: 5, Failures: 0, Errors: 0`，BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add cloud-base/cloud-common/cloud-common-core/
git commit -m "feat(common-core): 统一错误码 ErrorCode 与返回体 R

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 3: common-core 分页对象 PageQuery / PageResult（TDD）

**Files:**
- Create: `cloud-base/cloud-common/cloud-common-core/src/main/java/com/cloudai/common/core/domain/PageQuery.java`
- Create: `cloud-base/cloud-common/cloud-common-core/src/main/java/com/cloudai/common/core/domain/PageResult.java`
- Test: `cloud-base/cloud-common/cloud-common-core/src/test/java/com/cloudai/common/core/domain/PageQueryTest.java`
- Test: `cloud-base/cloud-common/cloud-common-core/src/test/java/com/cloudai/common/core/domain/PageResultTest.java`

- [ ] **Step 1: 写失败测试 `PageQueryTest.java`**

```java
package com.cloudai.common.core.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PageQueryTest {

    @Test
    void defaults_arePage1Size10() {
        PageQuery q = new PageQuery();
        assertThat(q.getPageNum()).isEqualTo(1);
        assertThat(q.getPageSize()).isEqualTo(10);
    }
}
```

- [ ] **Step 2: 写失败测试 `PageResultTest.java`**

```java
package com.cloudai.common.core.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PageResultTest {

    @Test
    void of_carriesTotalAndRows() {
        PageResult<String> r = PageResult.of(2, List.of("a", "b"));
        assertThat(r.getTotal()).isEqualTo(2);
        assertThat(r.getRows()).containsExactly("a", "b");
        assertThat(r.isEmpty()).isFalse();
    }

    @Test
    void isEmpty_whenRowsNull() {
        PageResult<String> r = PageResult.of(0, null);
        assertThat(r.isEmpty()).isTrue();
    }

    @Test
    void isEmpty_whenRowsEmpty() {
        PageResult<String> r = PageResult.of(0, List.of());
        assertThat(r.isEmpty()).isTrue();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core`
Expected: COMPILATION ERROR（PageQuery/PageResult 不存在）。

- [ ] **Step 4: 实现 `PageQuery.java`**

```java
package com.cloudai.common.core.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 通用分页请求参数
 */
@Data
public class PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;
}
```

- [ ] **Step 5: 实现 `PageResult.java`**

```java
package com.cloudai.common.core.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用分页返回
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private long total;

    /** 当前页数据 */
    private List<T> rows;

    public static <T> PageResult<T> of(long total, List<T> rows) {
        PageResult<T> r = new PageResult<>();
        r.setTotal(total);
        r.setRows(rows == null ? new ArrayList<>() : rows);
        return r;
    }

    public boolean isEmpty() {
        return rows == null || rows.isEmpty();
    }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core`
Expected: `Tests run: 9`（含 Task 2 的 5 个 + 本任务 4 个：PageQuery 1 + PageResult 3），全部 PASS，BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add cloud-base/cloud-common/cloud-common-core/
git commit -m "feat(common-core): 通用分页对象 PageQuery/PageResult

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 4: common-core 业务异常与全局异常处理（TDD）

**Files:**
- Create: `cloud-base/cloud-common/cloud-common-core/src/main/java/com/cloudai/common/core/exception/BusinessException.java`
- Create: `cloud-base/cloud-common/cloud-common-core/src/main/java/com/cloudai/common/core/exception/GlobalExceptionHandler.java`
- Create: `cloud-base/cloud-common/cloud-common-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `cloud-base/cloud-common/cloud-common-core/src/test/java/com/cloudai/common/core/exception/GlobalExceptionHandlerTest.java`

- [ ] **Step 1: 写失败测试 `GlobalExceptionHandlerTest.java`**

```java
package com.cloudai.common.core.exception;

import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessException_mapsCodeAndMessage() {
        BusinessException e = new BusinessException("用户名或密码错误");
        R<Void> r = handler.handleBusinessException(e);
        assertThat(r.getCode()).isEqualTo(1002);
        assertThat(r.getMsg()).isEqualTo("用户名或密码错误");
    }

    @Test
    void businessException_withCustomCode() {
        BusinessException e = new BusinessException(3001, "用户不存在");
        R<Void> r = handler.handleBusinessException(e);
        assertThat(r.getCode()).isEqualTo(3001);
        assertThat(r.getMsg()).isEqualTo("用户不存在");
    }

    @Test
    void unknownException_mapsToSystemError() {
        R<Void> r = handler.handleException(new RuntimeException("boom"));
        assertThat(r.getCode()).isEqualTo(500);
        assertThat(r.getMsg()).isEqualTo("系统异常，请稍后重试");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core`
Expected: COMPILATION ERROR（BusinessException/GlobalExceptionHandler 不存在）。

- [ ] **Step 3: 实现 `BusinessException.java`**

```java
package com.cloudai.common.core.exception;

import lombok.Getter;

/**
 * 业务异常：携带错误码，由全局异常处理器统一转为 R 返回
 */
@Getter
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(String message) {
        this(ErrorCode.BUSINESS_ERROR.getCode(), message);
    }

    public BusinessException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getMsg());
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }
}
```

- [ ] **Step 4: 实现 `GlobalExceptionHandler.java`**

```java
package com.cloudai.common.core.exception;

import com.cloudai.common.core.domain.R;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 全局异常处理：自动装配，引入 common-core 的 web 服务即生效（WebMVC 与 WebFlux 均适用）
 */
@AutoConfiguration
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：原样透出错误码与消息 */
    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException e) {
        return R.fail(e.getCode(), e.getMessage());
    }

    /** 参数校验失败（@Valid） */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        StringBuilder msg = new StringBuilder();
        e.getBindingResult().getFieldErrors()
                .forEach(fe -> msg.append(fe.getField()).append(" ").append(fe.getDefaultMessage()).append("; "));
        return R.fail(ErrorCode.PARAM_ERROR, msg.toString());
    }

    /** 兜底：未知异常统一 500，不向外暴露堆栈细节 */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        return R.fail(ErrorCode.SYSTEM_ERROR);
    }
}
```

- [ ] **Step 5: 创建自动装配注册文件**

路径：`cloud-base/cloud-common/cloud-common-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.cloudai.common.core.exception.GlobalExceptionHandler
```

（Task 5 会在此文件追加第二行。）

- [ ] **Step 6: 运行测试确认通过**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core`
Expected: `Tests run: 12`（含此前 9 个 + 本任务 3 个），全部 PASS，BUILD SUCCESS。

- [ ] **Step 7: Commit**

```bash
git add cloud-base/cloud-common/cloud-common-core/
git commit -m "feat(common-core): 业务异常与全局异常处理器自动装配

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 5: common-core Jackson 统一格式配置（TDD）

**Files:**
- Create: `cloud-base/cloud-common/cloud-common-core/src/main/java/com/cloudai/common/core/config/CommonJacksonAutoConfiguration.java`
- Modify: `cloud-base/cloud-common/cloud-common-core/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `cloud-base/cloud-common/cloud-common-core/src/test/java/com/cloudai/common/core/config/CommonJacksonAutoConfigurationTest.java`

- [ ] **Step 1: 写失败测试 `CommonJacksonAutoConfigurationTest.java`**

```java
package com.cloudai.common.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CommonJacksonAutoConfigurationTest {

    private ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = new Jackson2ObjectMapperBuilder();
        new CommonJacksonAutoConfiguration().jacksonCustomizer().customize(builder);
        return builder.build();
    }

    @Data
    static class Dto {
        private LocalDateTime time;
        private Long id;
    }

    @Test
    void serialize_dateFormatAndLongAsString() throws Exception {
        Dto dto = new Dto();
        dto.setTime(LocalDateTime.of(2026, 10, 4, 12, 0, 0));
        dto.setId(123456789012345L);
        String json = mapper().writeValueAsString(dto);
        assertThat(json).contains("\"2026-10-04 12:00:00\"");
        assertThat(json).contains("\"123456789012345\"");
    }

    @Test
    void deserialize_dateFormat() throws Exception {
        Dto dto = mapper().readValue("{\"time\":\"2026-10-04 12:00:00\"}", Dto.class);
        assertThat(dto.getTime()).isEqualTo(LocalDateTime.of(2026, 10, 4, 12, 0, 0));
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core`
Expected: COMPILATION ERROR（CommonJacksonAutoConfiguration 不存在）。

- [ ] **Step 3: 实现 `CommonJacksonAutoConfiguration.java`**

```java
package com.cloudai.common.core.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilderCustomizer;

import java.time.format.DateTimeFormatter;
import java.util.TimeZone;

/**
 * Jackson 统一格式：GMT+8、yyyy-MM-dd HH:mm:ss、Long 转 String（防前端 JS 精度丢失）
 */
@AutoConfiguration
public class CommonJacksonAutoConfiguration {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
            builder.simpleDateFormat(DATE_TIME_PATTERN);
            builder.serializers(new LocalDateTimeSerializer(formatter));
            builder.deserializers(new LocalDateTimeDeserializer(formatter));
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.timeZone(TimeZone.getTimeZone("GMT+8"));
        };
    }
}
```

说明：`LocalDateTimeSerializer` 所在的 `jackson-datatype-jsr310` 已在 Task 1 的 `cloud-common-core/pom.xml` 中声明，无需额外处理。

- [ ] **Step 4: 追加自动装配注册**

`AutoConfiguration.imports` 文件内容更新为两行：

```
com.cloudai.common.core.exception.GlobalExceptionHandler
com.cloudai.common.core.config.CommonJacksonAutoConfiguration
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-core`
Expected: `Tests run: 14`（含此前 12 个 + 本任务 2 个），全部 PASS，BUILD SUCCESS。

- [ ] **Step 6: Commit**

```bash
git add cloud-base/cloud-common/cloud-common-core/
git commit -m "feat(common-core): Jackson 统一格式配置（GMT+8/日期格式/Long转String）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 6: common-security JWT 工具 JwtUtil（TDD）

**Files:**
- Create: `cloud-base/cloud-common/cloud-common-security/src/main/java/com/cloudai/common/security/util/JwtUtil.java`
- Test: `cloud-base/cloud-common/cloud-common-security/src/test/java/com/cloudai/common/security/util/JwtUtilTest.java`

- [ ] **Step 1: 写失败测试 `JwtUtilTest.java`**

```java
package com.cloudai.common.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    /** HS512 要求密钥 >= 512bit（64 字节） */
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Test
    void createAndParse_roundtrip() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "token-uuid-1", 7200);
        Claims claims = JwtUtil.parseToken(SECRET, token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("account", String.class)).isEqualTo("admin");
        assertThat(claims.getId()).isEqualTo("token-uuid-1");
    }

    @Test
    void parseToken_expiredThrows() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "token-uuid-2", -10);
        assertThatThrownBy(() -> JwtUtil.parseToken(SECRET, token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void parseToken_wrongSecretThrows() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "token-uuid-3", 7200);
        assertThatThrownBy(() -> JwtUtil.parseToken(OTHER_SECRET, token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void parseToken_tamperedTokenThrows() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "token-uuid-4", 7200);
        String tampered = token.substring(0, token.length() - 3) + "aaa";
        assertThatThrownBy(() -> JwtUtil.parseToken(SECRET, tampered))
                .isInstanceOf(JwtException.class);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-security`
Expected: COMPILATION ERROR（JwtUtil 不存在）。

- [ ] **Step 3: 实现 `JwtUtil.java`**

```java
package com.cloudai.common.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 工具（HS512）。密钥由调用方传入（来自 Nacos 配置），便于阶段 3 升级 RS256 时只改本类。
 */
public final class JwtUtil {

    private JwtUtil() {
    }

    /**
     * 签发 access_token
     *
     * @param secret     HS512 密钥（>= 64 字节）
     * @param userId     用户 ID（写入 subject）
     * @param account    账号（写入 account claim）
     * @param tokenId    会话唯一标识（写入 jti，用于 Redis 在线状态）
     * @param ttlSeconds 有效期（秒）
     */
    public static String createToken(String secret, Long userId, String account, String tokenId, long ttlSeconds) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .id(tokenId)
                .subject(String.valueOf(userId))
                .claim("account", account)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * 解析并验签。签名不对/过期/篡改分别抛 SignatureException / ExpiredJwtException / JwtException。
     */
    public static Claims parseToken(String secret, String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-security`
Expected: `Tests run: 4, Failures: 0, Errors: 0`，BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add cloud-base/cloud-common/cloud-common-security/
git commit -m "feat(common-security): JWT 签发与验签工具（HS512）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 7: common-mybatis 基础实体、审计填充与分页插件（TDD）

**Files:**
- Create: `cloud-base/cloud-common/cloud-common-mybatis/src/main/java/com/cloudai/common/mybatis/domain/BaseEntity.java`
- Create: `cloud-base/cloud-common/cloud-common-mybatis/src/main/java/com/cloudai/common/mybatis/handler/AuditMetaObjectHandler.java`
- Create: `cloud-base/cloud-common/cloud-common-mybatis/src/main/java/com/cloudai/common/mybatis/config/CommonMybatisAutoConfiguration.java`
- Create: `cloud-base/cloud-common/cloud-common-mybatis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `cloud-base/cloud-common/cloud-common-mybatis/src/test/java/com/cloudai/common/mybatis/handler/AuditMetaObjectHandlerTest.java`

- [ ] **Step 1: 写失败测试 `AuditMetaObjectHandlerTest.java`**

```java
package com.cloudai.common.mybatis.handler;

import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AuditMetaObjectHandlerTest {

    private final AuditMetaObjectHandler handler = new AuditMetaObjectHandler();

    @Data
    static class OrderEntity extends BaseEntity {
        private String orderNo;
    }

    @Data
    static class PlainEntity {
        private String name;
    }

    @Test
    void insertFill_fillsCreateTimeAndUpdateTime() {
        OrderEntity entity = new OrderEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        handler.insertFill(metaObject);
        assertThat(entity.getCreateTime()).isNotNull();
        assertThat(entity.getUpdateTime()).isNotNull();
    }

    @Test
    void updateFill_fillsOnlyUpdateTime() {
        OrderEntity entity = new OrderEntity();
        entity.setCreateTime(LocalDateTime.of(2026, 1, 1, 0, 0));
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        handler.updateFill(metaObject);
        assertThat(entity.getUpdateTime()).isNotNull();
        assertThat(entity.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @Test
    void fill_skipsEntitiesWithoutAuditFields() {
        PlainEntity entity = new PlainEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        assertThatCode(() -> handler.insertFill(metaObject)).doesNotThrowAnyException();
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-mybatis`
Expected: COMPILATION ERROR（BaseEntity/AuditMetaObjectHandler 不存在）。

- [ ] **Step 3: 实现 `BaseEntity.java`**

```java
package com.cloudai.common.mybatis.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类：审计时间字段自动填充 + 逻辑删除。
 * createBy/updateBy 在阶段 3 接入登录上下文后填充。
 */
@Data
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 创建时间（插入填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（插入和更新都填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 逻辑删除：0 未删除 1 已删除 */
    @TableLogic
    private Integer deleted;
}
```

- [ ] **Step 4: 实现 `AuditMetaObjectHandler.java`**

```java
package com.cloudai.common.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：无对应属性的实体自动跳过
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        if (metaObject.hasSetter("createTime")) {
            setFieldValByName("createTime", now, metaObject);
        }
        if (metaObject.hasSetter("updateTime")) {
            setFieldValByName("updateTime", now, metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updateTime")) {
            setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        }
    }
}
```

- [ ] **Step 5: 实现 `CommonMybatisAutoConfiguration.java`**

```java
package com.cloudai.common.mybatis.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.cloudai.common.mybatis.handler.AuditMetaObjectHandler;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * MyBatis-Plus 通用配置：MySQL 分页插件 + 审计填充
 */
@AutoConfiguration
public class CommonMybatisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MybatisPlusInterceptor.class)
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限，服务端兜底防止大页拉取
        pagination.setMaxLimit(200L);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    @Bean
    @ConditionalOnMissingBean(MetaObjectHandler.class)
    public AuditMetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }
}
```

- [ ] **Step 6: 创建自动装配注册文件**

路径：`cloud-base/cloud-common/cloud-common-mybatis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.cloudai.common.mybatis.config.CommonMybatisAutoConfiguration
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-mybatis`
Expected: `Tests run: 3, Failures: 0, Errors: 0`，BUILD SUCCESS。

- [ ] **Step 8: Commit**

```bash
git add cloud-base/cloud-common/cloud-common-mybatis/
git commit -m "feat(common-mybatis): 基础实体/审计字段填充/分页插件自动装配

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 8: common-redis 配置与工具封装

说明：RedisTemplate 序列化配置与 RedisUtil 为纯装配/薄封装，真实行为依赖运行中的 Redis，**本任务只做编译验证**，行为验证在阶段 3（sso 存取 token 状态）完成。

**Files:**
- Create: `cloud-base/cloud-common/cloud-common-redis/src/main/java/com/cloudai/common/redis/config/CommonRedisAutoConfiguration.java`
- Create: `cloud-base/cloud-common/cloud-common-redis/src/main/java/com/cloudai/common/redis/util/RedisUtil.java`
- Create: `cloud-base/cloud-common/cloud-common-redis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

- [ ] **Step 1: 实现 `RedisUtil.java`**

```java
package com.cloudai.common.redis.util;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.concurrent.TimeUnit;

/**
 * Redis 薄封装：只封装本项目用到的方法，不做大而全
 */
@RequiredArgsConstructor
public class RedisUtil {

    private final RedisTemplate<String, Object> redisTemplate;

    public void set(String key, Object value) {
        redisTemplate.opsForValue().set(key, value);
    }

    public void set(String key, Object value, long timeout, TimeUnit unit) {
        redisTemplate.opsForValue().set(key, value, timeout, unit);
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) redisTemplate.opsForValue().get(key);
    }

    public Boolean delete(String key) {
        return redisTemplate.delete(key);
    }

    public Boolean expire(String key, long timeout, TimeUnit unit) {
        return redisTemplate.expire(key, timeout, unit);
    }

    public Boolean hasKey(String key) {
        return redisTemplate.hasKey(key);
    }
}
```

- [ ] **Step 2: 实现 `CommonRedisAutoConfiguration.java`**

```java
package com.cloudai.common.redis.config;

import com.cloudai.common.redis.util.RedisUtil;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * Redis 模板配置：key 用 String 序列化，value 用 JSON 序列化
 */
@AutoConfiguration
public class CommonRedisAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "redisTemplate")
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer keySerializer = new StringRedisSerializer();
        GenericJackson2JsonRedisSerializer valueSerializer = new GenericJackson2JsonRedisSerializer();
        template.setKeySerializer(keySerializer);
        template.setHashKeySerializer(keySerializer);
        template.setValueSerializer(valueSerializer);
        template.setHashValueSerializer(valueSerializer);
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    @ConditionalOnMissingBean
    public RedisUtil redisUtil(RedisTemplate<String, Object> redisTemplate) {
        return new RedisUtil(redisTemplate);
    }
}
```

- [ ] **Step 3: 创建自动装配注册文件**

路径：`cloud-base/cloud-common/cloud-common-redis/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.cloudai.common.redis.config.CommonRedisAutoConfiguration
```

- [ ] **Step 4: 编译验证**

Run: `mvn -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-redis`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add cloud-base/cloud-common/cloud-common-redis/
git commit -m "feat(common-redis): RedisTemplate 序列化配置与 RedisUtil 封装

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 9: cloud-sso 认证服务可启动（Nacos 注册验证）

**Files:**
- Create: `cloud-base/cloud-sso/src/main/java/com/cloudai/sso/SsoApplication.java`
- Create: `cloud-base/cloud-sso/src/main/java/com/cloudai/sso/controller/DemoController.java`
- Create: `cloud-base/cloud-sso/src/main/resources/application.yml`

- [ ] **Step 1: 实现启动类 `SsoApplication.java`**

```java
package com.cloudai.sso;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SsoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SsoApplication.class, args);
    }
}
```

- [ ] **Step 2: 实现 `DemoController.java`**

```java
package com.cloudai.sso.controller;

import com.cloudai.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 1 连通性验证接口（阶段 3 被 auth 相关接口替代后保留）
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("cloud-sso running");
    }
}
```

- [ ] **Step 3: 实现 `application.yml`**

```yaml
server:
  port: 9201

spring:
  application:
    name: cloud-sso
  config:
    import: optional:nacos:${spring.application.name}.yaml
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:127.0.0.1:8848}
      discovery:
        namespace: public

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3b: 为 spring-boot-maven-plugin 增加 repackage 绑定**

修改 `cloud-base/cloud-sso/pom.xml` 的 build 段（main class 已存在，绑定后可打出可执行 jar）：

```xml
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 4: 构建**

Run: `mvn -f cloud-base/pom.xml clean install -pl cloud-sso -am`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 启动服务（后台或独立终端）**

Run: `mvn -f cloud-base/pom.xml spring-boot:run -pl cloud-sso`
（若 Nacos 不在 127.0.0.1:8848，先 `export NACOS_ADDR=<用户实际地址>`）
Expected: 日志出现 `nacos registry, cloud-sso ... register finished`（注册成功）与 `Started SsoApplication`。

- [ ] **Step 6: 直连验证**

Run: `curl http://localhost:9201/demo/ping`
Expected: `{"code":200,"msg":"操作成功","data":"cloud-sso running"}`

Run: `curl http://localhost:9201/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 7: 停止服务，Commit**

停止：终端 Ctrl+C（后台运行则 kill 对应进程）。

```bash
git add cloud-base/cloud-sso/
git commit -m "feat(cloud-sso): 服务骨架可启动并注册 Nacos

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 10: cloud-system 系统管理服务可启动（Nacos 注册验证）

**Files:**
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/SystemApplication.java`
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/controller/DemoController.java`
- Create: `cloud-base/cloud-system/src/main/resources/application.yml`

- [ ] **Step 1: 实现启动类 `SystemApplication.java`**

```java
package com.cloudai.system;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class SystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(SystemApplication.class, args);
    }
}
```

- [ ] **Step 2: 实现 `DemoController.java`**

```java
package com.cloudai.system.controller;

import com.cloudai.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 1 连通性验证接口（阶段 2 被系统管理接口替代后保留）
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("cloud-system running");
    }
}
```

- [ ] **Step 3: 实现 `application.yml`**

```yaml
server:
  port: 9202

spring:
  application:
    name: cloud-system
  config:
    import: optional:nacos:${spring.application.name}.yaml
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:127.0.0.1:8848}
      discovery:
        namespace: public

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3b: 为 spring-boot-maven-plugin 增加 repackage 绑定**

修改 `cloud-base/cloud-system/pom.xml` 的 build 段（main class 已存在，绑定后可打出可执行 jar）：

```xml
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 4: 构建**

Run: `mvn -f cloud-base/pom.xml clean install -pl cloud-system -am`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 启动服务（后台或独立终端）**

Run: `mvn -f cloud-base/pom.xml spring-boot:run -pl cloud-system`
Expected: 日志出现 Nacos `register finished` 与 `Started SystemApplication`。

- [ ] **Step 6: 直连验证**

Run: `curl http://localhost:9202/demo/ping`
Expected: `{"code":200,"msg":"操作成功","data":"cloud-system running"}`

Run: `curl http://localhost:9202/swagger-ui/index.html`
Expected: HTTP 200，Swagger UI 页面可访问。

- [ ] **Step 7: 停止服务，Commit**

```bash
git add cloud-base/cloud-system/
git commit -m "feat(cloud-system): 服务骨架可启动并注册 Nacos

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 11: cloud-bpmn 工作流服务可启动（Nacos 注册验证）

**Files:**
- Create: `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/BpmnApplication.java`
- Create: `cloud-base/cloud-bpmn/src/main/java/com/cloudai/bpmn/controller/DemoController.java`
- Create: `cloud-base/cloud-bpmn/src/main/resources/application.yml`

- [ ] **Step 1: 实现启动类 `BpmnApplication.java`**

```java
package com.cloudai.bpmn;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BpmnApplication {

    public static void main(String[] args) {
        SpringApplication.run(BpmnApplication.class, args);
    }
}
```

- [ ] **Step 2: 实现 `DemoController.java`**

```java
package com.cloudai.bpmn.controller;

import com.cloudai.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 1 连通性验证接口（阶段 4 被工作流接口替代后保留）
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("cloud-bpmn running");
    }
}
```

- [ ] **Step 3: 实现 `application.yml`**

```yaml
server:
  port: 9203

spring:
  application:
    name: cloud-bpmn
  config:
    import: optional:nacos:${spring.application.name}.yaml
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:127.0.0.1:8848}
      discovery:
        namespace: public

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

- [ ] **Step 3b: 为 spring-boot-maven-plugin 增加 repackage 绑定**

修改 `cloud-base/cloud-bpmn/pom.xml` 的 build 段（main class 已存在，绑定后可打出可执行 jar）：

```xml
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 4: 构建**

Run: `mvn -f cloud-base/pom.xml clean install -pl cloud-bpmn -am`
Expected: BUILD SUCCESS。

- [ ] **Step 5: 启动服务（后台或独立终端）**

Run: `mvn -f cloud-base/pom.xml spring-boot:run -pl cloud-bpmn`
Expected: 日志出现 Nacos `register finished` 与 `Started BpmnApplication`。

- [ ] **Step 6: 直连验证**

Run: `curl http://localhost:9203/demo/ping`
Expected: `{"code":200,"msg":"操作成功","data":"cloud-bpmn running"}`

- [ ] **Step 7: 停止服务，Commit**

```bash
git add cloud-base/cloud-bpmn/
git commit -m "feat(cloud-bpmn): 服务骨架可启动并注册 Nacos

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 12: cloud-gateway 网关（路由转发全链路验证）

**Files:**
- Create: `cloud-base/cloud-gateway/src/main/java/com/cloudai/gateway/GatewayApplication.java`
- Create: `cloud-base/cloud-gateway/src/main/resources/application.yml`

- [ ] **Step 1: 实现启动类 `GatewayApplication.java`**

```java
package com.cloudai.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [ ] **Step 2: 实现 `application.yml`（Nacos 服务发现路由 + 跨域）**

```yaml
server:
  port: 18080

spring:
  application:
    name: cloud-gateway
  config:
    import: optional:nacos:${spring.application.name}.yaml
  cloud:
    nacos:
      server-addr: ${NACOS_ADDR:127.0.0.1:8848}
      discovery:
        namespace: public
    gateway:
      routes:
        - id: cloud-sso
          uri: lb://cloud-sso
          predicates:
            - Path=/sso/**
          filters:
            - StripPrefix=1
        - id: cloud-system
          uri: lb://cloud-system
          predicates:
            - Path=/system/**
          filters:
            - StripPrefix=1
        - id: cloud-bpmn
          uri: lb://cloud-bpmn
          predicates:
            - Path=/bpmn/**
          filters:
            - StripPrefix=1
      globalcors:
        cors-configurations:
          '[/**]':
            allowedOriginPatterns: "*"
            allowedMethods: "*"
            allowedHeaders: "*"
            allowCredentials: true

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

说明：`StripPrefix=1` 使 `/sso/demo/ping` → `cloud-sso` 服务的 `/demo/ping`；`/inner/**` 屏蔽与 JWT 过滤器在阶段 3 加入。

- [ ] **Step 2b: 为 spring-boot-maven-plugin 增加 repackage 绑定**

修改 `cloud-base/cloud-gateway/pom.xml` 的 build 段（main class 已存在，绑定后可打出可执行 jar）：

```xml
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <executions>
                    <execution>
                        <goals>
                            <goal>repackage</goal>
                        </goals>
                    </execution>
                </executions>
            </plugin>
```

- [ ] **Step 3: 构建**

Run: `mvn -f cloud-base/pom.xml clean install -pl cloud-gateway -am`
Expected: BUILD SUCCESS。

- [ ] **Step 4: 依次启动三个业务服务与网关（4 个后台任务或 4 个终端）**

```bash
mvn -f cloud-base/pom.xml spring-boot:run -pl cloud-sso
mvn -f cloud-base/pom.xml spring-boot:run -pl cloud-system
mvn -f cloud-base/pom.xml spring-boot:run -pl cloud-bpmn
mvn -f cloud-base/pom.xml spring-boot:run -pl cloud-gateway
```

Expected: 四个进程全部 Started 且无异常。

- [ ] **Step 5: 经网关全链路验证（阶段 1 验收标准）**

```bash
curl http://localhost:18080/sso/demo/ping
curl http://localhost:18080/system/demo/ping
curl http://localhost:18080/bpmn/demo/ping
```

Expected 依次为：

```json
{"code":200,"msg":"操作成功","data":"cloud-sso running"}
{"code":200,"msg":"操作成功","data":"cloud-system running"}
{"code":200,"msg":"操作成功","data":"cloud-bpmn running"}
```

三个请求成功即同时证明：网关路由、Nacos 服务发现（`lb://` 解析）、统一返回体全链路生效。

- [ ] **Step 6: Nacos 控制台核对（可选人工步骤）**

打开 Nacos 控制台（2.x：`http://127.0.0.1:8848/nacos`；3.x：控制台独立端口），服务列表应显示 4 个服务实例（cloud-gateway/cloud-sso/cloud-system/cloud-bpmn）。

- [ ] **Step 7: 停止全部服务，Commit**

```bash
git add cloud-base/cloud-gateway/
git commit -m "feat(cloud-gateway): 网关骨架（Nacos 发现路由/StripPrefix/跨域）

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 13: cloud-base README 与阶段 1 验收清单

**Files:**
- Create: `cloud-base/README.md`

- [ ] **Step 1: 写 `cloud-base/README.md`**

```markdown
# cloud-base 后端聚合工程

企业应用基座平台：Spring Cloud Alibaba 微服务体系。

## 技术栈

JDK 17 / Spring Boot 3.3.x / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.3（Nacos）
MyBatis-Plus 3.5.7 / MySQL 8 / Redis / Flowable 7.2.0（阶段4引入）

## 模块

| 模块 | 端口 | 说明 |
|---|---|---|
| cloud-gateway | 18080 | API 网关：路由转发（lb://）、跨域 |
| cloud-sso | 9201 | 认证中心（阶段3实现） |
| cloud-system | 9202 | 系统管理 RBAC（阶段2实现） |
| cloud-bpmn | 9203 | 工作流 Flowable（阶段4实现） |
| cloud-common-* | - | core/security/mybatis/redis 公共模块 |

## 构建与启动

前置：JDK 17、Maven 3.8+、运行中的 Nacos（默认 127.0.0.1:8848，可用环境变量 NACOS_ADDR 覆盖）。

    mvn clean install
    mvn spring-boot:run -pl cloud-sso
    mvn spring-boot:run -pl cloud-system
    mvn spring-boot:run -pl cloud-bpmn
    mvn spring-boot:run -pl cloud-gateway   # 网关最后启动

验证：curl http://localhost:18080/system/demo/ping

## 阶段状态

- [x] 阶段1 工程骨架（本阶段）
- [ ] 阶段2 系统管理（cloud-system RBAC）
- [ ] 阶段3 认证链路（cloud-sso + 网关鉴权）
- [ ] 阶段4 工作流（cloud-bpmn + Flowable）

设计文档：../docs/superpowers/specs/2026-10-04-cloud-base-backend-design.md
```

- [ ] **Step 2: 阶段 1 验收清单逐项核对**

- [ ] `mvn -f cloud-base/pom.xml clean install` 全量 BUILD SUCCESS
- [ ] 4 个服务可启动并注册 Nacos（Task 12 Step 5 三条 curl 全部返回统一 `R<T>`）
- [ ] common-core 单测 9 个全绿；common-security 4 个全绿；common-mybatis 3 个全绿
- [ ] 工作区无未提交文件（`git status` clean）

- [ ] **Step 3: Commit**

```bash
git add cloud-base/README.md
git commit -m "docs(cloud-base): 工程README与阶段1验收清单

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 计划自检记录（写计划时已核对）

1. **Spec 覆盖（阶段 1 范围）**：父 POM（Task 1）、4 公共模块（Task 2-8）、4 可启动服务（Task 9-12）、Nacos 注册+配置 import（Task 9-12）、网关路由（Task 12）、统一返回/异常（Task 2-5）——设计文档阶段 1 全部条目有对应任务。阶段 2/3/4 留待各自计划。
2. **无占位符**：所有代码步骤含完整代码（含 Task 1 中 system/bpmn 两个 POM 的完整 XML，未用"同 Task N"引用）。
3. **类型一致性**：`R.ok/R.fail`、`ErrorCode` 枚举值、`JwtUtil.createToken/parseToken`、`BaseEntity.createTime/updateTime/deleted`、DemoController 路径 `/demo/ping` 与网关 `StripPrefix=1` 规则前后一致。

## 执行完成后

阶段 1 验收通过后：编写阶段 2（cloud-system RBAC）实施计划 `docs/superpowers/plans/<日期>-cloud-base-phase2-system.md`。

