# 通知未读数统计表优化（notify 域，纯后端 B 级）

**日期**：2026-11-08
**背景**：`/system/notify/summary` 是 30s 轮询最热查询，现实现 `COUNT(*) ... WHERE user_id=? AND read_flag=0` 直扫收件表（写扩散表，随发送量线性膨胀），并发用户多后变慢。用户指定方向：改按用户统计表，不走 COUNT。
**契约影响**：无——`2026-11-08-notify-api.md` §3.1 出参与语义不变（性能内部优化），契约文档不动。
**设计变更**：`docs/superpowers/specs/2026-11-08-notify-center-design.md` §2 三表 → 增补第 4 张统计表（见本文任务 8 同步该文档）。

## 方案

- 新表 `sys_notify_user_stat`：每用户一行（user_id PK），`unread_count` 未读数、`last_receipt_id` 最新收件行 id（水位，一并消除 summary 的 `MAX(id)`）。
- **收件表仍是真相源**，统计表 = 同事务维护的派生计数；xxl-job 任务 11 每日对账全量重建兜底漂移（自愈）。
- **SQL 只做单表单键简单读写（修订 2026-11-08，用户 review）**：禁止 ODKU 聚合 / `VALUES()` / `GREATEST` / `INSERT...SELECT` 写入这类"逻辑埋进 SQL"的形态——分组、建行、对账编排全部上 Java 层；SQL 仅保留 `unread_count = unread_count ± #{delta}` 单行原子自增自减（标准形态，保并发正确性）与普通 INSERT/SELECT/DELETE。
- MySQL 5.7.24 实测环境。

## 任务清单

### 任务 1：DDL + 增量脚本 + 基线同步

**Create** `cloud-base/scripts/sql/2026-11-08-notify-unread-stat.sql`（增量脚本，**天然幂等**——建表 IF NOT EXISTS + 对账语句同款回填，可重放）：

```sql
-- 通知未读统计表（notify 域性能优化轮，2026-11-08）：
-- summary 30s 轮询由收件表 COUNT(*) 改为统计表单行 PK 查询；收件表仍是真相源，
-- 统计表由发送/已读三写路径事务内维护，xxl-job 任务 11 每日 03:40 对账全量重建兜底。
USE cloud_system;

CREATE TABLE IF NOT EXISTS sys_notify_user_stat (
    user_id         BIGINT      NOT NULL COMMENT '用户ID（sys_user.id，主键=每用户一行）',
    unread_count    INT         NOT NULL DEFAULT 0 COMMENT '未读数（发送+1、已读按影响行数递减；每日对账重建兜底）',
    last_receipt_id BIGINT      NOT NULL DEFAULT 0 COMMENT '该用户最新收件行 id（summary 轮询水位；0=无任何收件）',
    update_time     DATETIME    DEFAULT NULL COMMENT '最近维护时间',
    PRIMARY KEY (user_id)
) ENGINE = InnoDB COMMENT = '站内信未读统计表（收件表派生计数：真相源 sys_notify_user_message，写路径同事务维护+每日对账）';

-- 回填（= 对账语句同款，幂等）：存量收件数据聚合入统计表
INSERT INTO sys_notify_user_stat (user_id, unread_count, last_receipt_id, update_time)
SELECT user_id, SUM(read_flag = 0), IFNULL(MAX(id), 0), NOW()
  FROM sys_notify_user_message
 GROUP BY user_id
ON DUPLICATE KEY UPDATE unread_count = VALUES(unread_count),
                        last_receipt_id = VALUES(last_receipt_id),
                        update_time = NOW();
```

**Modify** `cloud-base/scripts/sql/cloud_system.sql`：DROP 段顶部补 `DROP TABLE IF EXISTS sys_notify_user_stat;`（放在收件表 DROP 之前，无外键无顺序依赖）；§2 表区按上表 DDL 同步（去 IF NOT EXISTS，沿基线风格）；文件头注释三表 → 四表。

### 任务 2：实体 + Mapper 接口 + XML（简单语句集）

**Create** `cloud-base/cloud-system/src/main/java/com/cloudai/system/entity/SysNotifyUserStat.java`：不继承 BaseEntity（计数表，无业务生命周期），含 javadoc（派生计数/真相源/对账兜底）；字段 userId/unreadCount/lastReceiptId/updateTime。

**Create** `cloud-base/cloud-system/src/main/java/com/cloudai/system/mapper/SysNotifyUserStatMapper.java` + `resources/mapper/SysNotifyUserStatMapper.xml`，六条简单语句（全部单表单键，无 ODKU/无聚合写）：

