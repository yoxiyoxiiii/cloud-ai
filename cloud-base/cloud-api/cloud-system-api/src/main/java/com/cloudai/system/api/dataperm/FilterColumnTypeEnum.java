package com.cloudai.system.api.dataperm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * 可过滤列类型枚举（设计 D32/D38，2026-10-11 谓词轮）：NUMBER/STRING/DATE/DATETIME 四型。
 *
 * <p><b>归一器单一实现（D34）</b>：{@link #normalize(String)} 是「配置值字符串 → 类型化对象」的
 * 唯一解析点，两处消费——system 配置校验（3037 拦截，格式非法即 IllegalArgumentException）与
 * api 模块 toRowFilter 终态转换（求值/测试期非法值理论不可达，DB 脏数据按不满足处理，D39）。
 * {@link #format(Object)} 为其逆（类型化 → wire 字符串，provider toScopeVo 回写用）。
 *
 * <p>值格式（契约 §1.3）：NUMBER 正则 {@code ^-?\d+(\.\d+)?$}（BigDecimal 可解析且禁科学计数）；
 * STRING 长度 ≤50（比较语义=大小写不敏感+尾随空格不敏感，对齐 MySQL utf8mb4_general_ci）；
 * DATE {@code yyyy-MM-dd}；DATETIME {@code yyyy-MM-dd HH:mm:ss}。不校验字典值域
 * （leaveType=9 合法但永不命中——explain 可排查，拍板记档）。
 */
public enum FilterColumnTypeEnum {

    /** 数值（BIGINT/TINYINT/INT 列；值归一 BigDecimal，compareTo 精度无关比较） */
    NUMBER("数值"),

    /** 字符串（VARCHAR 列；仅 EQ/IN，ci + 尾随空格不敏感比较） */
    STRING("字符串"),

    /** 日期（DATE 列；yyyy-MM-dd） */
    DATE("日期"),

    /** 日期时间（DATETIME 列；yyyy-MM-dd HH:mm:ss） */
    DATETIME("日期时间");

    /** NUMBER 值正则（禁科学计数；契约 §1.3 / 设计 D38） */
    private static final Pattern NUMBER_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    /** DATE 线格式（与 Jackson 全局日期格式对齐口径） */
    public static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** DATETIME 线格式（与 Jackson 全局时间格式对齐口径） */
    public static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** STRING 值长度上限（契约 §1.3，超限 3037） */
    public static final int STRING_VALUE_MAX = 50;

    private final String label;

    FilterColumnTypeEnum(String label) {
        this.label = label;
    }

    /** 中文标签 */
    public String getLabel() {
        return label;
    }

    /**
     * 声明类型 × entity 字段 Java 类型匹配（FilterColumns.assertDeclared 断言依据，D32）：
     * NUMBER→Long/Integer（含原生）、STRING→String、DATE→LocalDate、DATETIME→LocalDateTime。
     */
    public boolean matches(Class<?> fieldType) {
        return switch (this) {
            case NUMBER -> fieldType == Long.class || fieldType == long.class
                    || fieldType == Integer.class || fieldType == int.class;
            case STRING -> fieldType == String.class;
            case DATE -> fieldType == LocalDate.class;
            case DATETIME -> fieldType == LocalDateTime.class;
        };
    }

    /**
     * 值字符串归一（单一实现，D34）：NUMBER→BigDecimal（正则校验）、STRING→原串（长度校验）、
     * DATE→LocalDate、DATETIME→LocalDateTime。违例（null/格式非法/超长）→ IllegalArgumentException
     * （配置态由 system 消费方转 3037）。
     */
    public Object normalize(String value) {
        return switch (this) {
            case NUMBER -> normalizeNumber(value);
            case STRING -> normalizeString(value);
            case DATE -> normalizeDate(value);
            case DATETIME -> normalizeDateTime(value);
        };
    }

    /**
     * 归一逆变换（provider 侧 toScopeVo 回写 wire 字符串）：NUMBER toPlainString 保标度、
     * DATE/DATETIME 按线格式、STRING 原样；null 直返 null。
     */
    public String format(Object typedValue) {
        if (typedValue == null) {
            return null;
        }
        return switch (this) {
            case NUMBER -> ((BigDecimal) typedValue).toPlainString();
            case STRING -> (String) typedValue;
            case DATE -> ((LocalDate) typedValue).format(DATE_FORMATTER);
            case DATETIME -> ((LocalDateTime) typedValue).format(DATETIME_FORMATTER);
        };
    }

    private BigDecimal normalizeNumber(String value) {
        if (value == null || !NUMBER_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("NUMBER 值非法: " + value);
        }
        return new BigDecimal(value);
    }

    private String normalizeString(String value) {
        if (value == null || value.length() > STRING_VALUE_MAX) {
            throw new IllegalArgumentException("STRING 值超长(≤" + STRING_VALUE_MAX + "): " + value);
        }
        return value;
    }

    private LocalDate normalizeDate(String value) {
        try {
            return LocalDate.parse(value, DATE_FORMATTER);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("DATE 值非法: " + value);
        }
    }

    private LocalDateTime normalizeDateTime(String value) {
        try {
            return LocalDateTime.parse(value, DATETIME_FORMATTER);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("DATETIME 值非法: " + value);
        }
    }
}
