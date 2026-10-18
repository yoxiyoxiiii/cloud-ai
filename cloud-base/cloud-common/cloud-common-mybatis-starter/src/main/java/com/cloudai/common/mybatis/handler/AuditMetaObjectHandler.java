package com.cloudai.common.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.cloudai.common.core.domain.LoginUser;
import org.apache.ibatis.reflection.MetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 审计字段自动填充：无对应属性的实体自动跳过；时间总是生效（覆盖调用方已设值）。
 * 时间语义为数据库时钟（SELECT NOW() 的偏移缓存，缓存 60 秒——窗口内不查库、过期刷新一次；
 * 探针查库失败回退 Java 进程时间，不阻断写路径）。
 * 操作人取登录上下文（LoginUser.account），匿名场景留空。
 * 匿名/异步上下文的 UPDATE 保留原 updateBy（不清空、不归因错误的人）；update(null, wrapper) 纯条件更新无实体可填充，不维护审计字段。
 * 仅对 MP BaseMapper CRUD 生效；手写 SQL 的审计时间由 mapper XML 的 NOW() 字面量承担。
 */
public class AuditMetaObjectHandler implements MetaObjectHandler {

    private static final Logger log = LoggerFactory.getLogger(AuditMetaObjectHandler.class);

    /** 偏移缓存有效期：窗口内用 Java 钟加偏移，过期查一次库刷新 */
    private static final long OFFSET_TTL_MS = 60_000L;

    private final DbTimeProbe probe;
    private final long ttlMs;
    private volatile OffsetSample sample;

    /** 生产构造：经 DataSource 查 SELECT NOW() 探数据库时钟 */
    public AuditMetaObjectHandler(DataSource dataSource) {
        this(() -> new JdbcTemplate(dataSource).queryForObject("SELECT NOW()", LocalDateTime.class), OFFSET_TTL_MS);
    }

    /** 测试构造：注入固定探针与缓存时长 */
    AuditMetaObjectHandler(DbTimeProbe probe, long ttlMs) {
        this.probe = probe;
        this.ttlMs = ttlMs;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = dbNow();
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
            setFieldValByName("updateTime", dbNow(), metaObject);
        }
        if (metaObject.hasSetter("updateBy")) {
            setFieldValByName("updateBy", currentOperator(), metaObject);
        }
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
        // 双检：并发下后进线程直接复用刚刷新的样本，避免重复探针
        if (s != null && System.currentTimeMillis() - s.cachedAt <= ttlMs) {
            return s;
        }
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

    private String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getAccount();
        }
        return null;
    }

    /** 数据库时钟探针：返回一次 SELECT NOW() 结果 */
    @FunctionalInterface
    interface DbTimeProbe {
        LocalDateTime probe();
    }

    /** 一次探针的偏移快照：数据库时间 - Java 时间 + 采样时刻 */
    private record OffsetSample(long offsetNanos, long cachedAt) {
    }
}
