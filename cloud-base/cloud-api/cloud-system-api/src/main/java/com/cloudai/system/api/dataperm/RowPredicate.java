package com.cloudai.system.api.dataperm;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * 谓词终态（不可变，设计 D33/D34，2026-10-11 谓词轮）：{field, column, operator(枚举 name 字符串),
 * values(已类型化)}——operator 存字符串是 mapper XML OGNL 直比（{@code p.operator == 'EQ'}）
 * 免枚举边界的刻意取舍；values 经 {@link FilterColumnTypeEnum#normalize} 归一为
 * BigDecimal/LocalDate/LocalDateTime/String（类型化绑定消解 MySQL 隐式转型不确定性，D33）。
 *
 * <p>{@link #test(Object)} 与 MySQL 语义逐条对齐（D34 五规则 + 扩展轮 D43/D44 增量）：
 * ①NULL 恒不满足（含 NE——MySQL {@code NULL != 1} 不命中，探针 P7）；
 * ②NUMBER 双侧归一 BigDecimal compareTo（实体 Long/Integer、VO 字符串 "1" 双形态均归一）；
 * ③DATE/DATETIME 归一时间型 compareTo（字符串按声明类型线格式解析）；
 * ④STRING 大小写+尾随空格不敏感（stripTrailing+equalsIgnoreCase，对齐 utf8mb4_general_ci
 * PAD SPACE ci）；⑤操作符应用：EQ 单值、IN 任一、BETWEEN 闭区间、GTE/LTE 闭端、
 * GT/LT 严格不等（开边界，D44）、NE 等值求反（STRING×NE 为规则④求反，探针 P8）；
 * ⑥PREFIX startsWithIgnoreCase 双侧不 stripTrailing（LIKE 非 PAD SPACE，探针 P2-P5——
 * 与规则④刻意不对称记档，设计 D43）。
 * 防御方向恒 fail-closed：未知操作符/不可归一值/空值组/矩阵外组合 → false（不放大授权）。
 */
public final class RowPredicate {

    private final String field;
    private final String column;
    private final String operator;
    private final List<Object> values;

    private RowPredicate(String field, String column, String operator, List<Object> values) {
        this.field = field;
        this.column = column;
        this.operator = operator;
        this.values = List.copyOf(values);
    }

    /** 工厂：values 为归一后类型化对象（求值器 enrichment / ScopeVo.toRowFilter 构造） */
    public static RowPredicate of(String field, String column, PredicateOperatorEnum operator,
            List<Object> values) {
        return new RowPredicate(field, column, operator.name(), values);
    }

    public String getField() {
        return field;
    }

    /** 裸 DB 列名（XML 硬编码分支对照，无别名前缀） */
    public String getColumn() {
        return column;
    }

    /** 操作符枚举 name（字符串承载，XML OGNL 直比） */
    public String getOperator() {
        return operator;
    }

    /** 已类型化值（BigDecimal/LocalDate/LocalDateTime/String） */
    public List<Object> getValues() {
        return values;
    }

    /** SQL 语义对齐判定（D34 五规则；null/空值组/未知形态恒 false） */
    public boolean test(Object entityValue) {
        if (entityValue == null || values.isEmpty()) {
            return false;
        }
        Object probe = values.get(0);
        if (probe instanceof BigDecimal) {
            return testNumber(entityValue);
        }
        if (probe instanceof LocalDate || probe instanceof LocalDateTime) {
            return testTemporal(entityValue);
        }
        if (probe instanceof String) {
            return testString(entityValue);
        }
        return false;
    }

    /** NUMBER（规则②）：双侧归一 BigDecimal 后按操作符比较；不可归一 → false；PREFIX 矩阵外防御 false */
    private boolean testNumber(Object entityValue) {
        PredicateOperatorEnum op = parseOperator();
        BigDecimal x = toDecimal(entityValue);
        if (op == null || x == null) {
            return false;
        }
        List<BigDecimal> bounds = values.stream().map(RowPredicate::toDecimal).toList();
        if (bounds.stream().anyMatch(Objects::isNull)) {
            return false;
        }
        return switch (op) {
            case EQ -> x.compareTo(bounds.get(0)) == 0;
            case IN -> bounds.stream().anyMatch(b -> x.compareTo(b) == 0);
            case BETWEEN -> bounds.get(0).compareTo(x) <= 0 && x.compareTo(bounds.get(1)) <= 0;
            case GTE -> x.compareTo(bounds.get(0)) >= 0;
            case LTE -> x.compareTo(bounds.get(0)) <= 0;
            case GT -> x.compareTo(bounds.get(0)) > 0;
            case LT -> x.compareTo(bounds.get(0)) < 0;
            case NE -> x.compareTo(bounds.get(0)) != 0;
            case PREFIX -> false;
        };
    }

    /** DATE/DATETIME（规则③）：按首值形态定域，实体值/字符串双形态归一后按操作符比较 */
    private boolean testTemporal(Object entityValue) {
        PredicateOperatorEnum op = parseOperator();
        if (op == null) {
            return false;
        }
        if (values.get(0) instanceof LocalDate) {
            return testDate(entityValue, op);
        }
        return testDateTime(entityValue, op);
    }

    private boolean testDate(Object entityValue, PredicateOperatorEnum op) {
        LocalDate x = toLocalDate(entityValue);
        List<LocalDate> bounds = values.stream().map(RowPredicate::toLocalDate).toList();
        if (x == null || bounds.stream().anyMatch(Objects::isNull)) {
            return false;
        }
        return switch (op) {
            case EQ -> x.compareTo(bounds.get(0)) == 0;
            case IN -> bounds.stream().anyMatch(b -> x.compareTo(b) == 0);
            case BETWEEN -> !bounds.get(0).isAfter(x) && !x.isAfter(bounds.get(1));
            case GTE -> !x.isBefore(bounds.get(0));
            case LTE -> !x.isAfter(bounds.get(0));
            case GT -> x.isAfter(bounds.get(0));
            case LT -> x.isBefore(bounds.get(0));
            case NE -> x.compareTo(bounds.get(0)) != 0;
            case PREFIX -> false;
        };
    }

    private boolean testDateTime(Object entityValue, PredicateOperatorEnum op) {
        LocalDateTime x = toLocalDateTime(entityValue);
        List<LocalDateTime> bounds = values.stream().map(RowPredicate::toLocalDateTime).toList();
        if (x == null || bounds.stream().anyMatch(Objects::isNull)) {
            return false;
        }
        return switch (op) {
            case EQ -> x.compareTo(bounds.get(0)) == 0;
            case IN -> bounds.stream().anyMatch(b -> x.compareTo(b) == 0);
            case BETWEEN -> !bounds.get(0).isAfter(x) && !x.isAfter(bounds.get(1));
            case GTE -> !x.isBefore(bounds.get(0));
            case LTE -> !x.isAfter(bounds.get(0));
            case GT -> x.isAfter(bounds.get(0));
            case LT -> x.isBefore(bounds.get(0));
            case NE -> x.compareTo(bounds.get(0)) != 0;
            case PREFIX -> false;
        };
    }

    /**
     * STRING v2（规则④ + 扩展轮 D43/D44）：EQ/IN/NE/PREFIX 四 case + default false
     * （BETWEEN/GTE/LTE/GT/LT 矩阵外组合防御恒 false）。
     * 规则④：ci + stripTrailing 双侧（EQ/IN 原样、NE 为其求反，探针 P8）；
     * 规则⑥ PREFIX：startsWithIgnoreCase <b>双侧不 stripTrailing</b>——LIKE 非 PAD SPACE
     * （探针 P2-P5：配置前缀尾随空格是字面窄化条件），与规则④刻意不对称记档（设计 D43）。
     */
    private boolean testString(Object entityValue) {
        PredicateOperatorEnum op = parseOperator();
        if (!(entityValue instanceof String text) || op == null) {
            return false;
        }
        String raw = String.valueOf(values.get(0));
        return switch (op) {
            case EQ -> text.stripTrailing().equalsIgnoreCase(raw.stripTrailing());
            case IN -> values.stream().anyMatch(v -> text.stripTrailing()
                    .equalsIgnoreCase(String.valueOf(v).stripTrailing()));
            case NE -> !text.stripTrailing().equalsIgnoreCase(raw.stripTrailing());
            case PREFIX -> text.regionMatches(true, 0, raw, 0, raw.length());
            default -> false;
        };
    }

    /** 操作符防御解析：未知 name → null（谓词恒不满足，fail-closed） */
    private PredicateOperatorEnum parseOperator() {
        try {
            return PredicateOperatorEnum.valueOf(operator);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** NUMBER 归一：Long/Integer/BigDecimal 直转，字符串经 NUMBER 归一器（"1" 双形态）；不可归一 null */
    private static BigDecimal toDecimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Long || value instanceof Integer) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String text) {
            try {
                return (BigDecimal) FilterColumnTypeEnum.NUMBER.normalize(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** DATE 归一：LocalDate 直用、LocalDateTime 取日期部、字符串经 DATE 归一器；不可归一 null */
    private static LocalDate toLocalDate(Object value) {
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toLocalDate();
        }
        if (value instanceof String text) {
            try {
                return (LocalDate) FilterColumnTypeEnum.DATE.normalize(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }

    /** DATETIME 归一：LocalDateTime 直用、LocalDate 取日起点、字符串经 DATETIME 归一器；不可归一 null */
    private static LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (value instanceof String text) {
            try {
                return (LocalDateTime) FilterColumnTypeEnum.DATETIME.normalize(text);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return null;
    }
}
