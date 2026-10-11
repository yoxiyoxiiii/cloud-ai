package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 操作符封闭集 v2 单测（设计 D41，2026-10-11 操作符扩展轮）：九枚举 of 解析红绿、symbol/label、
 * arity 期望值数（3037 校验依据）+ 操作符×类型矩阵终态 4×9 全量穷举（3036 校验依据——
 * STRING×{EQ,IN,NE,PREFIX}、{NUMBER,DATE,DATETIME}×除 PREFIX 外八操作符）。
 * 旧版「GT 落选」负例随 D41 解禁废止（负例换 REGEXP——契约 §1.1 明确不在值域）。
 */
class PredicateOperatorEnumTest {

    @Test
    void of_validNames_parsed() {
        assertThat(PredicateOperatorEnum.of("EQ")).isEqualTo(PredicateOperatorEnum.EQ);
        assertThat(PredicateOperatorEnum.of("IN")).isEqualTo(PredicateOperatorEnum.IN);
        assertThat(PredicateOperatorEnum.of("BETWEEN")).isEqualTo(PredicateOperatorEnum.BETWEEN);
        assertThat(PredicateOperatorEnum.of("GTE")).isEqualTo(PredicateOperatorEnum.GTE);
        assertThat(PredicateOperatorEnum.of("LTE")).isEqualTo(PredicateOperatorEnum.LTE);
        assertThat(PredicateOperatorEnum.of("GT")).isEqualTo(PredicateOperatorEnum.GT);
        assertThat(PredicateOperatorEnum.of("LT")).isEqualTo(PredicateOperatorEnum.LT);
        assertThat(PredicateOperatorEnum.of("NE")).isEqualTo(PredicateOperatorEnum.NE);
        assertThat(PredicateOperatorEnum.of("PREFIX")).isEqualTo(PredicateOperatorEnum.PREFIX);
    }

    @Test
    void of_unknownName_illegalArgument() {
        // 仍不在值域的操作符与任意垃圾串（消费方转 1002「操作符无效」）：
        // LIKE 中缀/后缀模糊、REGEXP、IS-NULL（契约 §1.1「明确不在值域」）
        assertThatThrownBy(() -> PredicateOperatorEnum.of("LIKE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LIKE");
        assertThatThrownBy(() -> PredicateOperatorEnum.of("REGEXP"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PredicateOperatorEnum.of(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void symbols_asciiDigestForm() {
        // digest 摘要符号（契约 §1.6：ASCII 紧凑型，扩展轮新增 > < != ^ 四符号，D41）
        assertThat(PredicateOperatorEnum.EQ.getSymbol()).isEqualTo("=");
        assertThat(PredicateOperatorEnum.IN.getSymbol()).isEqualTo("in");
        assertThat(PredicateOperatorEnum.BETWEEN.getSymbol()).isEqualTo("~");
        assertThat(PredicateOperatorEnum.GTE.getSymbol()).isEqualTo(">=");
        assertThat(PredicateOperatorEnum.LTE.getSymbol()).isEqualTo("<=");
        assertThat(PredicateOperatorEnum.GT.getSymbol()).isEqualTo(">");
        assertThat(PredicateOperatorEnum.LT.getSymbol()).isEqualTo("<");
        assertThat(PredicateOperatorEnum.NE.getSymbol()).isEqualTo("!=");
        assertThat(PredicateOperatorEnum.PREFIX.getSymbol()).isEqualTo("^");
    }

    @Test
    void labels_chinese() {
        // 与前端 OPERATOR_OPTIONS 双处冻结（任一变更为契约变更；扩展轮 +大于/小于/不等于/前缀）
        assertThat(PredicateOperatorEnum.EQ.getLabel()).isEqualTo("等于");
        assertThat(PredicateOperatorEnum.IN.getLabel()).isEqualTo("属于");
        assertThat(PredicateOperatorEnum.BETWEEN.getLabel()).isEqualTo("介于");
        assertThat(PredicateOperatorEnum.GTE.getLabel()).isEqualTo("大于等于");
        assertThat(PredicateOperatorEnum.LTE.getLabel()).isEqualTo("小于等于");
        assertThat(PredicateOperatorEnum.GT.getLabel()).isEqualTo("大于");
        assertThat(PredicateOperatorEnum.LT.getLabel()).isEqualTo("小于");
        assertThat(PredicateOperatorEnum.NE.getLabel()).isEqualTo("不等于");
        assertThat(PredicateOperatorEnum.PREFIX.getLabel()).isEqualTo("前缀");
    }

    @Test
    void arity_expectations() {
        assertThat(PredicateOperatorEnum.EQ.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.GTE.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.LTE.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.BETWEEN.getArity()).isEqualTo(2);
        assertThat(PredicateOperatorEnum.IN.getArity()).isEqualTo(-1);
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
    void supports_terminalMatrix_exhaustive() {
        // D41 矩阵终态 4×9=36 组合穷举：STRING×{EQ,IN,NE,PREFIX}；其余类型除 PREFIX 全开
        for (PredicateOperatorEnum op : PredicateOperatorEnum.values()) {
            boolean stringAllowed = op == PredicateOperatorEnum.EQ || op == PredicateOperatorEnum.IN
                    || op == PredicateOperatorEnum.NE || op == PredicateOperatorEnum.PREFIX;
            assertThat(op.supports(FilterColumnTypeEnum.STRING))
                    .as("STRING × %s 应为 %s", op, stringAllowed)
                    .isEqualTo(stringAllowed);
            for (FilterColumnTypeEnum type : new FilterColumnTypeEnum[]{
                    FilterColumnTypeEnum.NUMBER, FilterColumnTypeEnum.DATE, FilterColumnTypeEnum.DATETIME}) {
                assertThat(op.supports(type))
                        .as("%s × %s 应为 %s", op, type, op != PredicateOperatorEnum.PREFIX)
                        .isEqualTo(op != PredicateOperatorEnum.PREFIX);
            }
        }
    }

    @Test
    void arity_newOperators_singleValue() {
        // GT/LT/NE/PREFIX 均单值（契约 §1.2 增量；值数校验经 getArity 自动生效，D46）
        assertThat(PredicateOperatorEnum.GT.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.LT.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.NE.getArity()).isEqualTo(1);
        assertThat(PredicateOperatorEnum.PREFIX.getArity()).isEqualTo(1);
    }
}