```xml
<!-- 单行 PK 查询：summary 30s 轮询取数（无行=该用户无收件，Service 兜底 0/0） -->
<select id="findByUser" resultType="...SysNotifyUserStat">
    SELECT user_id, unread_count, last_receipt_id, update_time
      FROM sys_notify_user_stat
     WHERE user_id = #{userId}
</select>

<!-- 单行 INSERT：发送路径该用户首条收件建行（并发建行由 Java 层 catch 吸收重试） -->
<insert id="save">
    INSERT INTO sys_notify_user_stat (user_id, unread_count, last_receipt_id, update_time)
    VALUES (#{userId}, #{unreadCount}, #{lastReceiptId}, NOW())
</insert>

<!-- 普通多行 INSERT：对账重灌专用（前一步已 deleteAll，无冲突） -->
<insert id="saveBatch">
    INSERT INTO sys_notify_user_stat (user_id, unread_count, last_receipt_id, update_time) VALUES
    <foreach collection="list" item="item" separator=",">
        (#{item.userId}, #{item.unreadCount}, #{item.lastReceiptId}, NOW())
    </foreach>
</insert>

<!-- 发送路径：单用户原子递增 + 水位直接覆盖（收件行 id 单调递增，Java 传批内该用户最大 id） -->
<update id="updateIncrease">
    UPDATE sys_notify_user_stat
       SET unread_count = unread_count + #{delta},
           last_receipt_id = #{lastReceiptId},
           update_time = NOW()
     WHERE user_id = #{userId}
</update>

<!-- 已读路径：按实际影响行数守卫递减（防负数；统计行缺失时 0 行，留给每日对账收敛） -->
<update id="updateDecrease">
    UPDATE sys_notify_user_stat
       SET unread_count = unread_count - #{delta},
           update_time = NOW()
     WHERE user_id = #{userId} AND unread_count &gt;= #{delta}
</update>

<!-- 对账第一步：清空统计表（随后 Java 组装 saveBatch 重灌；同一事务） -->
<delete id="deleteAll">
    DELETE FROM sys_notify_user_stat
</delete>
```

**Modify** `SysNotifyUserMessageMapper.java` + XML：新增对账聚合**只读**查询（GROUP BY 读是 SQL 本职，写逻辑不进 SQL）：

```xml
<!-- 对账取数：按用户聚合未读数与最新收件行 id（只读；列别名对齐 SysNotifyUserStat 驼峰映射复用实体） -->
<select id="listUnreadAgg" resultType="com.cloudai.system.entity.SysNotifyUserStat">
    SELECT user_id,
           SUM(read_flag = 0) AS unread_count,
           IFNULL(MAX(id), 0) AS last_receipt_id
      FROM sys_notify_user_message
     GROUP BY user_id
</select>
```

注意：`<if>` 换行规范本题无涉及；全部 `#{}`。

### 任务 3：发送路径维护统计（Java 编排）

**Modify** `NotifySendService.saveReceipts`：批量插收件行后，**同批** Java 分组维护统计（仍在 `saveNotify` 的 @Transactional 内）。收件行 insert 须带回填 id：`SysNotifyUserMessageMapper.xml` 的 `saveBatch` 补 `useGeneratedKeys="true" keyProperty="id"`，实体含 id 字段（已有）。分组与建行逻辑全在 Java：

```java
/** 批后维护统计：按用户分组递增，水位=批内该用户最大收件行 id（id 单调递增，直接覆盖）；
 *  统计行不存在则建行（并发首建冲突吸收后重试递增一次） */
private void increaseStat(List<SysNotifyUserMessage> rows) {
    Map<Long, List<SysNotifyUserMessage>> byUser = rows.stream()
            .collect(Collectors.groupingBy(SysNotifyUserMessage::getUserId));
    byUser.forEach((userId, list) -> {
        long maxId = list.stream().map(SysNotifyUserMessage::getId)
                .filter(Objects::nonNull).max(Long::compareTo).orElse(0L);
        int delta = list.size();
        int updated = statMapper.updateIncrease(userId, delta, maxId);
        if (updated == 0) {
            saveStatRow(userId, delta, maxId);
        }
    });
}

/** 建统计行：并发同用户首建撞主键时吸收重试递增（保持计数不丢） */
private void saveStatRow(Long userId, int delta, long maxId) {
    SysNotifyUserStat stat = new SysNotifyUserStat();
    stat.setUserId(userId);
    stat.setUnreadCount(delta);
    stat.setLastReceiptId(maxId);
    try {
        statMapper.save(stat);
    } catch (DuplicateKeyException e) {
        log.error("统计行并发首建冲突，重试递增: userId={}", userId, e);
        statMapper.updateIncrease(userId, delta, maxId);
    }
}
```

