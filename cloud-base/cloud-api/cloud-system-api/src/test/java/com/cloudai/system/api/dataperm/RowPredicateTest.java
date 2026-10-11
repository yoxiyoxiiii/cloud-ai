package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 谓词终态判定单测（设计 D34 五对齐规则，2026-10-11 谓词轮核心测试面）：与 MySQL 语义逐条
 * 对齐——null 恒不满足；NUMBER 双形态归一 + BigDecimal 精度无关；DATE/DATETIME 解析比较；
 * STRING ci + 尾随空格不敏感；EQ/IN 任一/BETWEEN 闭区间/GTE/LTE 全操作符；IN 空集不命中。
 */
class RowPredicateTest {

    private static RowPredicate numberPredicate(PredicateOperatorEnum op, String... rawValues) {
        List<Object> values = List.of(rawValues).stream()
                .map(v -> FilterColumnTypeEnum.NUMBER.normalize(v))
                .toList();
        return RowPredicate.of("id", "id", op, values);
    }

    private static RowPredicate stringPredicate(PredicateOperatorEnum op, String... rawValues) {
        List<Object> values = List.of(rawValues);
        return RowPredicate.of("businessType", "business_type", op, values);
    }

    private static RowPredicate datePredicate(PredicateOperatorEnum op, String... rawValues) {
        List<Object> values = List.of(rawValues).stream()
                .map(v -> FilterColumnTypeEnum.DATE.normalize(v))
                .toList();
        return RowPredicate.of("startDate", "start_date", op, values);
    }

    private static RowPredicate dateTimePredicate(PredicateOperatorEnum op, String... rawValues) {
        List<Object> values = List.of(rawValues).stream()
                .map(v -> FilterColumnTypeEnum.DATETIME.normalize(v))
                .toList();
        return RowPredicate.of("createTime", "create_time", op, values);
    }

    // ---- 规则①：NULL 恒不满足（全类型全操作符） ----

    @Test
    void nullEntityValue_neverSatisfied() {
        assertThat(numberPredicate(PredicateOperatorEnum.EQ, "1").test(null)).isFalse();
        assertThat(stringPredicate(PredicateOperatorEnum.IN, "leave").test(null)).isFalse();
        assertThat(datePredicate(PredicateOperatorEnum.GTE, "2026-01-01").test(null)).isFalse();
        assertThat(dateTimePredicate(PredicateOperatorEnum.BETWEEN, "2026-01-01 00:00:00",
                "2026-12-31 23:59:59").test(null)).isFalse();
    }

    @Test
    void emptyValues_neverSatisfied_defensive() {
        // IN 空值组（toRowFilter fail-closed 降级占位形态）恒不命中
        assertThat(RowPredicate.of("id", "id", PredicateOperatorEnum.IN, List.of()).test(1L)).isFalse();
    }

    // ---- 规则②：NUMBER 双形态归一 + BigDecimal 精度无关 ----

    @Test
    void number_typedForms_equivalent() {
        RowPredicate predicate = numberPredicate(PredicateOperatorEnum.EQ, "1");

        // 实体 Long/Integer、VO 字符串 "1" 三形态归一等值
        assertThat(predicate.test(1L)).isTrue();
        assertThat(predicate.test(1)).isTrue();
        assertThat(predicate.test("1")).isTrue();
        assertThat(predicate.test(2L)).isFalse();
    }

    @Test
    void number_bigDecimalScaleInsensitive() {
        RowPredicate predicate = numberPredicate(PredicateOperatorEnum.EQ, "1");

        // compareTo 精度无关：1.00 == 1（equals 则为 false——刻意用 compareTo 对齐 SQL 数值比较）
        assertThat(predicate.test(new BigDecimal("1.00"))).isTrue();
        assertThat(predicate.test(new BigDecimal("1.01"))).isFalse();
    }

    @Test
    void number_unparseableEntityValue_notSatisfied() {
        RowPredicate predicate = numberPredicate(PredicateOperatorEnum.EQ, "1");

        // VO 脏字符串不可归一 → false（fail-closed 方向）
        assertThat(predicate.test("abc")).isFalse();
        assertThat(predicate.test(LocalDate.of(2026, 1, 1))).isFalse();
    }

    // ---- 规则③：DATE/DATETIME 解析与比较 ----

    @Test
    void date_stringAndTyped_equivalent() {
        RowPredicate predicate = datePredicate(PredicateOperatorEnum.EQ, "2026-01-15");

        assertThat(predicate.test(LocalDate.of(2026, 1, 15))).isTrue();
        assertThat(predicate.test("2026-01-15")).isTrue();
        assertThat(predicate.test("2026-01-16")).isFalse();
    }

