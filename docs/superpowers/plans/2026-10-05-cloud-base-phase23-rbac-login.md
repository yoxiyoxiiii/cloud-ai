# cloud-base 阶段 2+3：权限管理与登录链路（最小闭环）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 最小闭环交付：cloud-system RBAC（用户/角色/菜单权限 5 表 + 管理 CRUD）+ cloud-sso 账密登录（双 token + Redis 在线状态）+ 网关 JWT 鉴权（白名单/验签/在线检查/X-User-* 透传//inner 屏蔽）+ 下游 @PreAuthorize 方法级权限。

**Architecture:** 数据在 cloud-system（MySQL cloud_system 库），sso 经 Feign（/inner 接口）取用户与权限集合，签发 HS512 JWT（jti=tokenId），Redis 存 `sso:online:{jti}` 会话（含权限集合，epochMillis 存时间——LocalDateTime 不可 JSON 序列化，见阶段 1 备忘）。网关全局过滤器验签 + 查在线 + 剥离伪造 header + 注入 X-User-Id/Account/Perms；common-security-starter 提供 servlet 端自动配置（header→Authentication + @EnableMethodSecurity），方法级 @PreAuthorize 生效。未加注解的接口=匿名可达（外部访问由网关白名单把门）。

**Tech Stack:** 沿用阶段 1（Boot 3.3.4/SCA 2023.0.3.3）；新增 spring-boot-starter-security（BCrypt+方法安全）、spring-cloud-starter-openfeign（sso→system）、mysql-connector-j。

**范围精简（用户 2026-10-05 确认"最小闭环"）**：不做验证码、登录日志、部门、岗位、字典、参数配置、操作日志——聚焦 RBAC + 登录闭环，后续按需补。设计文档 §9 的这些项留待扩展任务。

**前置条件（执行前核对）：**
1. MySQL 127.0.0.1:3306，root/空密码（用户环境实测；本机无 mysql 客户端，SQL 用 jshell+mysql-connector-j 执行）。
2. Nacos 127.0.0.1:8848 运行中（无鉴权，v1 OpenAPI 可直接发布配置）。
3. Redis 127.0.0.1:6379（Docker，无密码，Boot 默认零配置可用）。
4. mvn 绝对路径 `D:/software/apache-maven-3.8.4/bin/mvn`；服务启动用 `java -jar`；停服用 netstat+taskkill（Git Bash `$!` 不可靠）。
5. 本地 Maven 仓库在 `D:\source\repo`（jshell 加载 jar 用）。
6. 提交信息附 `Co-Authored-By: Claude Code <noreply@anthropic.com>`。

**测试策略说明（与阶段 1 的偏离记录）：** 纯逻辑（过滤器/树构建/TokenService 分支/审计填充/RedisUtil）走 TDD 单测；DB 依赖的 CRUD/查询逻辑用 Task 12 端到端验收覆盖（引入 H2 兼容库做 Service 单测的性价比在最小闭环阶段不划算，记录取舍）。

## 文件结构总览

```
cloud-base/
├── scripts/sql/cloud_system.sql                      # T2
├── cloud-common/cloud-common-security-starter/
│   └── src/main/java/com/cloudai/common/security/
│       ├── constant/SecurityConstants.java           # T3（X-User-* header 常量，网关与资源端共用）
│       ├── domain/LoginUser.java                     # T3
│       ├── filter/HeaderAuthFilter.java              # T3
│       └── config/CommonSecurityAutoConfiguration.java # T3（servlet 端 SecurityFilterChain+方法安全+PasswordEncoder）
│   └── src/main/resources/META-INF/spring/...imports # T3（新建）
│   └── src/test/java/...（HeaderAuthFilterTest / CommonSecurityAutoConfigurationTest）
├── cloud-common/cloud-common-mybatis-starter/
│   └── .../domain/BaseEntity.java                    # T4（+createBy/updateBy）
│   └── .../handler/AuditMetaObjectHandler.java       # T4（从登录上下文填充）
├── cloud-common/cloud-common-redis-starter/
│   └── .../util/RedisUtil.java                       # T9（+keys 方法）
├── cloud-system/
│   ├── pom.xml（+mybatis/security starter、mysql-connector-j）
│   ├── src/main/resources/application.yml（+datasource）
│   ├── src/main/java/com/cloudai/system/
│   │   ├── SystemApplication.java（+@MapperScan）
│   │   ├── entity/（SysUser/SysRole/SysMenu/SysUserRole/SysRoleMenu）  # T5
│   │   ├── mapper/（5 个 BaseMapper 接口）                             # T5
│   │   ├── dto/LoginUserDTO.java                                       # T5
│   │   ├── service/（SysUserService/SysRoleService/SysMenuService）    # T5/T7/T8
│   │   ├── controller/（InnerUserController/SysUserController/SysRoleController/SysMenuController） # T6/T7/T8
│   │   └── util/MenuTreeBuilder.java                                   # T8（纯函数 TDD）
├── cloud-sso/
│   ├── pom.xml（+security/redis starter、openfeign、loadbalancer）
│   ├── src/main/resources/application.yml（+cloud-common.yaml 导入）
│   └── src/main/java/com/cloudai/sso/
│       ├── SsoApplication.java（+@EnableFeignClients）                 # T9
│       ├── client/SystemUserClient.java + fallback                    # T9
│       ├── dto/（LoginUserDTO/LoginRequest/LoginResult）               # T9
│       ├── domain/OnlineSession.java                                   # T9
│       ├── service/TokenService.java                                   # T9
│       └── controller/AuthController.java                              # T9
└── cloud-gateway/
    ├── pom.xml（+core/security/redis starter）                          # T10
    ├── src/main/resources/application.yml（+cloud-common.yaml 导入、+3 条 inner 屏蔽路由）
    └── src/main/java/com/cloudai/gateway/filter/AuthGlobalFilter.java   # T10
```

---

## Task 1: Nacos 共享配置（JWT 密钥）

**Files:** 无仓库文件（sso/gateway 的 application.yml 修改在 T9/T10 各自进行；本任务只发布 Nacos 配置）

- [ ] **Step 1: 生成 64 字节 HS512 密钥并发布 cloud-common.yaml**

```bash
SECRET=$(openssl rand -hex 32)   # 64 个 hex 字符 = 64 字节，满足 HS512 >=64 字节
curl -s -X POST "http://127.0.0.1:8848/nacos/v1/cs/configs" \
  --data-urlencode "dataId=cloud-common.yaml" \
  --data-urlencode "group=DEFAULT_GROUP" \
  --data-urlencode "type=yaml" \
  --data-urlencode "content=cloud:
  jwt:
    secret: ${SECRET}
    access-token-ttl: 7200
    refresh-token-ttl: 604800
"
```

Expected: 返回 `true`。

- [ ] **Step 2: 回读验证**

```bash
curl -s "http://127.0.0.1:8848/nacos/v1/cs/configs?dataId=cloud-common.yaml&group=DEFAULT_GROUP"
```

Expected: 返回的 yaml 中 secret 为 64 字符 hex、ttl 为 7200/604800。**把生成的 secret 记录在报告里**（后续任务调试需要；它只在 Nacos，不进仓库）。

**说明**：datasource 走各服务本地 yml（root/空密码无秘密可言，生产再外置——与设计 §4"中间件连接放 Nacos"的偏差记录在案）；JWT 密钥是真秘密，必须在 Nacos。

---

## Task 2: cloud_system 库表与初始数据（SQL 脚本 + jshell 执行）

**Files:**
- Create: `cloud-base/scripts/sql/cloud_system.sql`

- [ ] **Step 1: 生成 admin123 的 BCrypt 散列**

```bash
HUTOOL=$(ls D:/source/repo/cn/hutool/hutool-all/5.8.32/hutool-all-5.8.32.jar)
echo 'System.out.println(cn.hutool.crypto.digest.BCrypt.hashpw("admin123"));' | jshell --class-path "$HUTOOL" -s -
```

Expected: 输出 `$2a$10$...` 60 字符 BCrypt 散列（$2a$ 开头）。记下该值用于 SQL。

- [ ] **Step 2: 写 `cloud-base/scripts/sql/cloud_system.sql`**

