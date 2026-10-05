# cloud-base 后端微服务聚合工程设计文档

- 日期：2026-10-04
- 状态：已确认（用户逐节评审通过）
- 仓库：`D:\source\cloud-ai`，工程位于子目录 `cloud-base/`

## 1. 定位与目标

**企业应用基座平台**：先搭建通用管理基座（认证、用户权限、工作流），后续在同一仓库挂载具体业务模块（含 AI 相关服务）。

**成功标准**：SQL 脚本初始化后，4 个服务本地 IDE 启动即注册 Nacos，通过网关完成"登录 → 系统管理 CRUD → 请假审批"完整链路。

## 2. 技术栈矩阵

| 层面 | 选型 | 版本 |
|---|---|---|
| JDK | Java | 17（LTS） |
| 基础框架 | Spring Boot | 3.3.x |
| 微服务框架 | Spring Cloud | 2023.0.3 |
| 阿里体系 | Spring Cloud Alibaba | 2023.0.3.3（Nacos 注册+配置） |
| 网关 | Spring Cloud Gateway | 随 Spring Cloud |
| 认证 | Spring Security（自研 JWT，双 token） | 随 Boot |
| 工作流 | Flowable（BPMN 引擎） | 7.2.0 |
| ORM | MyBatis-Plus（boot3 starter） | 3.5.x |
| 数据库 | MySQL | 5.7+（开发机实测 5.7.24） |
| 缓存 | Redis（token 状态、在线用户、验证码） | 6.x+ |
| 服务间调用 | OpenFeign + Spring Cloud LoadBalancer | 随 Spring Cloud |
| API 文档 | SpringDoc OpenAPI | 2.x |
| 构建 | Maven 多模块聚合 | 单父 POM + dependencyManagement |

版本依据（2026-10 确认）：SCA 2023.0.3.3 官方兼容矩阵对应 Spring Cloud 2023.0.x / Boot 3.2~3.3.x；Flowable 7.2.0 是支持 Boot 3 的最新 7.x（8.x 已转向 Boot 4，不适用）。

## 3. 工程结构（已选方案 A）

```
cloud-base/
├── pom.xml                  # 父POM：统一依赖版本管理
├── cloud-common/
│   ├── cloud-common-core-starter    # 统一返回体/全局异常/错误码/工具
│   ├── cloud-common-security-starter    # JWT工具 + 资源端Spring Security自动配置
│   ├── cloud-common-mybatis-starter     # MyBatis-Plus配置/分页/审计填充/逻辑删除
│   └── cloud-common-redis-starter       # Redis配置/RedisUtil
├── cloud-gateway/           # 网关 :18080
├── cloud-sso/               # 认证中心 :9201
├── cloud-system/            # 系统管理 :9202
└── cloud-bpmn/              # 工作流 :9203
```

- 业务服务单 Maven 模块起步；Feign 契约量大后再拆 `-api` 子模块（演进路径到方案 B）。
- 公共模块用 Spring Boot 3 自动装配（`AutoConfiguration.imports`），业务服务引入依赖即生效。

## 4. 架构与端口

```
浏览器/前端
    │
    ▼
cloud-gateway (18080) ──白名单放行──► cloud-sso (9201) 登录/注销/验证码
    │  JWT校验 + 用户信息透传           │ Feign(内部接口)
    ├───────────────► cloud-system (9202)  用户/权限数据 ──┐
    └───────────────► cloud-bpmn  (9203)  审批人解析 ──────┤ Feign 调 system
                                                          ▼
        Nacos(注册/配置)   MySQL(cloud_system / cloud_bpmn)   Redis(token状态)
```

**关键约束**：
- 所有外部请求必须经网关；网关校验 JWT 后剥离外部同名 header，写入 `X-User-Id` / `X-User-Account` 等内部 header，下游覆盖式读取（防伪造）。
- 每服务独立数据库：`cloud_system`、`cloud_bpmn`；cloud-sso 无库（状态全在 Redis）。
- Nacos 配置约定：`cloud-{service}-{profile}.yaml` + 共享 `cloud-common-{profile}.yaml`（Redis、JWT 密钥等），`spring.config.import` 多 dataId 引入（不用 bootstrap）。

## 5. 认证鉴权设计（Spring Security 自研）

### Token 方案

| 项 | 设计 |
|---|---|
| 签名算法 | HS512，密钥存 Nacos 专属配置；签名/验签封装在 common-security，后续升 RS256 只改一处 |
| access_token | JWT，有效期 2h，声明：userId、account、tokenId(UUID)，只放非敏感信息 |
| refresh_token | UUID，有效期 7 天，仅用于换发新 access_token，只存 Redis |
| 状态控制 | 网关验签后查 Redis 在线会话，无记录即拒绝 → 注销/踢人立即生效 |

### Redis 键设计

```
sso:refresh:{userId}     → refresh_token（换发校验）
sso:online:{tokenId}     → 会话信息{userId, ip, 登录时间, 设备}，TTL=token有效期
sso:captcha:{uuid}       → 图形验证码（2分钟过期）
```

### 登录流程

```
POST /sso/auth/login {account, password, captchaCode, captchaUuid}
 1. 校验验证码（Redis 取出比对后删除）
 2. Feign 调 cloud-system /inner 接口 → 用户 + 角色 + 权限标识集合
 3. BCrypt 密码比对 → 失败则 Feign 记录失败日志
 4. 签发双 token，Redis 写在线会话
 5. 异步 Feign 通知 system 记录登录日志（失败不阻塞登录）
```

### 鉴权链路

