package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 请假单实体（表 sys_leave，审批平台化 2026-10-08 设计 D1：业务台账迁 cloud-system；
 * 投影轮 2026-10-09 设计 D6：status/approvalId 快照列退役物理删列——状态一律 JOIN
 * approval_projection 派生读，写权归框架组件，本表纯业务行）。
 * id 即平台 bpmn_approval.business_key；投影行 uk_business(business_type, business_key) 关联。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_leave")
public class SysLeave extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请假标题（e2e 前缀锚点） */
    private String title;

    /** 请假类型：1事假 2病假 3年假（字典 system_leave_type） */
    private Integer leaveType;

    /** 开始日期 */
    private LocalDate startDate;

    /** 结束日期 */
    private LocalDate endDate;

    /** 事由说明 */
    private String reason;

    /** 申请人账号（sys_user.account） */
    private String applyUser;

    /** 审批人账号（发起时指定，引擎 assignee） */
    private String approver;

    /** 类型枚举（内嵌实体，Enum 后缀规范；字段保持 Integer 映射，Java 侧禁魔法数） */
    public enum TypeEnum {
        PERSONAL(1), SICK(2), ANNUAL(3);

        private final int code;

        TypeEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static TypeEnum of(int code) {
            for (TypeEnum t : values()) {
                if (t.code == code) {
                    return t;
                }
            }
            throw new IllegalArgumentException("未知类型: " + code);
        }
    }
}