```sql
-- cloud-system 库初始化（最小闭环：RBAC 5 表 + 初始数据）
-- ⚠️ 开发环境初始化脚本：DROP 并重建 cloud_system 全部表，生产环境禁止执行
-- 引用完整性由服务层维护（微服务惯例，不建外键）；实测 MySQL 5.7.24+
CREATE DATABASE IF NOT EXISTS cloud_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE cloud_system;

DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role_menu;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_menu;

-- 用户
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    account     VARCHAR(30)  NOT NULL COMMENT '登录账号',
    nickname    VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '昵称',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码散列',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account (account)
) ENGINE = InnoDB COMMENT = '用户表';

-- 角色
CREATE TABLE sys_role (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    name        VARCHAR(30) NOT NULL COMMENT '角色名称',
    role_key    VARCHAR(30) NOT NULL COMMENT '角色标识',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL,
    create_time DATETIME    DEFAULT NULL,
    update_by   VARCHAR(30)  DEFAULT NULL,
    update_time DATETIME    DEFAULT NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_key (role_key)
) ENGINE = InnoDB COMMENT = '角色表';

-- 菜单/权限（M目录 C菜单 F按钮；perms 为权限标识，按钮/菜单有效）
CREATE TABLE sys_menu (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父ID，0为根',
    name        VARCHAR(30) NOT NULL COMMENT '名称',
    perms       VARCHAR(50) NOT NULL DEFAULT '' COMMENT '权限标识，如 system:user:list',
    type        CHAR(1)     NOT NULL DEFAULT 'C' COMMENT 'M目录 C菜单 F按钮',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL,
    create_time DATETIME    DEFAULT NULL,
    update_by   VARCHAR(30)  DEFAULT NULL,
    update_time DATETIME    DEFAULT NULL,
    deleted     TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE = InnoDB COMMENT = '菜单权限表';

CREATE TABLE sys_user_role (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    user_id     BIGINT   NOT NULL,
    role_id     BIGINT   NOT NULL,
    create_time DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role_id (role_id)
) ENGINE = InnoDB COMMENT = '用户角色关联（纯关系表：物理删除，无逻辑删除列）';

CREATE TABLE sys_role_menu (
    id          BIGINT   NOT NULL AUTO_INCREMENT,
    role_id     BIGINT   NOT NULL,
    menu_id     BIGINT   NOT NULL,
    create_time DATETIME DEFAULT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    KEY idx_menu_id (menu_id)
) ENGINE = InnoDB COMMENT = '角色菜单关联（纯关系表：物理删除，无逻辑删除列）';

-- ---------- 初始数据 ----------
INSERT INTO sys_role (id, name, role_key, create_time) VALUES (1, '管理员', 'admin', NOW());

INSERT INTO sys_menu (id, parent_id, name, perms, type, sort, create_time) VALUES
(10, 0, '系统管理',   '',                   'M', 1, NOW()),
(11, 10, '用户管理',  'system:user:list',   'C', 1, NOW()),
(12, 10, '角色管理',  'system:role:list',   'C', 2, NOW()),
(13, 10, '菜单管理',  'system:menu:list',   'C', 3, NOW()),
(111, 11, '用户新增', 'system:user:add',    'F', 1, NOW()),
(112, 11, '用户修改', 'system:user:edit',   'F', 2, NOW()),
(113, 11, '用户删除', 'system:user:remove', 'F', 3, NOW()),
(114, 11, '重置密码', 'system:user:resetPwd','F', 4, NOW()),
(115, 11, '分配角色', 'system:user:assignRole','F', 5, NOW()),
(121, 12, '角色新增', 'system:role:add',    'F', 1, NOW()),
(122, 12, '角色修改', 'system:role:edit',   'F', 2, NOW()),
(123, 12, '角色删除', 'system:role:remove', 'F', 3, NOW()),
(124, 12, '分配权限', 'system:role:assignMenu','F', 4, NOW()),
(131, 13, '菜单新增', 'system:menu:add',    'F', 1, NOW()),
(132, 13, '菜单修改', 'system:menu:edit',   'F', 2, NOW()),
(133, 13, '菜单删除', 'system:menu:remove', 'F', 3, NOW()),
(20, 0, '认证管理',   '',                   'M', 2, NOW()),
(21, 20, '在线用户',  'sso:online:list',    'C', 1, NOW()),
(211, 21, '强制下线', 'sso:online:kick',    'F', 1, NOW());

-- admin 账号（密码 admin123，散列由 Step 1 生成后替换 <BCRYPT_ADMIN123>）
INSERT INTO sys_user (id, account, nickname, password, create_time) VALUES
(1, 'admin', '管理员', '<BCRYPT_ADMIN123>', NOW());

INSERT INTO sys_user_role (user_id, role_id, create_time) VALUES (1, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu;
```

- [ ] **Step 3: 用 jshell + mysql-connector-j 执行脚本**

```bash
CONN=$(ls D:/source/repo/com/mysql/mysql-connector-j/*/mysql-connector-j-*.jar | head -1)
cat > /tmp/exec-sql.jsh <<'EOF'
import java.sql.*;
var conn = DriverManager.getConnection("jdbc:mysql://127.0.0.1:3306/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=GMT%2B8", "root", "");
var sql = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("cloud-base/scripts/sql/cloud_system.sql")), "UTF-8");
for (String stmt : sql.split(";\\s*\\n")) {
    String s = stmt.trim();
    if (s.isEmpty() || s.startsWith("--")) continue;
    conn.createStatement().execute(s);
}
System.out.println("SQL_EXECUTED_OK");
conn.close();
/exit
EOF
jshell --class-path "$CONN" /tmp/exec-sql.jsh
```

（若 mysql-connector-j 不在本地仓库，先 `D:/software/apache-maven-3.8.4/bin/mvn dependency:get -Dartifact=com.mysql:mysql-connector-j:8.3.0` 拉取。注意：按 `;`+换行切分不含分号的数据行；INSERT 多行语句内无分号，安全。）

- [ ] **Step 4: 验证数据**

jshell 再执行查询 `USE cloud_system; SELECT account FROM sys_user; SELECT COUNT(*) FROM sys_menu; SELECT COUNT(*) FROM sys_role_menu;`
Expected: admin / 19 / 19 / 17（19 行菜单；role_menu 19；非空 perms 17——目录 10/20 为空 perms。原期望 21/21/19 为计划笔误，2026-10-05 执行时修正）。

- [ ] **Step 5: Commit**

```bash
git add cloud-base/scripts/
git commit -m "feat(cloud-system): cloud_system 建库脚本与RBAC初始数据

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 3: common-security-starter 资源端自动配置（TDD）

**Files:**
- Modify: `cloud-base/cloud-common/cloud-common-security-starter/pom.xml`
- Create: `.../src/main/java/com/cloudai/common/security/constant/SecurityConstants.java`
- Create: `.../src/main/java/com/cloudai/common/security/domain/LoginUser.java`
- Create: `.../src/main/java/com/cloudai/common/security/filter/HeaderAuthFilter.java`
- Create: `.../src/main/java/com/cloudai/common/security/config/CommonSecurityAutoConfiguration.java`
- Create: `.../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `.../src/test/java/com/cloudai/common/security/filter/HeaderAuthFilterTest.java`
- Test: `.../src/test/java/com/cloudai/common/security/config/CommonSecurityAutoConfigurationTest.java`

**背景**：网关（WebFlux）也依赖本模块（用 JwtUtil 与常量），因此安全自动配置必须限定 servlet 环境，否则会在网关激活 servlet 过滤链。

- [ ] **Step 1: pom 增加依赖**（jjwt 依赖保留）

```xml
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-core-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-security</artifactId>
        </dependency>
```

（父 pom dependencyManagement 需先加内部模块 `cloud-common-core-starter` 条目——Task 1 阶段已存在；无需版本号。）

- [ ] **Step 2: 写失败测试 `HeaderAuthFilterTest.java`**

```java
package com.cloudai.common.security.filter;

import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.domain.LoginUser;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class HeaderAuthFilterTest {

    private final HeaderAuthFilter filter = new HeaderAuthFilter();

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
    }

    private Authentication authInChain() throws Exception {
        AtomicReference<Authentication> captured = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            captured.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }).when(chain).doFilter(any(), any());
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return captured.get();
    }

    @Test
    void buildsAuthenticationFromHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_USER_ID, "1");
        request.addHeader(SecurityConstants.HEADER_USER_ACCOUNT, "admin");
        request.addHeader(SecurityConstants.HEADER_USER_PERMS, "system:user:list,system:user:add");

        AtomicReference<Authentication> captured = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            captured.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        Authentication auth = captured.get();
        assertThat(auth).isInstanceOf(UsernamePasswordAuthenticationToken.class);
        LoginUser user = (LoginUser) auth.getPrincipal();
        assertThat(user.getUserId()).isEqualTo(1L);
        assertThat(user.getAccount()).isEqualTo("admin");
        assertThat(user.getPermissions()).containsExactly("system:user:list", "system:user:add");
        assertThat(auth.getAuthorities())
                .contains(new SimpleGrantedAuthority("system:user:list"), new SimpleGrantedAuthority("system:user:add"));
    }

    @Test
    void noHeadersRemainsAnonymous() throws Exception {
        assertThat(authInChain()).isNull();
    }

    @Test
    void malformedUserIdTreatedAsAnonymous() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_USER_ID, "abc");
        AtomicReference<Authentication> captured = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            captured.set(SecurityContextHolder.getContext().getAuthentication());
            return null;
        }).when(chain).doFilter(any(), any());
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(captured.get()).isNull();
    }

    @Test
    void contextClearedAfterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(SecurityConstants.HEADER_USER_ID, "1");
        FilterChain chain = mock(FilterChain.class);
        filter.doFilter(request, new MockHttpServletResponse(), chain);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-security-starter`
Expected: COMPILATION ERROR。

- [ ] **Step 4: 实现 `SecurityConstants.java`**

```java
package com.cloudai.common.security.constant;

/**
 * 网关与资源端共享的内部透传 header 常量（网关剥离外部同名 header 后注入）
 */
public final class SecurityConstants {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ACCOUNT = "X-User-Account";
    public static final String HEADER_USER_PERMS = "X-User-Perms";
    public static final String PERMS_SEPARATOR = ",";

    /** Redis 在线会话键前缀，jti 为 JWT 的 jti 声明 */
    public static final String ONLINE_KEY_PREFIX = "sso:online:";

    private SecurityConstants() {
    }
}
```

- [ ] **Step 5: 实现 `LoginUser.java`**

```java
package com.cloudai.common.security.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 登录用户上下文（资源端从网关透传 header 构建）
 */
@Data
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String account;
    private String nickname;
    private List<String> permissions;
}
```

- [ ] **Step 6: 实现 `HeaderAuthFilter.java`**

```java
package com.cloudai.common.security.filter;

import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.domain.LoginUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * 从网关透传的 X-User-* header 构建认证上下文；无 header 即匿名；
 * userId 非数字视为无效（伪造防护兜底），整单按匿名处理。
 * 请求结束后清理 SecurityContext（无状态，线程池复用防串号）。
 */
public class HeaderAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            buildAuthentication(request);
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void buildAuthentication(HttpServletRequest request) {
        String userId = request.getHeader(SecurityConstants.HEADER_USER_ID);
        if (userId == null || userId.isBlank()) {
            return;
        }
        Long uid;
        try {
            uid = Long.valueOf(userId.trim());
        } catch (NumberFormatException e) {
            return;
        }
        LoginUser user = new LoginUser();
        user.setUserId(uid);
        user.setAccount(request.getHeader(SecurityConstants.HEADER_USER_ACCOUNT));
        String perms = request.getHeader(SecurityConstants.HEADER_USER_PERMS);
        List<String> permList = (perms == null || perms.isBlank())
                ? List.of()
                : Arrays.asList(perms.split(SecurityConstants.PERMS_SEPARATOR));
        user.setPermissions(permList);
        var authorities = permList.stream().map(SimpleGrantedAuthority::new).toList();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(user, null, authorities));
    }
}
```

