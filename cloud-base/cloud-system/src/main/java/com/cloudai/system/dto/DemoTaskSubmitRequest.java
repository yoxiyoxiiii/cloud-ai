package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 演示任务提交入参（契约 2026-11-10-task-center-api §7）。
 * 两字段均可缺省（默认 100 / 0）；范围校验在 Service 显式做（越界 6002，与任务中心参数错误同码）。
 */
@Data
public class DemoTaskSubmitRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模拟处理条数（1-10000，默认 100） */
    private Integer total;

    /** 注入失败百分比 0-100（默认 0；>0 时任务在约该进度处失败，用于验证重试/告警链路） */
    private Integer failPercent;
}
