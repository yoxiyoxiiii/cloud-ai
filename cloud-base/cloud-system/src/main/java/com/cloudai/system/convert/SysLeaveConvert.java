package com.cloudai.system.convert;

import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.vo.SysLeaveVo;

import java.time.format.DateTimeFormatter;

/** 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）；leaveType/status String 化对齐契约 §5.2；
 *  日期 LocalDate → yyyy-MM-dd 字符串（契约出参形态） */
public final class SysLeaveConvert {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private SysLeaveConvert() {
    }

    public static SysLeaveVo toVo(SysLeave leave) {
        SysLeaveVo vo = new SysLeaveVo();
        vo.setId(leave.getId());
        vo.setApprovalId(leave.getApprovalId());
        vo.setTitle(leave.getTitle());
        vo.setLeaveType(leave.getLeaveType() == null ? null : String.valueOf(leave.getLeaveType()));
        vo.setStartDate(leave.getStartDate() == null ? null : leave.getStartDate().format(DATE));
        vo.setEndDate(leave.getEndDate() == null ? null : leave.getEndDate().format(DATE));
        vo.setReason(leave.getReason());
        vo.setStatus(leave.getStatus() == null ? null : String.valueOf(leave.getStatus()));
        vo.setApplyUser(leave.getApplyUser());
        vo.setApprover(leave.getApprover());
        vo.setCreateTime(leave.getCreateTime());
        return vo;
    }
}
