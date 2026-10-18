package com.cloudai.common.mybatis.handler;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.within;

class AuditMetaObjectHandlerTest {

    /** 固定探针：数据库时钟比 Java 时钟快 2 小时（模拟跨库时钟偏移） */
    private static LocalDateTime probeTimePlusTwoHours() {
        return LocalDateTime.now().plusHours(2);
    }

    private final AuditMetaObjectHandler handler =
            new AuditMetaObjectHandler(AuditMetaObjectHandlerTest::probeTimePlusTwoHours, 60_000L);

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

    @Test
    void updateFill_skipsEntitiesWithoutAuditFields() {
        PlainEntity entity = new PlainEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        assertThatCode(() -> handler.updateFill(metaObject)).doesNotThrowAnyException();
    }

    @Test
    void insertFill_fillsOperatorFromSecurityContext() {
        var loginUser = new LoginUser();
        loginUser.setAccount("admin");
        var auth = new UsernamePasswordAuthenticationToken(loginUser, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            OrderEntity entity = new OrderEntity();
            handler.insertFill(SystemMetaObject.forObject(entity));
            assertThat(entity.getCreateBy()).isEqualTo("admin");
            assertThat(entity.getUpdateBy()).isEqualTo("admin");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void updateFill_fillsOperatorFromSecurityContext() {
        var loginUser = new LoginUser();
        loginUser.setAccount("admin");
        var auth = new UsernamePasswordAuthenticationToken(loginUser, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            OrderEntity entity = new OrderEntity();
            entity.setCreateBy("someone");
            handler.updateFill(SystemMetaObject.forObject(entity));
            assertThat(entity.getUpdateBy()).isEqualTo("admin");
            assertThat(entity.getCreateBy()).isEqualTo("someone");
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void insertFill_noContextLeavesOperatorNull() {
        OrderEntity entity = new OrderEntity();
        handler.insertFill(SystemMetaObject.forObject(entity));
        assertThat(entity.getCreateBy()).isNull();
    }

    @Test
    void dbNow_appliesProbeOffset() {
        LocalDateTime dbNow = handler.dbNow();
        // 探针返回 Java 钟 + 2h，偏移应传导到 dbNow（留 1 分钟容差吸收执行耗时）
        assertThat(dbNow).isCloseTo(LocalDateTime.now().plusHours(2), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void dbNow_ttlWindowProbesOnlyOnce() {
        AtomicInteger probeCount = new AtomicInteger();
        AuditMetaObjectHandler counting =
                new AuditMetaObjectHandler(() -> {
                    probeCount.incrementAndGet();
                    return LocalDateTime.now();
                }, 60_000L);
        counting.dbNow();
        counting.dbNow();
        counting.dbNow();
        // TTL 窗口内复用同一偏移样本，只探一次库
        assertThat(probeCount.get()).isEqualTo(1);
    }

    @Test
    void dbNow_probeFailureFallsBackToJavaClock() {
        AuditMetaObjectHandler failing =
                new AuditMetaObjectHandler(() -> {
                    throw new IllegalStateException("db unreachable");
                }, 60_000L);
        LocalDateTime dbNow = failing.dbNow();
        // 探针失败不抛异常，回退 Java 进程时间
        assertThat(dbNow).isCloseTo(LocalDateTime.now(), within(1, ChronoUnit.MINUTES));
    }
}
