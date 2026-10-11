package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 可过滤列声明（契约 §5.2，谓词轮 2026-10-11 + 操作符扩展轮 D45）：行级条件可引用列的描述——
 * field（entity 字段名，配置入参）+ column（裸 DB 列名）+ type（NUMBER/STRING/DATE/DATETIME，
 * 驱动前端值控件与操作符矩阵）+ label（中文摘要叙述）+ dictKey（值域字典键，操作符扩展轮 additive：
 * 非空=前端值输入器字典下拉 + 摘要/narratives 值译标签；null=原值显示）。资源注册表端点出参。
 */
@Data
public class FilterColumnVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** entity 字段名（camelCase，条件配置入参形态） */
    private String field;

    /** 裸 DB 列名（snake_case） */
    private String column;

    /** 列类型：NUMBER / STRING / DATE / DATETIME */
    private String type;

    /** 中文名（中文摘要与 narratives 叙述） */
    private String label;

    /** 值域字典键（D45，additive）：非空=字典下拉/值译标签；null=无字典原值显示 */
    private String dictKey;
}
