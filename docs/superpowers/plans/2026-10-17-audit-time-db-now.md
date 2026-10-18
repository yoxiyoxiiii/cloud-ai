# 审计时间全量改用数据库时间（NOW()） 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 所有落库的审计时间（create_time/update_time）一律取数据库时钟（SQL 端 `NOW()`），不再用 Java 进程时间；操作人（create_by/update_by）仍走登录上下文显式传参。

**Architecture:** 手写 SQL 主路径在 mapper XML 里把审计时间列的绑定参数改为 `NOW()` 字面量（同语句内 NOW() 恒定，保持「插入 create=update 同值」语义）；休眠件 AuditMetaObjectHandler 改为「SELECT NOW() 偏移缓存」取 DB 时间（TTL 60s，查库失败回退 Java 时间不阻断写路径）。

**Tech Stack:** MySQL 5.7.24（session 时区 SYSTEM=本机墙钟 UTC+8，已实测与 JVM 同口径，NOW() 无时区漂移）。

**级别：** B 级纯后端。契约零改动（审计时间不出现在任何契约断言）、DDL 零改动、前端零改动。

---

## 统一口径（全 plan 适用）

1. **存储型审计时间**（写进 create_time/update_time 列的值）→ SQL `NOW()`。
2. **纯查询截止参数**（清理任务 cutoff、对账窗口比较值）→ 保持 Java 时间，不动（不落库无一致性要求）。
3. **操作人**（create_by/update_by）→ 仍 SecurityUtils.currentAccount() 显式传参，不动。
4. Service 层**只删审计用途**的 `LocalDateTime.now()`；业务列用途（如业务时间默认值）逐个判断保留。
5. mq starter 的 INSERT 本就不写审计列（grep 实证 create_time 仅出现在 DELETE cutoff），零改动。

## 改造模式（代表示例，其余文件同模式套用）

### 模式 A：mapper XML INSERT（列显式 + NOW() 值）

改前：
```xml
INSERT INTO xxx_yyy (name, status, create_by, create_time, update_by, update_time)
VALUES (#{name}, #{status}, #{createBy}, #{createTime}, #{updateBy}, #{updateTime})
```
改后：
```xml
INSERT INTO xxx_yyy (name, status, create_by, create_time, update_by, update_time)
VALUES (#{name}, #{status}, #{createBy}, NOW(), #{updateBy}, NOW())
```

### 模式 B：mapper XML UPDATE / 逻辑删除墓碑

改前：`update_time = #{updateTime},` → 改后：`update_time = NOW(),`
`<if>` 匿名保留 update_by 的结构不动。逻辑删除（deleteXxxById）同理：`update_time = NOW()`。

### 模式 C：Mapper 接口签名收窄（删时间参数）

改前：
```java
int updateXxx(@Param("xxx") XxxYyy xxx, @Param("updateBy") String updateBy,
              @Param("updateTime") LocalDateTime updateTime);
```
改后：
```java
int updateXxx(@Param("xxx") XxxYyy xxx, @Param("updateBy") String updateBy);
```
deleteXxxById 同理删 updateTime 参数。insertXxx 签名不变（时间从实体取的字段不再被 SQL 引用）。

### 模式 D：Service 层（auditCreate 收窄 + 调用点删 now）

改前：
```java
private void auditCreate(XxxYyy xxx) {
    LocalDateTime now = LocalDateTime.now();
    String operator = SecurityUtils.currentAccount();
    xxx.setCreateBy(operator); xxx.setCreateTime(now);
    xxx.setUpdateBy(operator); xxx.setUpdateTime(now);
}
manageService.edit: xxxMapper.updateXxx(xxx, SecurityUtils.currentAccount(), LocalDateTime.now());
```
改后：
```java
private void auditCreate(XxxYyy xxx) {
    String operator = SecurityUtils.currentAccount();
    xxx.setCreateBy(operator);
    xxx.setUpdateBy(operator);
}
manageService.edit: xxxMapper.updateXxx(xxx, SecurityUtils.currentAccount());
```
（实体 createTime/updateTime 字段保留——读路径映射仍用；不再由写路径赋值。）

