package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 我的数据范围摘要（契约 §6.8 + 列级数据权限轮增量）：当前登录人自查（不留痕）。
 * columns 是机器可读列动作清单（前端动态列渲染驱动源）；columnSummary 人读摘要保留不动
 * （leave 页提示条零影响，两口径同源于同一 ColumnScope，无漂移面）。
 */
@Data
public class MyScopeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 全部 / 仅自己 / 自定义范围（含自己，共 N 人）/ 指定范围（N 人）/ 无（空范围）/ 不适用（该资源仅列级权限） */
    private String scopeLabel;

    /** 列结论（如 "reason:脱敏"；null=无列动作） */
    private String columnSummary;

    /** 列动作终态清单（hidden 在前 masked 在后、各自字典序稳定；无列动作必返 [] 而非 null） */
    private List<DataPermExplainVo.ColumnDecisionVo> columns = new ArrayList<>();
}
