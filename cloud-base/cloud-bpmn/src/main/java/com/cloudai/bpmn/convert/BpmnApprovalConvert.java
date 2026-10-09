package com.cloudai.bpmn.convert;

import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.vo.ApprovalVo;

/** 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）；状态字段 String 化对齐契约 §3.1；
 *  businessTypeName/detailPath 非实体字段（配置表渲染），由 Service 回填 */
public final class BpmnApprovalConvert {

    private BpmnApprovalConvert() {
    }

    public static ApprovalVo toVo(BpmnApproval approval) {
        ApprovalVo vo = new ApprovalVo();
        vo.setId(approval.getId());
        vo.setBusinessType(approval.getBusinessType());
        vo.setBusinessKey(approval.getBusinessKey());
        vo.setTitle(approval.getTitle());
        vo.setStatus(approval.getStatus() == null ? null : String.valueOf(approval.getStatus()));
        vo.setApplyUser(approval.getApplyUser());
        vo.setApprover(approval.getApprover());
        vo.setProcessInstanceId(approval.getProcessInstanceId());
        vo.setCreateTime(approval.getCreateTime());
        vo.setUpdateTime(approval.getUpdateTime());
        return vo;
    }
}
