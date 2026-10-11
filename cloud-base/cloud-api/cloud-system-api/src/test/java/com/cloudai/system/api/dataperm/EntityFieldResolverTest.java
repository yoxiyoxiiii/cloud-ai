package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 行属性取值器单测（设计 D34，2026-10-11 谓词轮）：typed/字符串双形态取值、父类字段
 * （BaseEntity.createTime 类比）、未知字段 null（fail-closed 方向）。
 */
class EntityFieldResolverTest {

    private static class FixtureParent {
        LocalDateTime createTime;
    }

    private static class FixtureEntity extends FixtureParent {
        Long id;
        Integer status;
        String leaveType;
    }

    @Test
    void resolve_typedFields() {
        FixtureEntity bean = new FixtureEntity();
        bean.id = 50L;
        bean.status = 2;

        Function<String, Object> resolver = EntityFieldResolver.of(bean);

        assertThat(resolver.apply("id")).isEqualTo(50L);
        assertThat(resolver.apply("status")).isEqualTo(2);
    }

    @Test
    void resolve_stringFormFields() {
        FixtureEntity bean = new FixtureEntity();
        bean.leaveType = "1";

        Function<String, Object> resolver = EntityFieldResolver.of(bean);

        // VO 字符串形态（投影轮 CAST 直出）——归一由 RowPredicate.test 完成
        assertThat(resolver.apply("leaveType")).isEqualTo("1");
    }

    @Test
    void resolve_parentClassField() {
        FixtureEntity bean = new FixtureEntity();
        bean.createTime = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

        Function<String, Object> resolver = EntityFieldResolver.of(bean);

        // 父类字段（审计列在 BaseEntity——与 Applier 仅 getDeclaredFields 的差异验证）
        assertThat(resolver.apply("createTime")).isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
    }

    @Test
    void resolve_unknownField_null() {
        FixtureEntity bean = new FixtureEntity();

        Function<String, Object> resolver = EntityFieldResolver.of(bean);

        // 未知字段 → null → 谓词不满足 → 拒绝（fail-closed，D34）
        assertThat(resolver.apply("ghost")).isNull();
        assertThat(resolver.apply("id")).isNull();
    }

    @Test
    void resolve_nullBeanField_nullValue() {
        FixtureEntity bean = new FixtureEntity();

        Function<String, Object> resolver = EntityFieldResolver.of(bean);

        // 字段存在但值为 null（未赋值）——正常返回 null（谓词侧按不满足处理）
        assertThat(resolver.apply("id")).isNull();
    }
}
