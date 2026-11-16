# 数据权限组件封装设计（cloud-common 双 starter）

- 日期：2026-11-16
- 状态：定稿（组件抽取轮，主控出品）
- 关联：`2026-10-10-data-permission-design.md`（原始设计）、`2026-10-10-dataperm-component-api.md`（组件契约）、`2026-11-16-dataperm-aop-evaluate-design.md`（求值 AOP 化——上一轮，本设计的直接前置）

## 1. 问题与目标

数据权限机器面（注解/切面/列裁剪 advice/上下文/模型）自组件化轮起寄居在 cloud-system-api 的 `dataperm/` 包，跨库求值胶水（Feign fail-closed）手写在 cloud-bpmn `service/dataperm/`。问题：

- **api 模块职责漂移**：机器面不是「服务间契约」（契约是 DataPermClient + wire 模型），却占了 api 模块的大头，且 api 模块为它背 webmvc/aop 依赖；
- **消费方接入有手写面**：bpmn 手抄 40 行 Feign fail-closed 胶水 + `@EnableFeignClients` 条目，下一个跨库消费方（如 cloud-task）还得再抄一遍。

目标：镜像 translate 双模块形态抽成 cloud-common 公共组件——机器面归 `cloud-common-dataperm-starter`，远程求值归 `cloud-common-dataperm-remote-starter`（引依赖即用零配置零代码）。

## 2. 组件拓扑（D1：双模块镜像 translate）

| 组件 | 位置 | 职责 |
|---|---|---|
| `cloud-common-dataperm-starter` | `cloud-common/`，包 `com.cloudai.common.dataperm` | 机器面：`@DataPermEvaluate`/`@DataPermVo` 注解、求值切面、列裁剪 advice/applier、列上下文与请求级 filter、RowFilter/ColumnScope 模型、`DataPermEvaluationProvider` SPI + 两自动装配 |
| `cloud-common-dataperm-remote-starter` | `cloud-common/`，包 `com.cloudai.common.dataperm.remote` | 远程求值后端：`RemoteDataPermEvaluationProvider`（bpmn 手写胶水原样迁入，语义逐字不变）+ `DataPermRemoteAutoConfiguration`（程序式构建 DataPermClient） |
| 契约（不搬家） | cloud-system-api `client/` + `domain/` | `DataPermClient`（@FeignClient + fallbackFactory）与 wire 模型（DataPermEvaluateRequest/DataPermScopeVo/DataPermDenyRequest/PredicateVo/RowGroupVo）——服务间契约归提供方 api 模块惯例不变，契约文档 `2026-10-10-dataperm-component-api.md` 零改动 |

镜像参照：`cloud-common-translate-starter`（机器面）/ `cloud-common-translate-remote-starter`（程序式远程回源）。

system-api 与 dataperm-starter 的依赖方向：system-api → dataperm-starter（`DataPermScopeVo.toColumnScope()/toRowFilter()` 产 starter 类型、`PredicateVo` 持 `FilterColumnTypeEnum`）。无环——starter 只依赖 core-starter 与 spring 系。

## 3. 关键设计

### 3.1 契约不搬家（D2）

wire 模型与 Feign 接口留在 cloud-system-api：搬走等于单方改契约面（服务间对齐物漂移），且 DataPermClient 的 @FeignClient 注解元数据（contextId/fallbackFactory）被既有 `@EnableFeignClients` 消费路径依赖。starter 侧通过「api 模块反向依赖 starter」完成模型转换（§2 依赖方向），先例：cloud-common starter 依赖 cloud-api 模块（translate-remote 同款）。

### 3.2 远程求值：程序式 FeignClientBuilder（D3）

`DataPermRemoteAutoConfiguration` 经 spring-cloud-openfeign 4.x `FeignClientBuilder` 程序式构建 `DataPermClient`（`forType(DataPermClient.class, "cloud-system").path("/inner/data-perm")`）——**不经 @EnableFeignClients 扫描**（common 包不在消费方扫描范围），消费方引依赖即用、零配置零代码。Builder 不读 @FeignClient 注解元数据，path 显式传；构建需要 FeignClientFactory（FeignAutoConfiguration 自动装配）。translate-remote 同款模式（该处记档的特例先例）。

