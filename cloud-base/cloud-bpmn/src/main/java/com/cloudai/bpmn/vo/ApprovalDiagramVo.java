package com.cloudai.bpmn.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 审批单图数据 VO（契约 2026-10-08-approval-platform-api §3.4 取代 LeaveDiagramVo：
 * businessKey=approvalId 历史锚点取实例，高亮四字段按三态矩阵定型——零变化平移；
 * definitionId 可空——历史实例缺失防御态前端隐藏图区）。
 */
@Data
public class ApprovalDiagramVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 实例所用定义 id（历史实例锚点；历史缺失防御 null） */
    private String definitionId;

    /** 实例 id（历史缺失防御 null） */
    private String processInstanceId;

    /** 当前活动节点（运行中取引擎；终态/撤销恒空数组） */
    private List<String> activeActivityIds;

    /** 已执行活动 id 集合（历史活动时间升序去重，含网关/事件节点） */
    private List<String> completedActivityIds;

    /** 结束节点 id（endApprove/endReject）；审批中/撤销为 null */
    private String endActivityId;
}
