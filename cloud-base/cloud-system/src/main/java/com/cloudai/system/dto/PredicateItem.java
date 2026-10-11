package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 行级条件三元组（契约 §5.1 / §2.3，2026-10-11 谓词轮）：save 入参与 rule/config/explain
 * 出参共用——配置原样承载（值统一字符串，Long→String 既有约定防精度）；组内多条件 AND，
 * 与档位行内 AND。类型化（BigDecimal/LocalDate/…）在求值 enrichment 与消费方 toRowFilter
 * 经 FilterColumnTypeEnum.normalize 完成，本对象恒字符串形态。
 */
@Data
public class PredicateItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 条件列标识（entity 字段名，如 leaveType；不在资源可过滤列清单 → 3035） */
    private String field;

    /** 操作符（EQ/IN/BETWEEN/GTE/LTE；非枚举 name → 1002「操作符无效」） */
    private String operator;

    /** 值数组（统一字符串；EQ/GTE/LTE=1、BETWEEN=2 有序、IN=1~30；格式违例 → 3037） */
    private List<String> values;
}
