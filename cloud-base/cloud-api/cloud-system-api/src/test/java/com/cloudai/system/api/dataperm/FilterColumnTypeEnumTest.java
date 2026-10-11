package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可过滤列类型枚举单测（设计 D32/D34/D38，2026-10-11 谓词轮）：matches 字段类型矩阵、
 * normalize 归一器格式红绿（单一实现，配置校验 3037 与 toRowFilter 两处消费）、format 逆变换。
 */
class FilterColumnTypeEnumTest {

    // ---- matches 字段类型矩阵（FilterColumns 断言依据） ----

    @Test
    void matches_matrix() {
        assertThat(FilterColumnTypeEnum.NUMBER.matches(Long.class)).isTrue();
        assertThat(FilterColumnTypeEnum.NUMBER.matches(Integer.class)).isTrue();
        assertThat(FilterColumnTypeEnum.NUMBER.matches(int.class)).isTrue();
        assertThat(FilterColumnTypeEnum.NUMBER.matches(String.class)).isFalse();

        assertThat(FilterColumnTypeEnum.STRING.matches(String.class)).isTrue();
        assertThat(FilterColumnTypeEnum.STRING.matches(Long.class)).isFalse();

        assertThat(FilterColumnTypeEnum.DATE.matches(LocalDate.class)).isTrue();
        assertThat(FilterColumnTypeEnum.DATE.matches(LocalDateTime.class)).isFalse();

        assertThat(FilterColumnTypeEnum.DATETIME.matches(LocalDateTime.class)).isTrue();
        assertThat(FilterColumnTypeEnum.DATETIME.matches(LocalDate.class)).isFalse();
    }

    // ---- normalize 归一器红绿 ----

    @Test
    void normalizeNumber_green_decimalForms() {
        assertThat(FilterColumnTypeEnum.NUMBER.normalize("1")).isEqualTo(new BigDecimal("1"));
        assertThat(FilterColumnTypeEnum.NUMBER.normalize("-5")).isEqualTo(new BigDecimal("-5"));
        assertThat(FilterColumnTypeEnum.NUMBER.normalize("1.50")).isEqualTo(new BigDecimal("1.50"));
    }

    @Test
    void normalizeNumber_red_scientificAndGarbage() {
        // 禁科学计数（BigDecimal 可解析但正则拦）+ 非纯十进制 + null
        assertThatThrownBy(() -> FilterColumnTypeEnum.NUMBER.normalize("1e5"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterColumnTypeEnum.NUMBER.normalize("abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterColumnTypeEnum.NUMBER.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeString_green_underLimit() {
        assertThat(FilterColumnTypeEnum.STRING.normalize("leave")).isEqualTo("leave");
        assertThat(FilterColumnTypeEnum.STRING.normalize("a".repeat(50))).isNotNull();
    }

    @Test
    void normalizeString_red_overLimit() {
        assertThatThrownBy(() -> FilterColumnTypeEnum.STRING.normalize("a".repeat(51)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterColumnTypeEnum.STRING.normalize(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeDate_greenIso() {
        assertThat(FilterColumnTypeEnum.DATE.normalize("2026-01-01"))
                .isEqualTo(LocalDate.of(2026, 1, 1));
    }

    @Test
    void normalizeDate_red_formats() {
        assertThatThrownBy(() -> FilterColumnTypeEnum.DATE.normalize("2026/01/01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterColumnTypeEnum.DATE.normalize("2026-01-01 00:00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void normalizeDateTime_green() {
        assertThat(FilterColumnTypeEnum.DATETIME.normalize("2026-01-01 00:00:00"))
                .isEqualTo(LocalDateTime.of(2026, 1, 1, 0, 0, 0));
    }

    @Test
    void normalizeDateTime_red() {
        assertThatThrownBy(() -> FilterColumnTypeEnum.DATETIME.normalize("2026-01-01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FilterColumnTypeEnum.DATETIME.normalize("2026-01-01T00:00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- format 逆变换（provider toScopeVo 回写 wire 字符串） ----

    @Test
    void format_roundTrip() {
        assertThat(FilterColumnTypeEnum.NUMBER.format(new BigDecimal("1"))).isEqualTo("1");
        assertThat(FilterColumnTypeEnum.NUMBER.format(new BigDecimal("1.50"))).isEqualTo("1.50");
        assertThat(FilterColumnTypeEnum.STRING.format("leave")).isEqualTo("leave");
        assertThat(FilterColumnTypeEnum.DATE.format(LocalDate.of(2026, 1, 1))).isEqualTo("2026-01-01");
        assertThat(FilterColumnTypeEnum.DATETIME.format(LocalDateTime.of(2026, 1, 1, 12, 30, 5)))
                .isEqualTo("2026-01-01 12:30:05");
        assertThat(FilterColumnTypeEnum.NUMBER.format(null)).isNull();
    }
}
