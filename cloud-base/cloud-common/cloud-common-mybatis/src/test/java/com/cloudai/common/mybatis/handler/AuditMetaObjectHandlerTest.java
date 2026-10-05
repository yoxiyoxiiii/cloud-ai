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
}