```
请求 → cloud-gateway 全局过滤器
        ├─ 白名单(/sso/auth/login、验证码、API文档) → 放行
        ├─ JWT 验签 + 过期检查 ──失败→ 401
        ├─ Redis 在线检查 ──无→ 401
        └─ 写内部 header（先剥离外部同名 header）
下游服务（common-security 统一装配）:
        ├─ 过滤器从 header 构建 Authentication（LoginUser 上下文）
        └─ @PreAuthorize("hasAuthority('system:user:add')") 方法级权限
```

- 权限标识规范：`模块:实体:操作`（如 `system:user:add`、`bpmn:model:publish`），与 sys_menu 按钮权限对应。
- cloud-sso 自身也装配 common-security：登录接口匿名，在线管理/强退接口要求管理员权限。
- 数据权限 MVP 不做，common-mybatis 预留拦截器扩展点。

## 6. 数据设计

### cloud_system 库

| 表 | 说明 |
|---|---|
| sys_user / sys_dept / sys_post / sys_user_post | 用户、部门、岗位 |
| sys_role / sys_user_role / sys_role_menu / sys_menu | RBAC 核心；sys_menu 同时管理菜单与按钮权限 |
| sys_dict_type / sys_dict_data | 字典 |
| sys_config | 参数配置 |
| sys_oper_log / sys_login_log | 操作日志（AOP 注解采集）、登录日志（sso Feign 写入） |

### cloud_bpmn 库

Flowable 自动建 `ACT_*` 表 + 业务扩展表：

| 表 | 说明 |
|---|---|
| bpmn_category | 流程分类 |
| bpmn_definition_ext | 流程定义扩展：绑定分类、业务表单标识 |
| bpmn_instance_ext | 流程实例扩展：申请人、业务单据 ID、业务状态 |

审批意见用 Flowable 自带 comment 机制。初始化脚本内置 admin 超管、基础菜单权限、请假审批示例流程。

## 7. 公共模块职责

| 模块 | 内容 |
|---|---|
| cloud-common-core-starter | `R<T>` 统一返回体、BusinessException + 全局异常处理器、错误码、分页对象、常量、Jackson 统一格式（GMT+8，`yyyy-MM-dd HH:mm:ss`）、Hutool |
| cloud-common-security-starter | JWT 签发/验签、资源端 SecurityFilterChain 自动配置、LoginUser 上下文 |
| cloud-common-mybatis-starter | 分页插件、审计字段自动填充（create_by/create_time/update_by/update_time）、逻辑删除 |
| cloud-common-redis-starter | RedisTemplate 序列化配置、RedisUtil 封装 |

（2026-10-05 更名：四个公共模块由 cloud-common-xxx 更名为 cloud-common-xxx-starter，凸显其自动装配 starter 语义；Java 包名 com.cloudai.common.* 不变。）

## 8. 服务间调用

- cloud-system 暴露 `/inner/**` 内部接口（查用户/权限、写日志），网关路由屏蔽 `/inner/**`，仅服务间 Feign 可达。
- Feign 接口定义暂放调用方（sso、bpmn 各自定义 client + fallback），量大了再抽 api 模块。

## 9. MVP 功能范围

| 服务 | MVP 包含 | 明确不做（预留扩展） |
|---|---|---|
| cloud-gateway | 动态路由、JWT 校验+在线检查、用户透传、跨域、白名单、`/inner/**` 屏蔽 | 限流（后续 Sentinel） |
| cloud-sso | 登录（账密+图形验证码）、双 token、刷新、注销、在线用户查询/强退 | 短信/扫码/OAuth2 登录 |
| cloud-system | 用户/角色/菜单/部门/岗位 CRUD、权限分配、字典、参数、操作/登录日志、`/inner/**` | 多租户、数据权限 |
| cloud-bpmn | 流程分类、定义 XML 上传部署/挂起、发起实例、待办/已办、审批（同意/驳回）、审批意见、流程跟踪 | 在线设计器、业务表单引擎、会签/多实例 |

## 10. 错误处理

- 错误码分段：`1xxx` 通用 / `2xxx` 认证 / `3xxx` system / `4xxx` bpmn。
- 网关 401/403/503 返回统一 JSON（与 `R<T>` 结构一致）。
- 业务异常 `BusinessException(错误码)` → common-core 全局异常处理器。
- Feign 配 fallback 降级，返回统一错误。

## 11. 测试策略

- 单元测试（JUnit 5 + Mockito）：JWT 签发/验签/过期、异常映射、审计填充、分页。
- 阶段验收：每阶段附 curl/Swagger 手工验证清单。
- 终验：请假审批全链路（登录→发起→待办→审批→流程跟踪）。

## 12. 分期实施

| 阶段 | 内容 | 验证标准 |
|---|---|---|
| 1. 工程骨架 | 父 POM + 4 common 模块 + 4 可启动服务 + Nacos 接入 + 网关路由 + 统一返回/异常 | 4 服务注册 Nacos；经网关访问 demo 接口返回统一 `R<T>` |
| 2. 系统管理 | cloud-system 全部 RBAC 功能 + SQL 初始化脚本 | Swagger 调通全部 CRUD |
| 3. 认证链路 | cloud-sso 全部功能 + 网关鉴权 + common-security 装配全部业务服务 | 登录→访问→权限拦截→注销失效→在线强退 |
| 4. 工作流 | cloud-bpmn 全部功能 + 请假示例流程 | 发起→待办→审批→流程跟踪 |

每阶段结束均为可运行、可验证状态。

## 13. 工程约定

- `groupId: com.cloudai`，根包 `com.cloudai.gateway / sso / system / bpmn / common.*`。
- 时间统一 GMT+8、`yyyy-MM-dd HH:mm:ss`。
- 日志 Logback 按服务分文件；网关生成 trace-id 经 header 透传，MDC 打印。
- 中间件（Nacos/MySQL/Redis）使用已有环境，仓库不包含 docker-compose。
