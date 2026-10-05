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

    @Test
    void updateFill_skipsEntitiesWithoutAuditFields() {
        PlainEntity entity = new PlainEntity();
        MetaObject metaObject = SystemMetaObject.forObject(entity);
        assertThatCode(() -> handler.updateFill(metaObject)).doesNotThrowAnyException();
    }

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
}