- [ ] **Step 7: 写失败测试 `CommonSecurityAutoConfigurationTest.java`**

```java
package com.cloudai.common.security.config;

import com.cloudai.common.security.filter.HeaderAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class CommonSecurityAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withUserConfiguration(ServletWebConfig.class)
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(CommonSecurityAutoConfiguration.class));

    @Test
    void registersFilterChainAndAdvice() {
        runner.run(ctx -> {
            org.assertj.core.api.Assertions.assertThat(ctx).hasSingleBean(HeaderAuthFilter.class);
            org.assertj.core.api.Assertions.assertThat(ctx).hasBean("cloudSecurityFilterChain");
            org.assertj.core.api.Assertions.assertThat(ctx).hasSingleBean(
                    "securityExceptionAdvice");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    @EnableWebSecurity
    static class ServletWebConfig {
    }
}
```

（若该装配测试因缺 WebMvc 组件报错，允许给 withUserConfiguration 补最小 MockMvc 相关 bean；目标只验证自动配置在 servlet 环境注册三个 bean。）

- [ ] **Step 8: 实现 `CommonSecurityAutoConfiguration.java`**

```java
package com.cloudai.common.security.config;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.security.filter.HeaderAuthFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 资源端安全自动配置（仅 servlet 生效；网关是 WebFlux 自动退避）。
 * URL 级不做拦截（外部访问由网关白名单把门），方法级用 @PreAuthorize；
 * 未加注解的接口=匿名可达。@PreAuthorize 拒绝时 HTTP 200 + body code=403（全局约定）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableWebSecurity
@EnableMethodSecurity
public class CommonSecurityAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public HeaderAuthFilter headerAuthFilter() {
        return new HeaderAuthFilter();
    }

    @Bean
    @ConditionalOnMissingBean(name = "cloudSecurityFilterChain")
    public SecurityFilterChain cloudSecurityFilterChain(HttpSecurity http, HeaderAuthFilter headerAuthFilter)
            throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .addFilterBefore(headerAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @ConditionalOnMissingBean(PasswordEncoder.class)
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    @ConditionalOnMissingBean(name = "securityExceptionAdvice")
    public Object securityExceptionAdvice() {
        return new SecurityExceptionAdvice();
    }

    /**
     * 方法级权限拒绝 → 统一 R 格式（HTTP 200 + code 403）
     */
    @RestControllerAdvice
    static class SecurityExceptionAdvice {

        @ExceptionHandler(AccessDeniedException.class)
        public R<Void> handleAccessDenied(AccessDeniedException e) {
            return R.fail(ErrorCode.FORBIDDEN);
        }
    }
}
```

（`HttpStatus` import 未用到则删除。）

- [ ] **Step 9: 创建 imports 文件**

路径：`cloud-base/cloud-common/cloud-common-security-starter/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`

```
com.cloudai.common.security.config.CommonSecurityAutoConfiguration
```

- [ ] **Step 10: 运行全部测试确认通过**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-common/cloud-common-security-starter`
Expected: BUILD SUCCESS，`Tests run: 12`（JwtUtil 7 + HeaderAuthFilter 4 + AutoConfig 1）。

- [ ] **Step 11: Commit**

```bash
git add cloud-base/
git commit -m "feat(common-security-starter): 资源端header认证自动配置与方法级权限

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 4: 审计字段扩展（BaseEntity + 登录上下文填充，TDD）

**Files:**
- Modify: `cloud-base/cloud-common/cloud-common-mybatis-starter/src/main/java/com/cloudai/common/mybatis/domain/BaseEntity.java`
- Modify: `.../handler/AuditMetaObjectHandler.java`
- Modify: `.../pom.xml`（+cloud-common-security-starter 依赖）
- Test: 修改既有 `AuditMetaObjectHandlerTest.java`

- [ ] **Step 1: pom 增加依赖**

```xml
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-security-starter</artifactId>
        </dependency>
```

（用于读取登录上下文；其自动配置在纯单测环境不激活，无副作用。）

- [ ] **Step 2: 追加失败测试**（`AuditMetaObjectHandlerTest` 内新增）

```java
    @Test
    void insertFill_fillsOperatorFromSecurityContext() {
        var loginUser = new com.cloudai.common.security.domain.LoginUser();
        loginUser.setAccount("admin");
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                loginUser, null, java.util.List.of());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            OrderEntity entity = new OrderEntity();
            handler.insertFill(SystemMetaObject.forObject(entity));
            assertThat(entity.getCreateBy()).isEqualTo("admin");
            assertThat(entity.getUpdateBy()).isEqualTo("admin");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void insertFill_noContextLeavesOperatorNull() {
        OrderEntity entity = new OrderEntity();
        handler.insertFill(SystemMetaObject.forObject(entity));
        assertThat(entity.getCreateBy()).isNull();
    }
```

- [ ] **Step 3: 运行确认失败**（无 createBy 字段 → 编译错误）

- [ ] **Step 4: `BaseEntity` 增加两个字段**（updateTime 之后、deleted 之前）

```java
    /** 创建人（插入填充，来自登录上下文） */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /** 更新人（插入和更新都填充，来自登录上下文） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;
```

- [ ] **Step 5: `AuditMetaObjectHandler` 扩展**

```java
package com.cloudai.common.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.cloudai.common.security.domain.LoginUser;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：无对应属性的实体自动跳过；时间语义为服务器时间总是生效（覆盖调用方已设值）；
 * 操作人取登录上下文（LoginUser.account），匿名场景留空。
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        String operator = currentOperator();
        if (metaObject.hasSetter("createTime")) {
            setFieldValByName("createTime", now, metaObject);
        }
        if (metaObject.hasSetter("updateTime")) {
            setFieldValByName("updateTime", now, metaObject);
        }
        if (metaObject.hasSetter("createBy")) {
            setFieldValByName("createBy", operator, metaObject);
        }
        if (metaObject.hasSetter("updateBy")) {
            setFieldValByName("updateBy", operator, metaObject);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updateTime")) {
            setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        }
        if (metaObject.hasSetter("updateBy")) {
            setFieldValByName("updateBy", currentOperator(), metaObject);
        }
    }

    private String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getAccount();
        }
        return null;
    }
}
```

注意：`setFieldValByName` 对 null 值默认跳过（MP 行为），operator 为 null 时字段保持不变，满足"匿名留空"。

- [ ] **Step 6: 运行测试确认通过**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-mybatis-starter`
Expected: `Tests run: 6`（原 4 + 新 2），全绿。

- [ ] **Step 7: Commit**

```bash
git add cloud-base/cloud-common/cloud-common-mybatis-starter/
git commit -m "feat(common-mybatis-starter): 审计字段扩展createBy/updateBy并从登录上下文填充

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 5: cloud-system 持久层接入与实体/Mapper/登录查询

**Files:**
- Modify: `cloud-base/cloud-system/pom.xml`
- Modify: `cloud-base/cloud-system/src/main/resources/application.yml`
- Modify: `cloud-base/cloud-system/src/main/java/com/cloudai/system/SystemApplication.java`
- Create: `.../entity/SysUser.java` `SysRole.java` `SysMenu.java` `SysUserRole.java` `SysRoleMenu.java`
- Create: `.../mapper/SysUserMapper.java` `SysRoleMapper.java` `SysMenuMapper.java` `SysUserRoleMapper.java` `SysRoleMenuMapper.java`
- Create: `.../dto/LoginUserDTO.java`
- Create: `.../service/SysUserService.java`

- [ ] **Step 1: pom 增加依赖**（springdoc 之后）

```xml
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-mybatis-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-security-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.mysql</groupId>
            <artifactId>mysql-connector-j</artifactId>
            <scope>runtime</scope>
        </dependency>
```

- [ ] **Step 2: application.yml 增加数据源**（spring: 节点下）

```yaml
  datasource:
    url: jdbc:mysql://127.0.0.1:3306/cloud_system?useUnicode=true&characterEncoding=utf8&serverTimezone=GMT%2B8&useSSL=false&allowPublicKeyRetrieval=true
    username: root
    password: ""
    driver-class-name: com.mysql.cj.jdbc.Driver
```

- [ ] **Step 3: SystemApplication 加 @MapperScan**

```java
package com.cloudai.system;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.cloudai.system.mapper")
public class SystemApplication {

    public static void main(String[] args) {
        SpringApplication.run(SystemApplication.class, args);
    }
}
```

- [ ] **Step 4: 实现 5 个实体**

`SysUser.java`：

```java
package com.cloudai.system.entity;

import com.cloudai.common.mybatis.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String account;

    private String nickname;

    /** BCrypt 散列；序列化时不出现在响应中 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 0正常 1停用 */
    private Integer status;
}
```

`SysRole.java`：

```java
package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_role")
public class SysRole extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private String name;

    private String roleKey;

    /** 0正常 1停用 */
    private Integer status;
}
```

`SysMenu.java`：

```java
package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_menu")
public class SysMenu extends BaseEntity {

    private static final long serialVersionUID = 1L;

    private Long parentId;

    private String name;

    /** 权限标识，如 system:user:list */
    private String perms;

    /** M目录 C菜单 F按钮 */
    private String type;

    private Integer sort;

    /** 0正常 1停用 */
    private Integer status;
}
```

`SysUserRole.java`（**纯关系表实体：不继承 BaseEntity**——无逻辑删除列避免与联合唯一键冲突，mapper.delete 为物理删除）：

```java
package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("sys_user_role")
public class SysUserRole implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long roleId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
```

`SysRoleMenu.java`（同上，纯关系表实体）：

