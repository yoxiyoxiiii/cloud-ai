package com.cloudai.system.api.dataperm;

/**
 * 行级条件操作符封闭集（设计 D29，2026-10-11 谓词轮）：EQ/IN/BETWEEN/GTE/LTE 五枚举——
 * 顶层枚举落 api 模块（不在 system entity 扫描面，守护测试 Enum 后缀规则不适用）。
 *
 * <p>落选记档（设计 §1.3/D29）：LIKE/PREFIX 一切模糊操作符（拍板①首版不做）、GT/LT（GTE/LTE
 * 已覆盖单边区间）、NE（白名单思维下可用 IN 其余值替代）、IS-NULL（试点列全 NOT NULL）。
 *
 * <p>code 摘要符号（digest 用 ASCII 紧凑型，契约 §1.6：{@code =} {@code in} {@code ~}
 * {@code >=} {@code <=}）；label 中文与前端 OPERATOR_OPTIONS 双处冻结（任一变更为契约变更）。
 * 类型矩阵（{@link #supports}，配置校验 3036 依据）：NUMBER/DATE/DATETIME × 全五操作符；
 * STRING × 仅 EQ/IN（ci 排序规则下字符串大小比较语义不稳，禁比较符类）。
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
    LTE("<=", "小于等于");

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

    /** 期望值数量：EQ/GTE/LTE=1、BETWEEN=2、IN 变长（1~30 上限由配置校验 3037 把守） */
    public int getArity() {
        return switch (this) {
            case EQ, GTE, LTE -> 1;
            case BETWEEN -> 2;
            case IN -> -1;
        };
    }

    /** 操作符×列类型矩阵（D38 校验依据）：STRING 仅 EQ/IN，其余类型全五操作符合法 */
    public boolean supports(FilterColumnTypeEnum type) {
        if (type == FilterColumnTypeEnum.STRING) {
            return this == EQ || this == IN;
        }
        return true;
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
