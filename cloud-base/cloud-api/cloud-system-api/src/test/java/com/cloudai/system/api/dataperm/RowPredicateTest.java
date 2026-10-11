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

    // ---- 扩展轮（D43/D44，2026-10-11）：GT/LT 严格不等、NE 求反、PREFIX 前缀与矩阵外防御 ----

    @Test
    void number_gtLt_strictBoundary() {
        RowPredicate gt = numberPredicate(PredicateOperatorEnum.GT, "10");
        RowPredicate lt = numberPredicate(PredicateOperatorEnum.LT, "10");

        // 开边界：边界值 10 不命中（与 GTE/LTE 闭端成对差异，设计 D44）
        assertThat(gt.test(10L)).isFalse();
        assertThat(gt.test(11L)).isTrue();
        assertThat(gt.test(new BigDecimal("10.5"))).isTrue();
        assertThat(lt.test(10L)).isFalse();
        assertThat(lt.test(9L)).isTrue();
    }

    @Test
    void number_gt_typedForms_equivalent() {
        RowPredicate predicate = numberPredicate(PredicateOperatorEnum.GT, "10");

        // VO 字符串形态经 NUMBER 归一器后同样参与严格比较
        assertThat(predicate.test("11")).isTrue();
        assertThat(predicate.test("10")).isFalse();
        assertThat(predicate.test(new BigDecimal("10.0001"))).isTrue();
    }

    @Test
    void number_ne_inversion() {
        RowPredicate predicate = numberPredicate(PredicateOperatorEnum.NE, "2");

        assertThat(predicate.test(1L)).isTrue();
        assertThat(predicate.test(3)).isTrue();
        assertThat(predicate.test("2")).isFalse();
    }

    @Test
    void number_neNull_neverSatisfied() {
        // MySQL NULL != 1 不命中（探针 P7）：NE 与其他操作符同向 fail-closed
        assertThat(numberPredicate(PredicateOperatorEnum.NE, "1").test(null)).isFalse();
    }

    @Test
    void number_prefix_matrixOutDefensiveFalse() {
        // NUMBER×PREFIX 矩阵外组合（配置态 3036 已拦）测试器防御恒 false
        RowPredicate predicate = numberPredicate(PredicateOperatorEnum.PREFIX, "10");

        assertThat(predicate.test(100L)).isFalse();
        assertThat(predicate.test(10L)).isFalse();
    }

    @Test
    void date_gtLt_strictBoundary() {
        RowPredicate gt = datePredicate(PredicateOperatorEnum.GT, "2026-01-15");
        RowPredicate lt = datePredicate(PredicateOperatorEnum.LT, "2026-01-15");

        assertThat(gt.test(LocalDate.of(2026, 1, 15))).isFalse();
        assertThat(gt.test(LocalDate.of(2026, 1, 16))).isTrue();
        assertThat(lt.test(LocalDate.of(2026, 1, 15))).isFalse();
        assertThat(lt.test(LocalDate.of(2026, 1, 14))).isTrue();
    }

    @Test
    void date_ne_inversion() {
        RowPredicate predicate = datePredicate(PredicateOperatorEnum.NE, "2026-01-15");

        assertThat(predicate.test(LocalDate.of(2026, 1, 14))).isTrue();
        assertThat(predicate.test("2026-01-15")).isFalse();
    }

    @Test
    void dateAndDateTime_prefix_matrixOutDefensiveFalse() {
        // 值仍为合法类型化形态（配置态矩阵 3036 已拦此类组合，此处仅验证测试器 switch 防御分支）
        assertThat(datePredicate(PredicateOperatorEnum.PREFIX, "2026-01-01")
                .test(LocalDate.of(2026, 1, 1))).isFalse();
        assertThat(dateTimePredicate(PredicateOperatorEnum.PREFIX, "2026-01-01 00:00:00")
                .test(LocalDateTime.of(2026, 1, 1, 0, 0))).isFalse();
    }

    @Test
    void dateTime_gtLtStrict_andNe() {
        RowPredicate gt = dateTimePredicate(PredicateOperatorEnum.GT, "2026-01-01 12:00:00");
        RowPredicate lt = dateTimePredicate(PredicateOperatorEnum.LT, "2026-01-01 12:00:00");
        RowPredicate ne = dateTimePredicate(PredicateOperatorEnum.NE, "2026-01-01 12:00:00");

        assertThat(gt.test(LocalDateTime.of(2026, 1, 1, 12, 0, 0))).isFalse();
        assertThat(gt.test(LocalDateTime.of(2026, 1, 1, 12, 0, 1))).isTrue();
        assertThat(lt.test(LocalDateTime.of(2026, 1, 1, 12, 0, 0))).isFalse();
        assertThat(lt.test(LocalDateTime.of(2026, 1, 1, 11, 59, 59))).isTrue();
        assertThat(ne.test(LocalDateTime.of(2026, 1, 1, 12, 0, 0))).isFalse();
        assertThat(ne.test(LocalDateTime.of(2026, 1, 1, 12, 0, 1))).isTrue();
    }

    @Test
    void string_ne_inversion_ciAndPad() {
        RowPredicate predicate = stringPredicate(PredicateOperatorEnum.NE, "abc");

        // 规则④求反（探针 P8）：等值判定（ci+stripTrailing）先成立再求反——"ABC " 视为等于 "abc"，NE 不命中
        assertThat(predicate.test("abc")).isFalse();
        assertThat(predicate.test("ABC")).isFalse();
        assertThat(predicate.test("ABC ")).isFalse();
        assertThat(predicate.test("abd")).isTrue();
    }

    @Test
    void string_neNull_neverSatisfied() {
        assertThat(stringPredicate(PredicateOperatorEnum.NE, "leave").test(null)).isFalse();
    }

    @Test
    void string_prefix_caseInsensitive() {
        RowPredicate predicate = stringPredicate(PredicateOperatorEnum.PREFIX, "E2e");

        assertThat(predicate.test("e2e-leave-1")).isTrue();
        assertThat(predicate.test("E2E-leave-1")).isTrue();
        assertThat(predicate.test(" leave")).isFalse();
        assertThat(predicate.test("xe2e")).isFalse();
    }

    @Test
    void string_prefix_entityTrailingSpace_hit() {
        // 探针 P4：LIKE 'e2e%' 命中列值 'e2e '（前缀比较长度有界，实体值尾随空格无影响）
        RowPredicate predicate = stringPredicate(PredicateOperatorEnum.PREFIX, "e2e");

        assertThat(predicate.test("e2e ")).isTrue();
    }

    @Test
    void string_prefix_prefixTrailingSpace_narrows() {
        // 探针 P5：配置前缀尾随空格是字面窄化——LIKE 'e2e %' 不命中 'e2e'（PREFIX 非 PAD SPACE，与规则④不对称）
        RowPredicate predicate = stringPredicate(PredicateOperatorEnum.PREFIX, "e2e ");

        assertThat(predicate.test("e2e")).isFalse();
        assertThat(predicate.test("e2e x")).isTrue();
    }

    @Test
    void string_prefix_wildcardsLiteral() {
        // %/_ 在测试器侧为字面字符（SQL 侧由三层 REPLACE 转义链保证，设计 D43）
        RowPredicate percent = stringPredicate(PredicateOperatorEnum.PREFIX, "e2e%");
        RowPredicate underscore = stringPredicate(PredicateOperatorEnum.PREFIX, "e2e_");

        assertThat(percent.test("e2e%x")).isTrue();
        assertThat(percent.test("e2ex")).isFalse();
        assertThat(underscore.test("e2e_x")).isTrue();
        assertThat(underscore.test("e2ex")).isFalse();
    }

    @Test
    void string_prefix_backslashLiteral() {
        // 反斜杠在 PreparedStatement 参数中零参与（值原样绑定），测试器侧同为字面
        RowPredicate predicate = stringPredicate(PredicateOperatorEnum.PREFIX, "e2e\\");

        assertThat(predicate.test("e2e\\x")).isTrue();
        assertThat(predicate.test("e2ex")).isFalse();
    }

    @Test
    void string_prefix_nullEntityValue_neverSatisfied() {
        assertThat(stringPredicate(PredicateOperatorEnum.PREFIX, "e2e").test(null)).isFalse();
    }

    @Test
    void string_comparisonOps_matrixOutDefensiveFalse() {
        // STRING×GTE/GT/LT 矩阵外组合（配置态 3036 已拦）测试器防御恒 false
        assertThat(stringPredicate(PredicateOperatorEnum.GTE, "a").test("b")).isFalse();
        assertThat(stringPredicate(PredicateOperatorEnum.GT, "a").test("b")).isFalse();
        assertThat(stringPredicate(PredicateOperatorEnum.LT, "z").test("b")).isFalse();
    }
}
