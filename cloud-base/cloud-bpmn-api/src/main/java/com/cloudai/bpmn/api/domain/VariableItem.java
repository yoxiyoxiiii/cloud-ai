package com.cloudai.bpmn.api.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 流程变量透传项（契约 2026-10-08-approval-platform-api §4.1：variables 数组元素，
 * List 形态规避 Map 接参禁令——契约定形）。
 * （cloud-bpmn-api 归位 2026-10-09）
 */
@Data
public class VariableItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 变量名（BPMN 表达式引用锚点；与平台四变量同名时以平台注入为准） */
    @NotBlank
    @Size(max = 64)
    private String name;

    /** 变量值（字符串形态；数值/布尔语义由流程模型表达式自行解析） */
    @Size(max = 500)
    private String value;
}