### 模式 E：AuditMetaObjectHandler（DB 时间偏移缓存）

完整新实现（替换 `cloud-common-mybatis-starter/.../handler/AuditMetaObjectHandler.java`）：
```java
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditMetaObjectHandler.class);
    /** 偏移缓存有效期：窗口内用 Java 钟加偏移，过期查一次库刷新 */
    private static final long OFFSET_TTL_MS = 60_000L;

    private final DbTimeProbe probe;
    private final long ttlMs;
    private volatile OffsetSample sample;

    /** 生产构造：经 DataSource 查 SELECT NOW() */
    public AuditMetaObjectHandler(DataSource dataSource) {
        this(ds -> {
            return new JdbcTemplate(ds).queryForObject("SELECT NOW()", LocalDateTime.class);
        }, OFFSET_TTL_MS);
    }

    /** 测试构造：注入固定探针 */
    AuditMetaObjectHandler(DbTimeProbe probe, long ttlMs) {
        this.probe = probe;
        this.ttlMs = ttlMs;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = dbNow();
        String operator = currentOperator();
        if (metaObject.hasSetter("createTime")) { setFieldValByName("createTime", now, metaObject); }
        if (metaObject.hasSetter("updateTime")) { setFieldValByName("updateTime", now, metaObject); }
        if (metaObject.hasSetter("createBy")) { setFieldValByName("createBy", operator, metaObject); }
        if (metaObject.hasSetter("updateBy")) { setFieldValByName("updateBy", operator, metaObject); }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        if (metaObject.hasSetter("updateTime")) { setFieldValByName("updateTime", dbNow(), metaObject); }
        if (metaObject.hasSetter("updateBy")) { setFieldValByName("updateBy", currentOperator(), metaObject); }
    }

    /** 数据库时钟：Java 钟 + 缓存偏移；偏移过期或查库失败时刷新/回退 */
    LocalDateTime dbNow() {
        OffsetSample s = sample;
        if (s == null || System.currentTimeMillis() - s.cachedAt > ttlMs) {
            s = refreshSample();
        }
        return LocalDateTime.now().plusNanos(s.offsetNanos);
    }

    private synchronized OffsetSample refreshSample() {
        OffsetSample s = sample;
        if (s != null && System.currentTimeMillis() - s.cachedAt <= ttlMs) { return s; }
        LocalDateTime dbAt;
        try {
            dbAt = probe.probe();
        } catch (Exception e) {
            log.warn("DB 时间探针失败，审计填充回退 Java 进程时间", e);
            dbAt = LocalDateTime.now();
        }
        long offset = Duration.between(LocalDateTime.now(), dbAt).toNanos();
        s = new OffsetSample(offset, System.currentTimeMillis());
        sample = s;
        return s;
    }

    @FunctionalInterface
    interface DbTimeProbe { LocalDateTime probe(); }

    private record OffsetSample(long offsetNanos, long cachedAt) { }

    // currentOperator() 原样保留
}
```
装配：`CommonMybatisAutoConfiguration` 里 handler 构造改传 DataSource bean（servlet 服务必驻）。javadoc 改写：时间语义=数据库时钟（60s 偏移缓存，探针失败回退 Java 时间）；说明仅对 MP BaseMapper CRUD 生效、手写 SQL 由 XML NOW() 承担。

### 模式 F：projection DAO（cloud-bpmn-api，JdbcTemplate 常量 SQL）

`ApprovalProjectionDao` 三段 upsert 的 `VALUES(..., ?, ?)`（update_by/update_time）→ update_by 保留 `?`、update_time 值改 `NOW()`；`ON DUPLICATE KEY UPDATE ... update_time = VALUES(update_time)` 不变（引用插入值）；第 71 行 update 的 `update_time = ?` → `NOW()`。`ApprovalProjectionListener` 三处 `LocalDateTime.now()` 实参删除（方法签名随之收窄）；`ApprovalProjectionReconciler` 的 now 仅用于落 update_time/diff 列的传参删除，若兼作查询比较值则查询处保留 Java 时间。相关方法签名与 `ApprovalProjectionDaoTest` 断言同步（时间参数从参数断言中移除；SQL 文本断言含 NOW()）。