原 `DuplicateKeyException` 收件行兜底语义不变（uk_message_user 防御层）；收件行冲突批统计一并跳过，由每日对账收敛。

### 任务 4：已读路径维护统计（补事务）

**Modify** `NotifyUserMessageService`：
- `updateRead` / `updateReadAll` 各加 `@Transactional(rollbackFor = Exception.class)`（原「单表单语句不加事务」理由失效——现两语句）。头注释同步改。
- `updateRead`：mapper `updateRead` 改为返回 `int`（影响行数，XML 已条件写无需改），`n > 0` 时 `statMapper.updateDecrease(userId, n)`（单行守卫递减，简单形态保留）。
- `updateReadAll`：同理（`updateReadAll` 已返回 int）。
- `findSummary`：改读 `statMapper.findByUser(userId)`，行缺失 → unreadCount "0" / latestId "0"；头注释「30s 轮询单行 PK 查询，不走 COUNT」。

**Modify** `SysNotifyUserMessageMapper.java`：`updateRead` 返回类型 void → int（XML 无需动）。

### 任务 5：对账入清理任务（Java 编排 + 事务）

**Modify** `NotifyUserMessageService`：新增对账方法（聚合读 → 清空 → 重灌，同一事务原子）：

```java
/** 统计对账：按收件表真相源全量重建统计表（xxl-job 每日调度；清空+重灌同事务，失败整体回滚） */
@Transactional(rollbackFor = Exception.class)
public int resetUnreadStat() {
    List<SysNotifyUserStat> aggs = userMessageMapper.listUnreadAgg();
    statMapper.deleteAll();
    if (!aggs.isEmpty()) {
        statMapper.saveBatch(aggs);
    }
    return aggs.size();
}
```

**Modify** `NotifyCleanJobHandler.cleanExpired`：两步删除后第三步改调 `userMessageService.resetUnreadStat()`（返回行数记 log `statRows`）；类头注释「两步」→「三步（③统计对账）」。对账逻辑集中在 service，job 只做调度薄壳。

### 任务 6：守护/绑定测试同步

**Modify** `MapperXmlBindingTest`：XML 清单 + `SysNotifyUserStatMapper.xml`（17→18），语句总数 112→119（stat 6 条 + 收件表 listUnreadAgg 1 条），计数注释追加本轮一行，contains 抽查补 `SysNotifyUserStatMapper.updateIncrease` 与 `SysNotifyUserMessageMapper.listUnreadAgg`。

### 任务 7：单测同步

- `NotifyUserMessageServiceTest`：summary 走 stat mock（有行/无行两态，已有）；updateRead/updateReadAll 断言 updateDecrease 以实际影响行数调用（已有）；**新增** `resetUnreadStat` 序列断言（listUnreadAgg → deleteAll → 空列表不调 saveBatch）。
- `NotifySendServiceTest`：断言发送后按用户分组调用 `updateIncrease(userId, 收件数, 批内最大 id)`；统计行不存在（updateIncrease 返回 0）时断言 `save` 建行；并发建行冲突（save 抛 DuplicateKey）时断言重试 updateIncrease。
- 全量 `mvn -f cloud-base/pom.xml clean install -pl cloud-system -am` 全绿（守护测试一并过）。

### 任务 8：设计文档同步

**Modify** `docs/superpowers/specs/2026-11-08-notify-center-design.md`：§2 表清单第 4 表的**维护方式描述改 Java 编排口径**（若已贴 ODKU/GREATEST/INSERT...SELECT 形态 SQL 一并替换为简单语句集描述）；§6 清理任务段对账第三步改「service 编排（聚合读→清空→重灌同事务）」。

## 验证标准（主控集成段执行）

1. `mvn clean install -pl cloud-system -am` 全绿（含守护 + 绑定 119）。
2. 重启 cloud-system：真链路验证——发送 → summary unread +N 且 latestId 变化；单条已读 → -1；全部已读 → 归零；再发再读闭环；新用户首条收件走 save 建行分支。
3. `EXPLAIN` summary 查询走 const/PK。
4. 对账幂等：手动触发 xxl 任务 11 前后统计零漂移；验证数据自清后再触发，统计收敛真相源。
5. e2e 全量回归 `grep -c '[FAIL]'`==0（Navbar 登录即轮询 summary 实证轮询路径）。
6. 测试数据清理（e2e_ 前缀），服务按需交还。
