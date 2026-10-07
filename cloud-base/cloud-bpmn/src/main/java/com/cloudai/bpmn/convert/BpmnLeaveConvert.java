package com.cloudai.bpmn.convert;

import com.cloudai.bpmn.entity.BpmnLeave;
import com.cloudai.bpmn.vo.LeaveVo;

/** 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）；日期/枚举字段 String 化对齐契约 §2.2 */
public final class BpmnLeaveConvert {

    private BpmnLeaveConvert() {
    }

    public static LeaveVo toVo(BpmnLeave leave) {
        LeaveVo vo = new LeaveVo();
        vo.setId(leave.getId());
        vo.setTitle(leave.getTitle());
        vo.setLeaveType(leave.getLeaveType() == null ? null : String.valueOf(leave.getLeaveType()));
        vo.setStartDate(leave.getStartDate() == null ? null : leave.getStartDate().toString());
        vo.setEndDate(leave.getEndDate() == null ? null : leave.getEndDate().toString());
        vo.setReason(leave.getReason());
        vo.setStatus(leave.getStatus() == null ? null : String.valueOf(leave.getStatus()));
        vo.setApplyUser(leave.getApplyUser());
        vo.setApprover(leave.getApprover());
        vo.setProcessInstanceId(leave.getProcessInstanceId());
        vo.setCreateTime(leave.getCreateTime());
        vo.setUpdateTime(leave.getUpdateTime());
        return vo;
    }
}
