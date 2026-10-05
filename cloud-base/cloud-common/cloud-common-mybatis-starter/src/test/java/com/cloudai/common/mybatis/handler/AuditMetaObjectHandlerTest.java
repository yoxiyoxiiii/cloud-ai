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
import java.util.List;

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
}