---

## 文件清单（grep 实证基线）

| 域 | 文件 | 模式 |
|---|---|---|
| mybatis-starter | handler/AuditMetaObjectHandler.java（重写）、config/CommonMybatisAutoConfiguration.java（装配传 DataSource）、handler/AuditMetaObjectHandlerTest.java（provider 注入改造 + 偏移缓存单测：固定探针返回偏移值、TTL 内二次调用不再探针、探针抛异常回退） | E |
| cloud-system | 12 个 Service/其它 Java 文件（LocalDateTime.now 落审计列的调用点删除）、mapper 接口收窄、12 个 mapper XML | A/B/C/D |
| cloud-bpmn | 4 个 Java 文件、2 个 mapper XML | A/B/C/D |
| cloud-bpmn-api | ApprovalProjectionDao/Listener/Reconciler + ApprovalProjectionDaoTest | F |
| 规范文档 | CLAUDE.md 编码规范「写操作审计字段显式传参」条目改写为「操作人显式传参；审计时间一律 SQL NOW()（DB 时钟为唯一真相源），MP 填充件同口径（偏移缓存）」；`.claude/skills/backend-spec/SKILL.md`（8 处 LocalDateTime 模板同步；若 backend-crud 技能文件在盘一并 grep 同步） | — |

执行时以 `grep -rn "LocalDateTime.now\|updateTime\|createTime" cloud-base/{cloud-system,cloud-bpmn}/src/main` 现场复核清单为准（清点后逐文件套模式，注意只动审计用途）。

### Task 1: mybatis-starter（handler 重写 + 装配 + 单测）
- [ ] 按模式 E 重写 handler 与装配；单测改造（原时间断言改注入固定探针；新增偏移缓存与回退用例）
- [ ] `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml install -pl cloud-common/cloud-common-mybatis-starter -am -q` BUILD SUCCESS

### Task 2: cloud-system 回接（XML + 接口 + Service + 单测适配）
- [ ] 逐文件套模式 A-D；Service 单测中 verify(...updateXxx(..., any(LocalDateTime.class))) 类断言同步收窄
- [ ] `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml install -pl cloud-system -am -q` BUILD SUCCESS（守护测试全绿——规则不含审计传参断言，已核）

### Task 3: cloud-bpmn + projection DAO
- [ ] 同模式 A-D；bpmn-api 按模式 F
- [ ] `D:/software/apache-maven-3.8.4/bin/mvn -f cloud-base/pom.xml install -pl cloud-bpmn -am -q` BUILD SUCCESS

### Task 4: 全仓构建 + 规范文档
- [ ] 全仓 `clean install` BUILD SUCCESS
- [ ] CLAUDE.md 与技能模板同步（上表末行）

### Task 5: 真机对拍验收（DB 时钟实证）
- [ ] 起 sso(9201)/system(9202)/gateway(18080)，admin 登录经网关调一个编辑端点（如 PUT /system/dict 或用户编辑）与一个新增端点
- [ ] pymysql 对拍：`SELECT create_time, update_time, NOW() FROM <表> WHERE id=<新id>`——update_time 与 NOW() 同秒级（±2s）；INSERT 行 create_time == update_time
- [ ] `curl http://localhost:18080/system/demo/ping` 正常
- [ ] 验完立即停服（netstat 找 PID + taskkill //F //PID），端口复核清零

## 明确不做

- cloud-base-ai（Python 侧自有审计口径，需求点名 Java 后端）；sso（无 DB）
- DDL 改动（不加 DEFAULT CURRENT_TIMESTAMP，显式 NOW() 已覆盖）；mq starter（cutoff 是查询参数非存储值）
- 全量 e2e（契约零改动，审计时间不在任何断言；真机对拍即行为证明）

## 移交备忘

- 若未来引入 MP BaseMapper CRUD：填充件已是 DB 时钟口径，无需再动
- 若 DB 迁移到独立服务器：NOW() 语义自动跟随新 DB 时钟，Java 侧无感知（正是本轮目的）