```java
package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("sys_role_menu")
public class SysRoleMenu implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long roleId;

    private Long menuId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
```

- [ ] **Step 5: 实现 5 个 Mapper**（同构，示例一个，其余替换类名/实体）

```java
package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudai.system.entity.SysUser;

public interface SysUserMapper extends BaseMapper<SysUser> {
}
```

（SysRoleMapper/SysMenuMapper/SysUserRoleMapper/SysRoleMenuMapper 同构。）

- [ ] **Step 6: 实现 `LoginUserDTO.java`**

```java
package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * sso 登录所需的用户聚合（含密码散列——仅内网 /inner 通道返回）
 */
@Data
public class LoginUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String account;
    private String nickname;
    private String password;
    /** 权限标识集合 */
    private List<String> permissions;
    /** 0正常 1停用 */
    private Integer status;
}
```

- [ ] **Step 7: 实现 `SysUserService.java`**

```java
package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.system.dto.LoginUserDTO;
import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysRoleMenu;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.entity.SysUserRole;
import com.cloudai.system.mapper.SysMenuMapper;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 登录联动查询：账号 → 用户 + 权限标识集合（经 角色→菜单 聚合）
 */
@Service
@RequiredArgsConstructor
public class SysUserLinkageService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysMenuMapper menuMapper;

    public LoginUserDTO getLoginUserByAccount(String account) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccount, account)
                .last("LIMIT 1"));
        if (user == null) {
            return null;
        }
        LoginUserDTO dto = new LoginUserDTO();
        dto.setUserId(user.getId());
        dto.setAccount(user.getAccount());
        dto.setNickname(user.getNickname());
        dto.setPassword(user.getPassword());
        dto.setStatus(user.getStatus());

        List<Long> roleIds = userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, user.getId()))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            dto.setPermissions(List.of());
            return dto;
        }
        List<Long> enabledRoleIds = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                        .in(SysRole::getId, roleIds).eq(SysRole::getStatus, 0))
                .stream().map(SysRole::getId).toList();
        if (enabledRoleIds.isEmpty()) {
            dto.setPermissions(List.of());
            return dto;
        }
        List<Long> menuIds = roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .in(SysRoleMenu::getRoleId, enabledRoleIds))
                .stream().map(SysRoleMenu::getMenuId).distinct().toList();
        if (menuIds.isEmpty()) {
            dto.setPermissions(List.of());
            return dto;
        }
        List<String> perms = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                        .in(SysMenu::getId, menuIds).eq(SysMenu::getStatus, 0))
                .stream().map(SysMenu::getPerms)
                .filter(p -> p != null && !p.isBlank())
                .distinct()
                .toList();
        dto.setPermissions(perms);
        return dto;
    }
}
```

- [ ] **Step 8: 构建并启动验证数据源**

```bash
D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am -DskipTests
java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar   # 后台
curl http://localhost:9202/actuator/health   # {"status":"UP"}（数据源连通即 UP）
# 验证后 netstat 找 PID + taskkill 停止
```

Expected: UP（说明 datasource/Hikari/MP 装配成功）。

- [ ] **Step 9: Commit**

```bash
git add cloud-base/cloud-system/
git commit -m "feat(cloud-system): 持久层接入与RBAC实体/登录联动查询

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 6: /inner 内部用户接口

**Files:**
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/controller/InnerUserController.java`

- [ ] **Step 1: 实现**

```java
package com.cloudai.system.controller;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.LoginUserDTO;
import com.cloudai.system.service.SysUserLinkageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口：仅 Feign 调用，网关已屏蔽 /{service}/inner/**。
 */
@RestController
@RequestMapping("/inner/user")
@RequiredArgsConstructor
public class InnerUserController {

    private final SysUserLinkageService linkageService;

    /** 按账号取登录聚合（含密码散列与权限集合）；账号不存在返回 data=null */
    @GetMapping("/{account}")
    public R<LoginUserDTO> getUserByAccount(@PathVariable("account") String account) {
        return R.ok(linkageService.getLoginUserByAccount(account));
    }
}
```

- [ ] **Step 2: 构建 + 启动验证**

```bash
D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am -DskipTests
java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar   # 后台
curl http://localhost:9202/inner/user/admin
# 停止服务
```

Expected: `{"code":200,...,"data":{"userId":1,"account":"admin","password":"$2a$...","permissions":[...17 个非空权限标识...],"status":0}}`（19 菜单中 2 个目录 perms 为空已过滤，17 = 6 用户 + 5 角色 + 4 菜单 + 2 在线）。

- [ ] **Step 3: Commit**