**本地优先双保险**（两处 @ConditionalOnMissingBean）：

- `dataPermClient` bean：消费方已 `@EnableFeignClients` 注册同型 client 则程序式构建让位（不产生双 bean）；
- `remoteDataPermEvaluationProvider` bean：宿主自带 `DataPermEvaluationProvider`（如 cloud-system 本地 provider）则远程后端让位——cloud-system 主通道是不引本模块。

### 3.3 超时与熔断（D4）

程序式 client 超时 connect 1s / read 2s（内网小负载 RPC，沿 translate-remote 取值）；**不经注解驱动的 circuit breaker 包装**——求值失败统一走 provider 的 catch-all fail-closed（BusinessException「数据权限服务不可用」），语义与熔断打开期一致（拒绝访问），translate-remote 同款取舍。deny 补痕调用面（ApprovalQueryService.denyQuietly）本就 catch-all 软失败，无熔断亦不受影响。

### 3.4 无功能开关（D5，沿上一轮结论）

不设 `cloud.dataperm.remote.enabled` 之类开关：关闭 = 标注方法求值后端缺失 = 调用即拒绝（fail-closed 报错，非静默放行）——开关只会制造全量报错面。紧急止损路径是回滚版本。（与 translate-remote 的 `cloud.translate.remote.enabled` 不同：翻译关闭是「无翻译降级」安全语义，求值关闭是「无权限判定」不安全语义。）

### 3.5 消费面收口

- **cloud-bpmn**：删 `service/dataperm/RemoteDataPermEvaluationProvider.java`（@Service 手写胶水），`@EnableFeignClients` 去掉 DataPermClient 条目（程序式 client 接管，deny 注入零改动）；pom 引两个 starter（沿 translate 双声明惯例）；
- **cloud-system**：机器面 import 批量改指 `com.cloudai.common.dataperm`（28 文件 sed，含 bpmn）；本地 provider（`LocalDataPermEvaluationProvider`）原样保留；pom 显式引 dataperm-starter；
- **其余服务（sso/task/aggregate）**：传递获得 starter（经 cloud-system-api），自动装配激活面与抽取前完全一致（此前 system-api 的 imports 文件同样注册这两条自动装配）——零改动零行为变化。

### 3.6 自动装配激活面不变

两自动装配（列裁剪 advisor + 求值切面）随 jar 走 AutoConfiguration.imports 注册。抽取前经 system-api 的 imports 文件激活（覆盖全部 system-api 消费方）；抽取后经 dataperm-starter 激活——由于 system-api 反向依赖 starter，传递闭包相同，激活面逐服务不变。

## 4. 测试策略

- dataperm-starter：17 个测试文件随类原样迁入（AspectJProxyFactory 织入真切面模式不变）；
- remote-starter 新增两组：
  - `DataPermRemoteAutoConfigurationTest`（ApplicationContextRunner + FeignAutoConfiguration + 直连 Client 桩）：①无本地后端→client+provider 装配；②本地 provider 存在→远程后端让位、client 照常；③消费方已注册 DataPermClient→程序式构建让位（单 bean 证明）；
  - `RemoteDataPermEvaluationProviderTest`（Mockito）：当前登录人解析入参、wire→求值结果映射（rowAll/白名单组/masked 列）、三路 fail-closed（降级 R 透传 msg / 空体默认文案 / Feign 异常默认文案）；
- cloud-system/cloud-bpmn 既有测试：import 批量改指新包，断言零改动（求值语义未动）。

## 5. 移交

- 契约面零变化：无新增错误码/权限标识/表/接口，`2026-10-10-dataperm-component-api.md` 不动；
- 下一个跨库消费方（如 cloud-task）接入 = 引 dataperm-starter + dataperm-remote-starter 两个依赖 + `@DataPermEvaluate` 标注，无胶水无配置；
- 程序式 client 无熔断是既定取舍（§3.3）——若未来求值 QPS 上升到需要熔断隔离，升级路径是消费方自行 @EnableFeignClients 注册（让位语义即接管）。
