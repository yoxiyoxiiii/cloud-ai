# cloud-base 后端聚合工程

企业应用基座平台：Spring Cloud Alibaba 微服务体系。

## 技术栈

JDK 17 / Spring Boot 3.3.4 / Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.3（Nacos）
MyBatis-Plus 3.5.7 / MySQL 5.7+（本机 5.7.24） / Redis / Flowable 7.2.0（阶段4引入）

## 登录与鉴权

    # 登录（经网关）
    curl -X POST http://localhost:18080/sso/auth/login -H "Content-Type: application/json" \
      -d '{"account":"admin","password":"admin123"}'
    # 返回 accessToken（2h）+ refreshToken（7d）；携带访问：
    curl -H "Authorization: Bearer <accessToken>" http://localhost:18080/system/user/page

- 白名单（免 token）：/sso/auth/login、/sso/auth/refresh、/sso/demo/**、/system/demo/**、/bpmn/demo/**、/actuator
- /inner/** 服务间接口：网关一律 403 屏蔽（Feign 内网直连）
- 方法级权限：@PreAuthorize("hasAuthority('system:user:list')")，权限标识存于 sys_menu.perms
- 注销/强退立即生效（Redis 在线会话）；生产部署必须修改 admin 初始密码

## 模块

| 模块 | 端口 | 说明 |
|---|---|---|
| cloud-gateway | 18080 | API 网关：JWT 鉴权路由（lb://）、跨域、/inner 屏蔽 |
| cloud-sso | 9201 | 认证中心：登录/双token/注销/在线管理 |
| cloud-system | 9202 | 系统管理 RBAC：用户/角色/菜单权限 |
| cloud-bpmn | 9203 | 工作流 Flowable（阶段4实现） |
| cloud-common-*-starter | - | core/security/mybatis/redis 公共模块（自动装配 starter） |

## 构建与启动

前置：JDK 17、Maven 3.8+、运行中的 Nacos（默认 127.0.0.1:8848，可用环境变量 NACOS_ADDR 覆盖）。

已知环境陷阱：

- 多网卡注册 IP：Nacos 自动选网卡，本机可能注册到虚拟网卡 IP（如 192.168.152.1）。本机直连可达；若网卡变更导致网关 503，用环境配置修（不进仓库）：spring.cloud.inetutils.preferred-networks 或 ignored-interfaces。
- Windows 停服残留：mvn spring-boot:run 停后 java 子进程可能仍占端口，netstat 找 PID 后 taskkill //F //PID；建议 java -jar 启动。

    mvn clean install
    java -jar cloud-base/cloud-sso/target/cloud-sso-1.0.0-SNAPSHOT.jar
    java -jar cloud-base/cloud-system/target/cloud-system-1.0.0-SNAPSHOT.jar
    java -jar cloud-base/cloud-bpmn/target/cloud-bpmn-1.0.0-SNAPSHOT.jar
    java -jar cloud-base/cloud-gateway/target/cloud-gateway-1.0.0-SNAPSHOT.jar   # 网关最后启动

验证：curl http://localhost:18080/system/demo/ping

## 阶段状态

- [x] 阶段1 工程骨架（本阶段）
- [x] 阶段2+3 权限管理与登录链路（RBAC + 登录/网关鉴权，2026-10-05 最小闭环）
- [ ] 阶段4 工作流（cloud-bpmn + Flowable）

设计文档：../docs/superpowers/specs/2026-10-04-cloud-base-backend-design.md