```bash
git add cloud-base/cloud-system/
git commit -m "feat(cloud-system): /inner 用户登录聚合接口

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 7: 用户管理 CRUD + 分配角色（@PreAuthorize）

**Files:**
- Create: `cloud-base/cloud-system/src/main/java/com/cloudai/system/controller/SysUserController.java`
- Create: `.../service/SysUserManageService.java`
- Create: `.../dto/UserSaveRequest.java` `UserRoleRequest.java` `RoleMenuRequest.java`（供 Task 8 角色接口使用）

- [ ] **Step 1: DTO**

`UserSaveRequest.java`：

```java
package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 修改必填 */
    private Long id;
    /** 新增必填；修改忽略 */
    private String account;
    private String nickname;
    /** 新增必填（明文，服务端 BCrypt）；修改忽略 */
    private String password;
    /** 0正常 1停用 */
    private Integer status;
}
```

`UserRoleRequest.java`：

```java
package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class UserRoleRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private List<Long> roleIds;
}
```

`RoleMenuRequest.java`：

```java
package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class RoleMenuRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long roleId;
    private List<Long> menuIds;
}
```

- [ ] **Step 2: Service**

`SysUserManageService.java`：

```java
package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.entity.SysUserRole;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysUserManageService {

    private final SysUserMapper userMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final PasswordEncoder passwordEncoder;

    public PageResult<SysUser> page(PageQuery query) {
        Page<SysUser> page = userMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<SysUser>().orderByDesc(SysUser::getId));
        page.getRecords().forEach(u -> u.setPassword(null));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    public SysUser detail(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(3001, "用户不存在");
        }
        user.setPassword(null);
        return user;
    }

    @Transactional(rollbackFor = Exception.class)
    public Long add(UserSaveRequest req) {
        if (req.getAccount() == null || req.getAccount().isBlank()
                || req.getPassword() == null || req.getPassword().isBlank()) {
            throw new BusinessException("账号与密码不能为空");
        }
        Long exists = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getAccount, req.getAccount()));
        if (exists > 0) {
            throw new BusinessException(3002, "账号已存在: " + req.getAccount());
        }
        SysUser user = new SysUser();
        user.setAccount(req.getAccount());
        user.setNickname(req.getNickname() == null ? req.getAccount() : req.getNickname());
        user.setPassword(passwordEncoder.encode(req.getPassword()));
        user.setStatus(req.getStatus() == null ? 0 : req.getStatus());
        try {
            userMapper.insert(user);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 逻辑删除行仍占用 uk_account，selectCount 查重看不到——捕获兜底转业务异常
            throw new BusinessException(3002, "账号已存在: " + req.getAccount());
        }
        return user.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    public void edit(Long id, UserSaveRequest req) {
        SysUser user = requireUser(id);
        user.setNickname(req.getNickname());
        user.setStatus(req.getStatus());
        userMapper.updateById(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireUser(id);
        userMapper.deleteById(id);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(Long id, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new BusinessException("密码不能为空");
        }
        SysUser user = requireUser(id);
        user.setPassword(passwordEncoder.encode(newPassword));
        userMapper.updateById(user);
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignRoles(Long userId, List<Long> roleIds) {
        requireUser(userId);
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getUserId, userId));
        if (roleIds != null) {
            roleIds.forEach(roleId -> {
                SysUserRole ur = new SysUserRole();
                ur.setUserId(userId);
                ur.setRoleId(roleId);
                userRoleMapper.insert(ur);
            });
        }
    }

    public List<Long> roleIdsOf(Long userId) {
        return userRoleMapper.selectList(new LambdaQueryWrapper<SysUserRole>()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
    }

    private SysUser requireUser(Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null) {
            throw new BusinessException(3001, "用户不存在");
        }
        return user;
    }
}
```

- [ ] **Step 3: Controller**

```java
package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.UserRoleRequest;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.service.SysUserManageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserManageService manageService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<PageResult<SysUser>> page(PageQuery query) {
        return R.ok(manageService.page(query));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<SysUser> detail(@PathVariable Long id) {
        return R.ok(manageService.detail(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:user:add')")
    public R<Long> add(@RequestBody UserSaveRequest req) {
        return R.ok(manageService.add(req));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<Void> edit(@RequestBody UserSaveRequest req) {
        manageService.edit(req.getId(), req);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:remove')")
    public R<Void> remove(@PathVariable Long id) {
        manageService.remove(id);
        return R.ok();
    }

    @PutMapping("/password/{id}")
    @PreAuthorize("hasAuthority('system:user:resetPwd')")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        manageService.resetPassword(id, body.get("password"));
        return R.ok();
    }

    @PutMapping("/role")
    @PreAuthorize("hasAuthority('system:user:assignRole')")
    public R<Void> assignRoles(@RequestBody UserRoleRequest req) {
        manageService.assignRoles(req.getUserId(), req.getRoleIds());
        return R.ok();
    }

    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<List<Long>> roleIds(@PathVariable Long id) {
        return R.ok(manageService.roleIdsOf(id));
    }
}
```

- [ ] **Step 4: 构建验证 + 启动冒烟**

```bash
D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am -DskipTests
# 启动后直连（鉴权未装网关前的过渡期，本机直连可访问）：
curl "http://localhost:9202/user/page?pageNum=1&pageSize=10"
# 停止服务
```

Expected: `{"code":200,...,"data":{"total":"1","rows":[{...password 不出现...}]}}`（total 为字符串——Long→String 序列化生效）。

- [ ] **Step 5: Commit**

```bash
git add cloud-base/cloud-system/
git commit -m "feat(cloud-system): 用户管理CRUD与角色分配

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 8: 角色/菜单管理 CRUD + 菜单树（树构建 TDD）

**Files:**
- Create: `.../util/MenuTreeBuilder.java` + `dto/MenuTreeNode.java`
- Create: `.../service/SysRoleManageService.java` `SysMenuManageService.java`
- Create: `.../controller/SysRoleController.java` `SysMenuController.java`
- Test: `.../util/MenuTreeBuilderTest.java`

- [ ] **Step 1: 写失败测试 `MenuTreeBuilderTest.java`**

```java
package com.cloudai.system.util;

import com.cloudai.system.dto.MenuTreeNode;
import com.cloudai.system.entity.SysMenu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MenuTreeBuilderTest {

    private SysMenu menu(Long id, Long parentId, String name, int sort) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setParentId(parentId);
        m.setName(name);
        m.setSort(sort);
        return m;
    }

    @Test
    void buildsTreeByParentAndSort() {
        List<SysMenu> menus = List.of(
                menu(10L, 0L, "系统管理", 1),
                menu(12L, 10L, "角色管理", 2),
                menu(11L, 10L, "用户管理", 1),
                menu(111L, 11L, "用户新增", 1),
                menu(20L, 0L, "认证管理", 2));
        List<MenuTreeNode> tree = MenuTreeBuilder.build(menus);
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).getName()).isEqualTo("系统管理");
        assertThat(tree.get(0).getChildren()).extracting(MenuTreeNode::getName)
                .containsExactly("用户管理", "角色管理");
        assertThat(tree.get(0).getChildren().get(0).getChildren())
                .extracting(MenuTreeNode::getName).containsExactly("用户新增");
    }

    @Test
    void orphanNodeAttachedToRootLevel() {
        List<SysMenu> menus = List.of(menu(999L, 888L, "孤儿", 0));
        List<MenuTreeNode> tree = MenuTreeBuilder.build(menus);
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getName()).isEqualTo("孤儿");
    }
}
```

- [ ] **Step 2: 运行确认失败**（类不存在编译错误）

- [ ] **Step 3: 实现 `MenuTreeNode.java`**

```java
package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
public class MenuTreeNode implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long parentId;
    private String name;
    private String perms;
    private String type;
    private Integer sort;
    private List<MenuTreeNode> children = new ArrayList<>();
}
```

- [ ] **Step 4: 实现 `MenuTreeBuilder.java`**

```java
package com.cloudai.system.util;

import com.cloudai.system.dto.MenuTreeNode;
import com.cloudai.system.entity.SysMenu;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单树构建：按 parentId 组装、sort 升序；父节点缺失的孤儿挂到根级。
 */
public final class MenuTreeBuilder {

    private MenuTreeBuilder() {
    }

    public static List<MenuTreeNode> build(List<SysMenu> menus) {
        Map<Long, List<MenuTreeNode>> byParent = menus.stream()
                .map(MenuTreeBuilder::toNode)
                .collect(Collectors.groupingBy(MenuTreeNode::getParentId));
        List<MenuTreeNode> roots = new java.util.ArrayList<>(byParent.getOrDefault(0L, List.of()));
        // 孤儿节点（父不存在于集合中）也挂到根级，避免数据问题导致菜单消失
        menus.stream().map(SysMenu::getParentId).distinct()
                .filter(pid -> pid != 0 && menus.stream().noneMatch(m -> m.getId().equals(pid)))
                .forEach(pid -> roots.addAll(byParent.getOrDefault(pid, List.of())));
        roots.addAll(byParent.getOrDefault(null, List.of()));
        roots.sort(Comparator.comparing(MenuTreeNode::getSort,
                Comparator.nullsLast(Comparator.naturalOrder())));
        byParent.values().forEach(children -> children.sort(Comparator
                .comparing(MenuTreeNode::getSort, Comparator.nullsLast(Comparator.naturalOrder()))));
        roots.forEach(root -> fillChildren(root, byParent));
        return roots;
    }

    private static void fillChildren(MenuTreeNode node, Map<Long, List<MenuTreeNode>> byParent) {
        node.setChildren(new java.util.ArrayList<>(byParent.getOrDefault(node.getId(), List.of())));
        node.getChildren().forEach(child -> fillChildren(child, byParent));
    }

    private static MenuTreeNode toNode(SysMenu m) {
        MenuTreeNode node = new MenuTreeNode();
        node.setId(m.getId());
        node.setParentId(m.getParentId());
        node.setName(m.getName());
        node.setPerms(m.getPerms());
        node.setType(m.getType());
        node.setSort(m.getSort());
        return node;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml test -pl cloud-system -Dtest=MenuTreeBuilderTest`
Expected: `Tests run: 2, Failures: 0`。

- [ ] **Step 6: 实现 `SysRoleManageService.java`**

```java
package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysRoleMenu;
import com.cloudai.system.entity.SysUserRole;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysRoleManageService {

    private final SysRoleMapper roleMapper;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;

    public PageResult<SysRole> page(PageQuery query) {
        Page<SysRole> page = roleMapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()),
                new LambdaQueryWrapper<SysRole>().orderByDesc(SysRole::getId));
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    public List<SysRole> listAll() {
        return roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getStatus, 0).orderByAsc(SysRole::getId));
    }

    public Long add(SysRole role) {
        if (role.getRoleKey() == null || role.getRoleKey().isBlank()) {
            throw new BusinessException("角色标识不能为空");
        }
        Long exists = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleKey, role.getRoleKey()));
        if (exists > 0) {
            throw new BusinessException(3003, "角色标识已存在: " + role.getRoleKey());
        }
        try {
            roleMapper.insert(role);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new BusinessException(3003, "角色标识已存在: " + role.getRoleKey());
        }
        return role.getId();
    }

    public void edit(SysRole role) {
        requireRole(role.getId());
        roleMapper.updateById(role);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireRole(id);
        roleMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, id));
        userRoleMapper.delete(new LambdaQueryWrapper<SysUserRole>().eq(SysUserRole::getRoleId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void assignMenus(Long roleId, List<Long> menuIds) {
        requireRole(roleId);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getRoleId, roleId));
        if (menuIds != null) {
            menuIds.forEach(menuId -> {
                SysRoleMenu rm = new SysRoleMenu();
                rm.setRoleId(roleId);
                rm.setMenuId(menuId);
                roleMenuMapper.insert(rm);
            });
        }
    }

    public List<Long> menuIdsOf(Long roleId) {
        return roleMenuMapper.selectList(new LambdaQueryWrapper<SysRoleMenu>()
                        .eq(SysRoleMenu::getRoleId, roleId))
                .stream().map(SysRoleMenu::getMenuId).toList();
    }

    private SysRole requireRole(Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null) {
            throw new BusinessException(3004, "角色不存在");
        }
        return role;
    }
}
```

- [ ] **Step 7: 实现 `SysMenuManageService.java`**

```java
package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.mapper.SysMenuMapper;
import com.cloudai.system.mapper.SysRoleMenuMapper;
import com.cloudai.system.entity.SysRoleMenu;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class SysMenuManageService {

    private final SysMenuMapper menuMapper;
    private final SysRoleMenuMapper roleMenuMapper;

    public List<SysMenu> listAll() {
        return menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getSort));
    }

    public Long add(SysMenu menu) {
        if (menu.getName() == null || menu.getName().isBlank()) {
            throw new BusinessException("菜单名称不能为空");
        }
        menuMapper.insert(menu);
        return menu.getId();
    }

    public void edit(SysMenu menu) {
        requireMenu(menu.getId());
        menuMapper.updateById(menu);
    }

    @Transactional(rollbackFor = Exception.class)
    public void remove(Long id) {
        requireMenu(id);
        Long childCount = menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getParentId, id));
        if (childCount > 0) {
            throw new BusinessException(3005, "存在子菜单，先删除子级");
        }
        menuMapper.deleteById(id);
        roleMenuMapper.delete(new LambdaQueryWrapper<SysRoleMenu>().eq(SysRoleMenu::getMenuId, id));
    }

    private SysMenu requireMenu(Long id) {
        SysMenu menu = menuMapper.selectById(id);
        if (menu == null) {
            throw new BusinessException(3006, "菜单不存在");
        }
        return menu;
    }
}
```

- [ ] **Step 8: 实现两个 Controller**

`SysRoleController.java`：

```java
package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.service.SysRoleManageService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleManageService manageService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<PageResult<SysRole>> page(PageQuery query) {
        return R.ok(manageService.page(query));
    }

    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<List<SysRole>> list() {
        return R.ok(manageService.listAll());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:role:add')")
    public R<Long> add(@RequestBody SysRole role) {
        return R.ok(manageService.add(role));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('system:role:edit')")
    public R<Void> edit(@RequestBody SysRole role) {
        manageService.edit(role);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:remove')")
    public R<Void> remove(@PathVariable Long id) {
        manageService.remove(id);
        return R.ok();
    }

    @PutMapping("/menu")
    @PreAuthorize("hasAuthority('system:role:assignMenu')")
    public R<Void> assignMenus(@RequestBody RoleMenuRequest req) {
        manageService.assignMenus(req.getRoleId(), req.getMenuIds());
        return R.ok();
    }

    @GetMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<List<Long>> menuIds(@PathVariable Long id) {
        return R.ok(manageService.menuIdsOf(id));
    }
}
```

`SysMenuController.java`：

```java
package com.cloudai.system.controller;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.MenuTreeNode;
import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.service.SysMenuManageService;
import com.cloudai.system.util.MenuTreeBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuManageService manageService;

    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public R<List<MenuTreeNode>> tree() {
        return R.ok(MenuTreeBuilder.build(manageService.listAll()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:add')")
    public R<Long> add(@RequestBody SysMenu menu) {
        return R.ok(manageService.add(menu));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('system:menu:edit')")
    public R<Void> edit(@RequestBody SysMenu menu) {
        manageService.edit(menu);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:remove')")
    public R<Void> remove(@PathVariable Long id) {
        manageService.remove(id);
        return R.ok();
    }
}
```

- [ ] **Step 9: 构建验证**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-system -am`
Expected: BUILD SUCCESS，MenuTreeBuilderTest 2 个测试通过。

- [ ] **Step 10: Commit**

```bash
git add cloud-base/cloud-system/
git commit -m "feat(cloud-system): 角色菜单管理CRUD与菜单树

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 9: sso 登录/令牌/在线管理（Feign + TokenService，核心分支 TDD）

**Files:**
- Modify: `cloud-base/cloud-common/cloud-common-redis-starter/src/main/java/com/cloudai/common/redis/util/RedisUtil.java`（+keys 方法）
- Test: `cloud-base/cloud-common/cloud-common-redis-starter/src/test/java/com/cloudai/common/redis/util/RedisUtilKeysTest.java`（连本机真实 Redis 的集成单测）
- Modify: `cloud-base/cloud-sso/pom.xml` `application.yml` `SsoApplication.java`
- Create: sso 下 `client/SystemUserClient.java` `client/SystemUserClientFallback.java` `dto/LoginUserDTO.java` `dto/LoginRequest.java` `dto/RefreshRequest.java` `dto/LoginResult.java` `domain/OnlineSession.java` `service/TokenService.java` `controller/AuthController.java`
- Test: `service/TokenServiceTest.java`

- [ ] **Step 1: RedisUtil 加 keys 方法（含集成测试）**

`RedisUtil.java` 追加：

```java
    /** 按模式取键名集合（在线列表等小规模扫描用；键量大时换 SCAN） */
    public java.util.Set<String> keys(String pattern) {
        return redisTemplate.keys(pattern);
    }
```

`RedisUtilKeysTest.java`（本机 Redis 集成验证，Redis 未运行时跳过）：

```java
package com.cloudai.common.redis.util;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RedisUtilKeysTest {

    @Test
    void keysReturnsMatchingKeys() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", 6379);
        factory.afterPropertiesSet();
        try {
            RedisTemplate<String, Object> template =
                    new com.cloudai.common.redis.config.CommonRedisAutoConfiguration().redisTemplate(factory);
            RedisUtil redis = new RedisUtil(template);
            Assumptions.assumeTrue(Boolean.TRUE.equals(factory.getConnection().ping()), "本机 Redis 未运行，跳过");
            redis.set("test:keys:a", "1");
            redis.set("test:keys:b", "2");
            Set<String> keys = redis.keys("test:keys:*");
            assertThat(keys).contains("test:keys:a", "test:keys:b");
            redis.delete("test:keys:a");
            redis.delete("test:keys:b");
        } finally {
            factory.destroy();
        }
    }
}
```

验证：`D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml test -pl cloud-common/cloud-common-redis-starter` → `Tests run: 2`（原 1 + 本 1）。提交本步（`feat(common-redis-starter): RedisUtil增加keys扫描`）。

- [ ] **Step 2: sso pom 增加依赖**

```xml
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-security-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-redis-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-loadbalancer</artifactId>
        </dependency>
```

- [ ] **Step 3: application.yml 的 config.import 改为多 dataId**（cloud-common.yaml 提供共享 jwt 配置）

```yaml
  config:
    import:
      - optional:nacos:cloud-common.yaml
      - optional:nacos:${spring.application.name}.yaml
```

- [ ] **Step 4: SsoApplication 加 @EnableFeignClients**

```java
package com.cloudai.sso;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
public class SsoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SsoApplication.class, args);
    }
}
```

- [ ] **Step 5: sso 侧 DTO 与 Feign 客户端**

`dto/LoginUserDTO.java`（字段与 system 的同名 DTO 逐字一致——Feign 反序列化契约）：

```java
package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class LoginUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String account;
    private String nickname;
    private String password;
    private List<String> permissions;
    private Integer status;
}
```

`client/SystemUserClient.java`：

```java
package com.cloudai.sso.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.sso.dto.LoginUserDTO;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * cloud-system 内部用户接口客户端
 */
@FeignClient(name = "cloud-system", contextId = "systemUserClient",
        path = "/inner/user", fallback = SystemUserClientFallback.class)
public interface SystemUserClient {

    @GetMapping("/{account}")
    R<LoginUserDTO> getUserByAccount(@PathVariable("account") String account);
}
```

`client/SystemUserClientFallback.java`：

```java
package com.cloudai.sso.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.sso.dto.LoginUserDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SystemUserClientFallback implements SystemUserClient {

    @Override
    public R<LoginUserDTO> getUserByAccount(String account) {
        log.error("cloud-system 用户服务不可用，account={}", account);
        return R.fail(2002, "用户服务不可用，请稍后重试");
    }
}
```

`dto/LoginRequest.java` / `dto/RefreshRequest.java` / `dto/LoginResult.java`：

```java
package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class LoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String account;
    private String password;
}
```

```java
package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class RefreshRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String refreshToken;
}
```

```java
package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class LoginResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accessToken;
    private String refreshToken;
    /** access_token 有效期（秒） */
    private Long expiresIn;
}
```

- [ ] **Step 6: `domain/OnlineSession.java`**（时间用 epochMillis——LocalDateTime 不可 JSON 序列化，阶段 1 备忘）

```java
package com.cloudai.sso.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 在线会话（存 Redis：sso:online:{tokenId}，value 为本对象 JSON）
 */
@Data
public class OnlineSession implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tokenId;
    private Long userId;
    private String account;
    private List<String> permissions;
    /** 登录时间（epoch millis） */
    private Long loginTime;
    /** 登录 IP */
    private String ip;
}
```

- [ ] **Step 7: 写失败测试 `TokenServiceTest.java`**（纯 Mockito，覆盖核心分支）

```java
package com.cloudai.sso.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.redis.util.RedisUtil;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.sso.client.SystemUserClient;
import com.cloudai.sso.domain.OnlineSession;
import com.cloudai.sso.dto.LoginUserDTO;
import com.cloudai.sso.dto.LoginResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private SystemUserClient userClient;
    @Mock
    private RedisUtil redisUtil;
    @Spy
    private PasswordEncoder passwordEncoder = new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();
    @InjectMocks
    private TokenService tokenService;

    private LoginUserDTO admin() {
        LoginUserDTO dto = new LoginUserDTO();
        dto.setUserId(1L);
        dto.setAccount("admin");
        dto.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder().encode("admin123"));
        dto.setPermissions(List.of("system:user:list"));
        dto.setStatus(0);
        return dto;
    }

    @Test
    void login_successIssuesTokensAndWritesRedis() {
        when(userClient.getUserByAccount("admin")).thenReturn(com.cloudai.common.core.domain.R.ok(admin()));
        LoginResult result = tokenService.login("admin", "admin123", "127.0.0.1");
        assertThat(result.getAccessToken()).isNotBlank();
        assertThat(result.getRefreshToken()).isNotBlank();
        assertThat(result.getExpiresIn()).isEqualTo(7200L);
        verify(redisUtil).set(startsWith(SecurityConstants.ONLINE_KEY_PREFIX), any(OnlineSession.class), anyLong(), any());
        verify(redisUtil).set(startsWith("sso:refresh:"), anyString(), anyLong(), any());
    }

    @Test
    void login_unknownAccountThrows() {
        when(userClient.getUserByAccount("ghost")).thenReturn(com.cloudai.common.core.domain.R.ok(null));
        assertThatThrownBy(() -> tokenService.login("ghost", "x", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号或密码错误");
    }

    @Test
    void login_wrongPasswordThrows() {
        when(userClient.getUserByAccount("admin")).thenReturn(com.cloudai.common.core.domain.R.ok(admin()));
        assertThatThrownBy(() -> tokenService.login("admin", "wrong", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("账号或密码错误");
    }

    @Test
    void login_disabledUserThrows() {
        LoginUserDTO dto = admin();
        dto.setStatus(1);
        when(userClient.getUserByAccount("admin")).thenReturn(com.cloudai.common.core.domain.R.ok(dto));
        assertThatThrownBy(() -> tokenService.login("admin", "admin123", "127.0.0.1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已停用");
    }

    @Test
    void logout_removesOnlineAndRefresh() throws Exception {
        java.lang.reflect.Field secretField = TokenService.class.getDeclaredField("secret");
        secretField.setAccessible(true);
        secretField.set(tokenService, TEST_SECRET);
        String token = com.cloudai.common.security.util.JwtUtil.createToken(TEST_SECRET, 1L, "admin", "jti-1", 7200);
        tokenService.logout(token);
        verify(redisUtil).delete("sso:online:jti-1");
        verify(redisUtil).delete("sso:refresh:1");
    }

    private static final String TEST_SECRET =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
}
```

- [ ] **Step 8: 实现 `TokenService.java`**

```java
package com.cloudai.sso.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.redis.util.RedisUtil;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.util.JwtUtil;
import com.cloudai.sso.client.SystemUserClient;
import com.cloudai.sso.domain.OnlineSession;
import com.cloudai.sso.dto.LoginResult;
import com.cloudai.sso.dto.LoginUserDTO;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String REFRESH_KEY_PREFIX = "sso:refresh:";

    private final SystemUserClient userClient;
    private final RedisUtil redisUtil;
    private final PasswordEncoder passwordEncoder;

    @Value("${cloud.jwt.secret}")
    private String secret;

    @Value("${cloud.jwt.access-token-ttl:7200}")
    private long accessTtlSeconds;

    @Value("${cloud.jwt.refresh-token-ttl:604800}")
    private long refreshTtlSeconds;

    public LoginResult login(String account, String password, String ip) {
        R<LoginUserDTO> resp = userClient.getUserByAccount(account);
        if (resp == null || resp.getCode() != 200) {
            throw new BusinessException(resp == null ? 2002 : resp.getCode(),
                    resp == null ? "用户服务不可用" : resp.getMsg());
        }
        LoginUserDTO dto = resp.getData();
        if (dto == null || !passwordEncoder.matches(password, dto.getPassword())) {
            throw new BusinessException(2001, "账号或密码错误");
        }
        if (dto.getStatus() == null || dto.getStatus() != 0) {
            throw new BusinessException(2003, "账号已停用");
        }
        return issueTokens(dto, ip);
    }

    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(2004, "refreshToken 不能为空");
        }
        Long userId = null;
        String stored = null;
        for (String key : redisUtil.keys(REFRESH_KEY_PREFIX + "*")) {
            String value = redisUtil.get(key);
            if (refreshToken.equals(value)) {
                userId = Long.valueOf(key.substring(REFRESH_KEY_PREFIX.length()));
                stored = value;
                break;
            }
        }
        if (userId == null || stored == null) {
            throw new BusinessException(2005, "refreshToken 无效或已过期");
        }
        // 通过在线会话取回用户信息重新签发（权限以登录时快照为准）
        OnlineSession session = findOnlineSessionByUserId(userId);
        if (session == null) {
            throw new BusinessException(2005, "会话已失效，请重新登录");
        }
        redisUtil.delete(SecurityConstants.ONLINE_KEY_PREFIX + session.getTokenId());
        LoginUserDTO dto = new LoginUserDTO();
        dto.setUserId(session.getUserId());
        dto.setAccount(session.getAccount());
        dto.setNickname(session.getAccount());
        dto.setPassword("");
        dto.setPermissions(session.getPermissions());
        dto.setStatus(0);
        LoginResult result = issueTokens(dto, session.getIp());
        redisUtil.delete(REFRESH_KEY_PREFIX + userId);
        return result;
    }

    public void logout(String accessToken) {
        Claims claims = JwtUtil.parseToken(secret, accessToken);
        String tokenId = claims.getId();
        Long userId = Long.valueOf(claims.getSubject());
        redisUtil.delete(SecurityConstants.ONLINE_KEY_PREFIX + tokenId);
        redisUtil.delete(REFRESH_KEY_PREFIX + userId);
    }

    public List<OnlineSession> onlineList() {
        List<OnlineSession> list = new ArrayList<>();
        Set<String> keys = redisUtil.keys(SecurityConstants.ONLINE_KEY_PREFIX + "*");
        if (keys != null) {
            keys.forEach(key -> {
                OnlineSession session = redisUtil.get(key);
                if (session != null) {
                    list.add(session);
                }
            });
        }
        return list;
    }

    public void kick(String tokenId) {
        redisUtil.delete(SecurityConstants.ONLINE_KEY_PREFIX + tokenId);
    }

    private LoginResult issueTokens(LoginUserDTO dto, String ip) {
        String tokenId = UUID.randomUUID().toString();
        String accessToken = JwtUtil.createToken(secret, dto.getUserId(), dto.getAccount(), tokenId, accessTtlSeconds);
        String refreshToken = UUID.randomUUID().toString();

        OnlineSession session = new OnlineSession();
        session.setTokenId(tokenId);
        session.setUserId(dto.getUserId());
        session.setAccount(dto.getAccount());
        session.setPermissions(dto.getPermissions());
        session.setLoginTime(System.currentTimeMillis());
        session.setIp(ip);
        redisUtil.set(SecurityConstants.ONLINE_KEY_PREFIX + tokenId, session, accessTtlSeconds, TimeUnit.SECONDS);
        redisUtil.set(REFRESH_KEY_PREFIX + dto.getUserId(), refreshToken, refreshTtlSeconds, TimeUnit.SECONDS);

        LoginResult result = new LoginResult();
        result.setAccessToken(accessToken);
        result.setRefreshToken(refreshToken);
        result.setExpiresIn(accessTtlSeconds);
        return result;
    }

    private OnlineSession findOnlineSessionByUserId(Long userId) {
        Set<String> keys = redisUtil.keys(SecurityConstants.ONLINE_KEY_PREFIX + "*");
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            OnlineSession session = redisUtil.get(key);
            if (session != null && userId.equals(session.getUserId())) {
                return session;
            }
        }
        return null;
    }
}
```

- [ ] **Step 9: 实现 `AuthController.java`**

```java
package com.cloudai.sso.controller;

import com.cloudai.common.core.domain.R;
import com.cloudai.sso.domain.OnlineSession;
import com.cloudai.sso.dto.LoginRequest;
import com.cloudai.sso.dto.LoginResult;
import com.cloudai.sso.dto.RefreshRequest;
import com.cloudai.sso.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    @PostMapping("/login")
    public R<LoginResult> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        return R.ok(tokenService.login(req.getAccount(), req.getPassword(), http.getRemoteAddr()));
    }

    @PostMapping("/refresh")
    public R<LoginResult> refresh(@RequestBody RefreshRequest req) {
        return R.ok(tokenService.refresh(req.getRefreshToken()));
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader("Authorization") String authorization) {
        tokenService.logout(stripBearer(authorization));
        return R.ok();
    }

    @GetMapping("/online")
    @PreAuthorize("hasAuthority('sso:online:list')")
    public R<List<OnlineSession>> online() {
        return R.ok(tokenService.onlineList());
    }

    @DeleteMapping("/online/{tokenId}")
    @PreAuthorize("hasAuthority('sso:online:kick')")
    public R<Void> kick(@PathVariable String tokenId) {
        tokenService.kick(tokenId);
        return R.ok();
    }

    private String stripBearer(String authorization) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return authorization;
    }
}
```

- [ ] **Step 10: 运行测试与构建**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-sso -am`
Expected: BUILD SUCCESS，TokenServiceTest 分支测试全绿（logout 测试按实现改写后 ≥5 个）。

- [ ] **Step 11: 启动冒烟（需 system 在跑）**

后台启动 system 与 sso，然后：

```bash
curl -s -X POST http://localhost:9201/auth/login -H "Content-Type: application/json" -d '{"account":"admin","password":"admin123"}'
```

Expected: `{"code":200,...,"data":{"accessToken":"eyJ...","refreshToken":"...","expiresIn":7200}}`。验证后停两个服务。

- [ ] **Step 12: Commit**

```bash
git add cloud-base/
git commit -m "feat(cloud-sso): 账密登录/双token/注销/在线管理

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 10: 网关鉴权过滤器 + /inner 屏蔽 + 401 JSON

**Files:**
- Modify: `cloud-base/cloud-gateway/pom.xml`
- Modify: `cloud-base/cloud-gateway/src/main/resources/application.yml`
- Create: `cloud-base/cloud-gateway/src/main/java/com/cloudai/gateway/filter/AuthGlobalFilter.java`

- [ ] **Step 1: pom 增加依赖**

```xml
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-core-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-security-starter</artifactId>
        </dependency>
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-redis-starter</artifactId>
        </dependency>
```

（security-starter 的安全自动配置有 @ConditionalOnWebApplication(SERVLET)，网关 WebFlux 下自动退避，仅用到 JwtUtil/常量。）

- [ ] **Step 2: application.yml 修改**

`config.import` 改为多 dataId：

```yaml
  config:
    import:
      - optional:nacos:cloud-common.yaml
      - optional:nacos:${spring.application.name}.yaml
```

routes 段**最前**（三条路由之前）追加 /inner 屏蔽路由：

```yaml
        - id: inner-block-sso
          uri: no://op
          predicates:
            - Path=/sso/inner/**
          filters:
            - SetStatus=403
        - id: inner-block-system
          uri: no://op
          predicates:
            - Path=/system/inner/**
          filters:
            - SetStatus=403
        - id: inner-block-bpmn
          uri: no://op
          predicates:
            - Path=/bpmn/inner/**
          filters:
            - SetStatus=403
```

- [ ] **Step 3: 实现 `AuthGlobalFilter.java`**

```java
package com.cloudai.gateway.filter;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.redis.util.RedisUtil;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.util.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 网关鉴权：白名单放行；其余验签 JWT + Redis 在线检查（注销/强退立即生效）；
 * 通过后剥离外部 X-User-* 伪造 header 并注入真实用户信息透传下游。
 * 401 返回统一 R JSON（网关层使用真实 HTTP 401 状态，与服务层 HTTP200+body.code 约定并存）。
 * 注：Redis 为同步调用（内网低延迟，MVP 取舍；高并发场景改 reactive 或本地缓存）。
 */
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 前缀白名单：登录/刷新、demo、文档、监控 */
    private static final List<String> WHITELIST = List.of(
            "/sso/auth/login", "/sso/auth/refresh",
            "/sso/demo", "/system/demo", "/bpmn/demo",
            "/actuator", "/v3/api-docs", "/swagger-ui", "/webjars", "/doc");

    private final RedisUtil redisUtil;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${cloud.jwt.secret}")
    private String secret;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        // 白名单：剥离外部 X-User-* 后放行
        ServerWebExchange safeExchange = stripUserHeaders(exchange);
        if (WHITELIST.stream().anyMatch(path::startsWith)) {
            return chain.filter(safeExchange);
        }
        String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange);
        }
        Claims claims;
        try {
            claims = JwtUtil.parseToken(secret, authorization.substring(BEARER_PREFIX.length()));
        } catch (JwtException e) {
            return unauthorized(exchange);
        }
        String tokenId = claims.getId();
        if (!Boolean.TRUE.equals(redisUtil.hasKey(SecurityConstants.ONLINE_KEY_PREFIX + tokenId))) {
            return unauthorized(exchange);
        }
        OnlineHolder session = redisUtil.get(SecurityConstants.ONLINE_KEY_PREFIX + tokenId);
        List<String> perms = (session == null || session.getPermissions() == null)
                ? List.of() : session.getPermissions();
        ServerWebExchange authed = withUserHeaders(safeExchange,
                claims.getSubject(), claims.get("account", String.class), String.join(",", perms));
        return chain.filter(authed);
    }

    private ServerWebExchange stripUserHeaders(ServerWebExchange exchange) {
        return exchange.mutate().request(builder -> builder
                .headers(headers -> {
                    headers.remove(SecurityConstants.HEADER_USER_ID);
                    headers.remove(SecurityConstants.HEADER_USER_ACCOUNT);
                    headers.remove(SecurityConstants.HEADER_USER_PERMS);
                })).build();
    }

    private ServerWebExchange withUserHeaders(ServerWebExchange exchange, String userId,
                                              String account, String perms) {
        return exchange.mutate().request(builder -> builder
                .headers(headers -> {
                    headers.set(SecurityConstants.HEADER_USER_ID, userId);
                    headers.set(SecurityConstants.HEADER_USER_ACCOUNT, account == null ? "" : account);
                    headers.set(SecurityConstants.HEADER_USER_PERMS, perms);
                })).build();
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(R.fail(401, "认证失败或未登录"));
        } catch (Exception e) {
            body = "{\"code\":401,\"msg\":\"认证失败或未登录\"}".getBytes(StandardCharsets.UTF_8);
        }
        DataBuffer buffer = response.bufferFactory().wrap(body);
        return response.writeWith(Mono.just(buffer));
    }

    @Override
    public int getOrder() {
        return -100;
    }

    /** 网关侧在线会话投影（只取权限字段，字段名与 sso 的 OnlineSession 对齐） */
    @lombok.Data
    public static class OnlineHolder {
        private List<String> permissions;
    }
}
```

（若 Jackson 反序列化 OnlineHolder 因 @class 类型头失败——GenericJackson2JsonRedisSerializer 存的是 sso.OnlineSession 类型——则改为以 `Object` 读取后 objectMapper.convertValue，或网关不读会话值、仅校验 hasKey、X-User-Perms 留空由下游 @PreAuthorize 拒绝。以实测为准，把最终采用方案写进提交说明。）

- [ ] **Step 4: 构建**

Run: `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-gateway -am`
Expected: BUILD SUCCESS。

- [ ] **Step 5: Commit**

```bash
git add cloud-base/cloud-gateway/
git commit -m "feat(cloud-gateway): JWT鉴权过滤器（白名单/验签/在线检查/用户透传）与inner屏蔽

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 11: bpmn 装配安全依赖 + 全链路权限验证

**Files:**
- Modify: `cloud-base/cloud-bpmn/pom.xml`

- [ ] **Step 1: bpmn pom 增加依赖**（cloud-common-core-starter 之后）

```xml
        <dependency>
            <groupId>com.cloudai</groupId>
            <artifactId>cloud-common-security-starter</artifactId>
        </dependency>
```

- [ ] **Step 2: 构建 + 启动三服务与网关（system/sso/gateway/bpmn），执行权限链路验证**

```bash
D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install -pl cloud-bpmn -am -DskipTests
# java -jar 后台启动 system、sso、bpmn、gateway（记录 PID）
# 1) 无 token 访问受保护接口
curl -s -i http://localhost:18080/system/user/page | head -5
#    Expected: HTTP/1.1 401 Unauthorized + R JSON body
# 2) 登录
TOKEN=$(curl -s -X POST http://localhost:18080/sso/auth/login -H "Content-Type: application/json" \
  -d '{"account":"admin","password":"admin123"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
echo "TOKEN=${TOKEN:0:30}..."
# 3) 带 token 访问（admin 有全部权限）
curl -s -H "Authorization: Bearer $TOKEN" "http://localhost:18080/system/user/page?pageNum=1&pageSize=10"
#    Expected: code 200 + 分页数据（total="1"）
# 4) 建无权限用户并登录验证 403
curl -s -X POST http://localhost:18080/system/user -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{"account":"noperm","nickname":"无权限","password":"123456"}'
TEST_TOKEN=$(curl -s -X POST http://localhost:18080/sso/auth/login -H "Content-Type: application/json" \
  -d '{"account":"noperm","password":"123456"}' | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
curl -s -H "Authorization: Bearer $TEST_TOKEN" "http://localhost:18080/system/user/page?pageNum=1&pageSize=10"
#    Expected: {"code":403,"msg":"无操作权限",...}（HTTP 200）
# 5) inner 屏蔽
curl -s -o /dev/null -w "%{http_code}" http://localhost:18080/system/inner/user/admin
#    Expected: 403
# 6) demo 白名单仍匿名可达
curl -s http://localhost:18080/system/demo/ping
#    Expected: {"code":200,...,"data":"cloud-system running"}
# 停止全部 4 个服务（taskkill），确认端口释放
```

- [ ] **Step 3: Commit**

```bash
git add cloud-base/cloud-bpmn/
git commit -m "feat(cloud-bpmn): 装配资源端安全依赖

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## Task 12: 端到端验收（注销失效/刷新/在线强退）

前置：4 服务 java -jar 后台启动（system/sso/bpmn/gateway），记录 PID。

- [ ] **Step 1: 注销后 token 立即失效**

```bash
TOKEN=$(登录 admin 得到 accessToken)
curl -s -X POST http://localhost:18080/sso/auth/logout -H "Authorization: Bearer $TOKEN"
# Expected: {"code":200,...}
curl -s -o /dev/null -w "%{http_code}" -H "Authorization: Bearer $TOKEN" http://localhost:18080/system/user/page
# Expected: 401（Redis 在线记录已删）
```

- [ ] **Step 2: 刷新令牌**

```bash
# 重新登录得 accessToken/refreshToken
REFRESH=$(... 提取 refreshToken)
curl -s -X POST http://localhost:18080/sso/auth/refresh -H "Content-Type: application/json" -d "{\"refreshToken\":\"$REFRESH\"}"
# Expected: 新 accessToken/refreshToken；用新 token 访问 /system/user/page → 200；旧 token → 401
```

- [ ] **Step 3: 在线列表与强退**

```bash
ADMIN_TOKEN=$(登录 admin)
# 建一个测试账号 testperm/123456 并登录得 TEST_TOKEN；从登录响应无法直接拿 tokenId（jti），
# 从在线列表取：
curl -s -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:18080/sso/auth/online
# Expected: 含 admin 与 testperm 两条会话（tokenId/account/permissions/loginTime/ip）
# 提取 testperm 的 tokenId：
KICK_ID=$(... jq/python 或 sed 提取 account=testperm 那条的 tokenId)
curl -s -X DELETE -H "Authorization: Bearer $ADMIN_TOKEN" http://localhost:18080/sso/auth/online/$KICK_ID
# Expected: code 200；随后用 TEST_TOKEN 访问 → 401
# 用 testperm（无 sso:online:list 权限）调在线列表：
curl -s -H "Authorization: Bearer $TEST_TOKEN" http://localhost:18080/sso/auth/online
# Expected: {"code":403,...}
```

- [ ] **Step 4: 审计字段联动验证**

管理员新增用户后查库：`SELECT account, create_by, create_time FROM sys_user WHERE account='testperm';`
Expected: create_by='admin'、create_time 非空（网关透传→HeaderAuthFilter→审计填充全链路生效）。

- [ ] **Step 5: 停止全部服务，确认 9201/9202/9203/18080 释放；无需提交（纯验收）**

---

## Task 13: 文档更新与阶段状态

**Files:**
- Modify: `cloud-base/README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-04-cloud-base-backend-design.md`（§9 范围精简记录）

- [ ] **Step 1: README 更新**：模块表 sso/system 说明去掉"（阶段N实现）"；阶段状态勾选阶段 2/3；新增"登录与鉴权"小节（curl 登录示例 + token 用法 + 白名单清单 + /inner 屏蔽说明）；技术栈行更正 MySQL 为 5.7+（本机 5.7.24）。

- [ ] **Step 2: CLAUDE.md 更新**：架构拓扑标注 security-starter 的资源端装配（servlet-only）、sso→system Feign 链路、Redis 键（sso:online:/sso:refresh:）；关键约定补：网关层 401 用真实 HTTP 状态、服务层 403 走 HTTP200+body、@PreAuthorize 权限标识清单来源 sys_menu.perms；环境补：MySQL root/空密码 127.0.0.1:3306、jshell 执行 SQL 方法。

- [ ] **Step 3: 设计文档 §9 附加精简记录**：注明 2026-10-05 按"最小闭环"执行——验证码/登录日志/部门/岗位/字典/参数/操作日志未做，列后续扩展清单。

- [ ] **Step 4: 全量构建 + 提交**

```bash
D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml clean install
git add cloud-base/README.md CLAUDE.md docs/
git commit -m "docs: 阶段2+3完成——权限管理与登录链路文档更新

Co-Authored-By: Claude Code <noreply@anthropic.com>"
```

---

## 计划自检记录（写计划时核对）

1. **Spec 覆盖**：RBAC 5 表（T2）、CRUD+分配（T7/T8）、登录聚合/inner（T5/T6）、账密登录双 token（T9）、网关鉴权+inner 屏蔽（T10）、@PreAuthorize 全链路（T3/T11）、注销/刷新/强退（T9/T12）、审计联动（T4/T12）。设计 §9 未做项已在范围精简声明。
2. **类型一致性**：LoginUser（security-starter）/LoginUserDTO（system 与 sso 两份同构）/OnlineSession（sso）字段对齐；SecurityConstants 常量为网关与资源端唯一来源；R.fail(401/403/2001-2005/3001-3006) 错误码分段符合设计（2xxx 认证/3xxx system）。
3. **已知执行期决策点（留给实现者，需在提交说明记录）**：网关读取 OnlineSession 的反序列化方案（T10 Step 3 注——@class 类型头与 OnlineHolder 的兼容性以实测为准）。
4. **风险预案**：H2 未引入，CRUD 逻辑靠 T11/T12 端到端验证；MySQL 连接失败先检查 3306 进程与空密码；Feign 首调超时注意 loadbalancer 缓存预热。

## 执行完成后

验收通过后合并回 main；阶段 4（cloud-bpmn + Flowable）按既有流程另立计划，开工前重读阶段 1 计划的"移交备忘"与本计划新增记录。

