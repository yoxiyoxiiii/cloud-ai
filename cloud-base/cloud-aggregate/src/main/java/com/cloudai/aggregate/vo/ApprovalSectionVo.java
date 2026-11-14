package com.cloudai.aggregate.vo;

import com.cloudai.bpmn.api.domain.ApprovalVo;
import com.cloudai.common.core.domain.PageResult;
import lombok.Data;

import java.io.Serializable;

/**
 * 我的审批段（cloud-bpmn）：段对象统一结构 status/msg/data——status ≠ 200 时 data 恒 null。
 */
@Data
public class ApprovalSectionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 段状态码（SectionStatusEnum：200/7001/7002） */
    private Integer status;

    /** 段状态说明文案（判断只认 status，msg 仅展示） */
    private String msg;

    /** 段数据（审批 top5 分页；失败段 null） */
    private PageResult<ApprovalVo> data;
}