    @Test
    void date_between_closedInterval() {
        RowPredicate predicate = datePredicate(PredicateOperatorEnum.BETWEEN, "2026-01-01", "2026-01-31");

        assertThat(predicate.test(LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(predicate.test(LocalDate.of(2026, 1, 31))).isTrue();
        assertThat(predicate.test(LocalDate.of(2026, 1, 15))).isTrue();
        assertThat(predicate.test(LocalDate.of(2025, 12, 31))).isFalse();
        assertThat(predicate.test(LocalDate.of(2026, 2, 1))).isFalse();
    }

    @Test
    void dateTime_gte_closedEnd() {
        RowPredicate predicate = dateTimePredicate(PredicateOperatorEnum.GTE, "2026-01-01 00:00:00");

        assertThat(predicate.test(LocalDateTime.of(2026, 1, 1, 0, 0, 0))).isTrue();
        assertThat(predicate.test(LocalDateTime.of(2026, 6, 15, 12, 30, 0))).isTrue();
        assertThat(predicate.test(LocalDateTime.of(2025, 12, 31, 23, 59, 59))).isFalse();
    }

    @Test
    void dateTime_localDateEntityValue_coercedToStartOfDay() {
        RowPredicate predicate = dateTimePredicate(PredicateOperatorEnum.LTE, "2026-01-01 12:00:00");

        // LocalDate 实体值取日起点归一（防御性宽容，不放大边界）
        assertThat(predicate.test(LocalDate.of(2026, 1, 1))).isTrue();
        assertThat(predicate.test(LocalDate.of(2026, 1, 2))).isFalse();
    }

    // ---- 规则④：STRING ci + 尾随空格不敏感（仅 EQ/IN） ----

    @Test
    void string_caseInsensitiveAndTrailingSpace() {
        RowPredicate predicate = stringPredicate(PredicateOperatorEnum.EQ, "Leave");

        // "ABC " vs "abc" 命中：大小写不敏感 + 尾随空格不敏感（对齐 utf8mb4_general_ci PAD SPACE ci）
        assertThat(predicate.test("leave")).isTrue();
        assertThat(predicate.test("LEAVE")).isTrue();
        assertThat(predicate.test("leave ")).isTrue();
        assertThat(predicate.test(" leave")).isFalse();
        assertThat(predicate.test("leaves")).isFalse();
    }

    @Test
    void string_between_defensiveFalse() {
        // STRING 矩阵外组合（配置态 3036 已拦，测试器防御恒 false——不引入字符串大小比较语义）
        RowPredicate predicate = stringPredicate(PredicateOperatorEnum.BETWEEN, "a", "z");

        assertThat(predicate.test("m")).isFalse();
    }

    // ---- 规则⑤：操作符应用（IN 任一 / BETWEEN 闭区间 / GTE / LTE） ----

    @Test
    void in_anyValueMatches() {
        RowPredicate predicate = numberPredicate(PredicateOperatorEnum.IN, "1", "3", "5");

        assertThat(predicate.test(1L)).isTrue();
        assertThat(predicate.test(5)).isTrue();
        assertThat(predicate.test("3")).isTrue();
        assertThat(predicate.test(4L)).isFalse();
    }

    @Test
    void between_closedIntervalBothEnds() {
        RowPredicate predicate = numberPredicate(PredicateOperatorEnum.BETWEEN, "1", "100");

        assertThat(predicate.test(1L)).isTrue();
        assertThat(predicate.test(100L)).isTrue();
        assertThat(predicate.test(50)).isTrue();
        assertThat(predicate.test(0L)).isFalse();
        assertThat(predicate.test(101L)).isFalse();
    }

    @Test
    void gteAndLte_closedComparison() {
        RowPredicate gte = numberPredicate(PredicateOperatorEnum.GTE, "10");
        RowPredicate lte = numberPredicate(PredicateOperatorEnum.LTE, "10");

        assertThat(gte.test(10L)).isTrue();
        assertThat(gte.test(11L)).isTrue();
        assertThat(gte.test(9L)).isFalse();
        assertThat(lte.test(10L)).isTrue();
        assertThat(lte.test(9L)).isTrue();
        assertThat(lte.test(11L)).isFalse();
    }

    @Test
    void stringIn_anyValueMatches_ci() {
        RowPredicate predicate = stringPredicate(PredicateOperatorEnum.IN, "leave", "approval");

        assertThat(predicate.test("LEAVE ")).isTrue();
        assertThat(predicate.test("approval")).isTrue();
        assertThat(predicate.test("todo")).isFalse();
    }
}
