package com.cloudai.system.api.domain;

import com.cloudai.system.api.dataperm.FilterColumnTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务间求值窄契约——谓词出参（契约 §5.3 / 设计 D35，2026-10-11 谓词轮）：<b>自含
 * column/type</b>（消费方 XML 分支与取值器零注册表依赖——provider 求值时经注册表声明
 * enrichment 完成契约模型）。值在 wire 上为字符串承载（Long→String 既有约定防精度），
 * 类型化在消费方 toRowFilter 经 FilterColumnTypeEnum.normalize 完成。
 * type 枚举在线格式即 name（"NUMBER"/"STRING"/"DATE"/"DATETIME"，契约 §5.3）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PredicateVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** entity 字段名（detail 取值键） */
    private String field;

    /** 裸 DB 列名（消费方 XML 硬编码分支对照） */
    private String column;

    /** 操作符 name（EQ/IN/BETWEEN/GTE/LTE） */
    private String operator;

    /** 值类型（消费方类型化转换依据） */
    private FilterColumnTypeEnum type;

    /** 值数组（字符串承载；类型化在消费方完成） */
    private List<String> values = new ArrayList<>();
}
