package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 操作符封闭集单测（设计 D29，2026-10-11 谓词轮）：of 解析红绿、symbol/label、
 * 操作符×类型矩阵（3036 校验依据）、arity 期望值数（3037 校验依据）。
 */
class PredicateOperatorEnumTest {

    @Test
    void of_validNames_parsed() {
        assertThat(PredicateOperatorEnum.of("EQ")).isEqualTo(PredicateOperatorEnum.EQ);
        assertThat(PredicateOperatorEnum.of("IN")).isEqualTo(PredicateOperatorEnum.IN);
        assertThat(PredicateOperatorEnum.of("BETWEEN")).isEqualTo(PredicateOperatorEnum.BETWEEN);
        assertThat(PredicateOperatorEnum.of("GTE")).isEqualTo(PredicateOperatorEnum.GTE);
        assertThat(PredicateOperatorEnum.of("LTE")).isEqualTo(PredicateOperatorEnum.LTE);
    }

    @Test
    void of_unknownName_illegalArgument() {
        // 落选操作符与任意垃圾串（消费方转 1002「操作符无效」）
        assertThatThrownBy(() -> PredicateOperatorEnum.of("LIKE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIKE");
        assertThatThrownBy(() -> PredicateOperatorEnum.of("GT"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateOperatorEnum.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void symbols_asciiDigestForm() {
        // digest 摘要符号（契约 §1.6：ASCII 紧凑型，如 id:1~100 / leave_type=1）
        assertThat(PredicateOperatorEnum.EQ.getSymbol()).isEqualTo("=");
        assertThat(PredicateOperatorEnum.IN.getSymbol()).isEqualTo("in");
        assertThat(PredicateOperatorEnum.BETWEEN.getSymbol()).isEqualTo("~");
        assertThat(PredicateOperatorEnum.GTE.getSymbol()).isEqualTo(">=");
        assertThat(PredicateOperatorEnum.LTE.getSymbol()).isEqualTo("<=");
    }

    @Test
    void labels_chinese() {
        // 与前端 OPERATOR_OPTIONS 双处冻结（任一变更为契约变更）
        assertThat(PredicateOperatorEnum.EQ.getLabel()).isEqualTo("等于");
        assertThat(PredicateOperatorEnum.IN.getLabel()).isEqualTo("属于");
        assertThat(PredicateOperatorEnum.BETWEEN.getLabel()).isEqualTo("介于");
        assertThat(PredicateOperatorEnum.GTE.getLabel()).isEqualTo("大于等于");
        assertThat(PredicateOperatorEnum.LTE.getLabel()).isEqualTo("小于等于");
    }

    @Test
    void supports_stringTypeOnlyEqAndIn() {
        assertThat(PredicateOperatorEnum.EQ.supports(FilterColumnTypeEnum.STRING)).isTrue();
        assertThat(PredicateOperatorEnum.IN.supports(FilterColumnTypeEnum.STRING)).isTrue();
        assertThat(PredicateOperatorEnum.BETWEEN.supports(FilterColumnTypeEnum.STRING)).isFalse();
        assertThat(PredicateOperatorEnum.GTE.supports(FilterColumnTypeEnum.STRING)).isFalse();
        assertThat(PredicateOperatorEnum.LTE.supports(FilterColumnTypeEnum.STRING)).isFalse();
    }

    @Test
    void supports_nonStringTypes_allOperators() {
        for (FilterColumnTypeEnum type : new FilterColumnTypeEnum[]{
                FilterColumnTypeEnum.NUMBER, FilterColumnTypeEnum.DATE, FilterColumnTypeEnum.DATETIME}) {
            for (PredicateOperatorEnum op : PredicateOperatorEnum.values()) {
                assertThat(op.supports(type))
                        .as("%s × %s 应合法", op, type)
                        .isTrue();
            }
        }
    }

    @Test
    void arity_expectations() {
        assertThat(PredicateOperatorEnum.EQ.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.GTE.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.LTE.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.BETWEEN.getArity()).isEqualTo(2);
        assertThat(PredicateOperatorEnum.IN.getArity()).isEqualTo(-1);
    }
}
