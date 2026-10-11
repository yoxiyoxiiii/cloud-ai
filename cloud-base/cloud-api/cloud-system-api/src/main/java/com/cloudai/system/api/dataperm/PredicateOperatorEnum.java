package com.cloudai.system.api.dataperm;

/**
 * 行级条件操作符封闭集 v2（设计 D41，2026-10-11 操作符扩展轮）：EQ/IN/BETWEEN/GTE/LTE +
 * GT/LT/NE/PREFIX 九枚举——顶层枚举落 api 模块（不在 system entity 扫描面，守护测试 Enum 后缀规则不适用）。
 *
 * <p>扩展轮记档（设计 §1.2/D41）：GT/LT（比较族补全——严格不等开边界）、NE（「除某值外全部」
 * 对新增值封闭，免 IN 列举漏更新）、PREFIX（右模糊 {@code LIKE 'x%'}——D29「模糊操作符整体不做」
 * 排除面仅开右前缀一道口）。仍不落：LIKE 中缀/后缀模糊、REGEXP、IS-NULL（契约 §1.1）。
 *
 * <p>code 摘要符号（digest 用 ASCII 紧凑型，契约 §1.6：{@code =} {@code in} {@code ~}
 * {@code >=} {@code <=} {@code >} {@code <} {@code !=} {@code ^}——PREFIX 符号取正则起始锚语义）；
 * label 中文与前端 OPERATOR_OPTIONS 双处冻结（任一变更为契约变更）。
 * 类型矩阵终态（{@link #supports}，配置校验 3036 依据）：NUMBER/DATE/DATETIME × 除 PREFIX 外八操作符
 * （数值/日期「前缀」是字符串化比较，无索引价值语义怪异）；STRING × EQ/IN/NE/PREFIX 四操作符
 * （ci 排序规则下字符串大小比较语义不稳，禁比较符类，D29 口径不变）。
 */
public enum PredicateOperatorEnum {

    /** 等于（单值） */
    EQ("=", "等于"),

    /** 属于（值集合任一命中，1~30 值） */
    IN("in", "属于"),

    /** 介于（闭区间，两值有序） */
    BETWEEN("~", "介于"),

    /** 大于等于（闭端） */
    GTE(">=", "大于等于"),

    /** 小于等于（闭端） */
    LTE("<=", "小于等于"),

    /** 大于（严格不等，开边界——边界值不命中，与 GTE 差异，设计 D44） */
    GT(">", "大于"),

    /** 小于（严格不等，开边界） */
    LT("<", "小于"),

    /** 不等于（等值求反，排除值——对新增值封闭，免 IN 列举漏更新；NULL 恒不满足含 NE） */
    NE("!=", "不等于"),

    /** 前缀（右模糊 {@code LIKE 'x%'}；值内 %/_/| 一律字面——SQL 侧三层竖线 REPLACE 转义链，设计 D43） */
    PREFIX("^", "前缀");

    private final String symbol;
    private final String label;

    PredicateOperatorEnum(String symbol, String label) {
        this.symbol = symbol;
        this.label = label;
    }

    /** digest/摘要符号（ASCII，契约 §1.6） */
    public String getSymbol() {
        return symbol;
    }

    /** 中文标签（配置台摘要/前端下拉共用，双处冻结点） */
    public String getLabel() {
        return label;
    }

    /** 期望值数量：EQ/GTE/LTE/GT/LT/NE/PREFIX=1、BETWEEN=2、IN 变长（1~30 上限由配置校验 3037 把守） */
    public int getArity() {
        return switch (this) {
            case EQ, GTE, LTE, GT, LT, NE, PREFIX -> 1;
            case BETWEEN -> 2;
            case IN -> -1;
        };
    }

    /** 操作符×列类型矩阵终态（D41，校验 3036 依据）：STRING 仅 EQ/IN/NE/PREFIX，其余类型除 PREFIX 全开 */
    public boolean supports(FilterColumnTypeEnum type) {
        return switch (type) {
            case STRING -> this == EQ || this == IN || this == NE || this == PREFIX;
            case NUMBER, DATE, DATETIME -> this != PREFIX;
        };
    }

    /**
     * name 解析（配置态前置校验用）：非枚举 name（含 null）→ IllegalArgumentException
     * （消费方 catch 转业务码 1002「操作符无效」；求值/测试期消费走 RowPredicate 内部防御解析）。
     */
    public static PredicateOperatorEnum of(String name) {
        if (name == null) {
            throw new IllegalArgumentException("操作符无效: null");
        }
        try {
            return PredicateOperatorEnum.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("操作符无效: " + name);
        }
    }
}
