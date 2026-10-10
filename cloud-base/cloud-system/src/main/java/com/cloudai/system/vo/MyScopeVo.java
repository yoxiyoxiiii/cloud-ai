package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 我的数据范围摘要（契约 §6.8）：leave 页提示条专用，当前登录人自查（不留痕）。
 */
@Data
public class MyScopeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 全部 / 仅自己 / 自定义范围（含自己，共 N 人）/ 指定范围（N 人）/ 无（空范围） */
    private String scopeLabel;

    /** 列结论（如 "reason:脱敏"；null=无列动作） */
    private String columnSummary;
}
