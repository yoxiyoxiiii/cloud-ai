package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 请假单实体（表 sys_leave，审批平台化 2026-10-08 设计 D1：业务台账迁 cloud-system）。
 * status 为缓存快照——真相源=bpmn_approval.status，读时经 /inner/approval/status-list 纠偏回写；
 * id 即平台 bpmn_approval.business_key；approval_id 撤销后仍保留（契约 §5.2）。
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

    /** 状态快照：0审批中 1已通过 2已拒绝 3已撤销（字典 bpmn_approval_status；真相源在平台侧） */
    private Integer status;

    /** 审批单ID（bpmn_approval.id，发起 Feign 成功后回填；撤销后仍保留） */
    private Long approvalId;

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

    /** 状态快照枚举（与平台 bpmn_approval.status 同值域；值语义以契约 §6/字典 bpmn_approval_status 为准） */
    public enum StatusEnum {
        APPROVING(0), APPROVED(1), REJECTED(2), CANCELLED(3);

        private final int code;

        StatusEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static StatusEnum of(int code) {
            for (StatusEnum s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知状态: " + code);
        }

        /** 终态判定（1已通过/2已拒绝/3已撤销不可再变更） */
        public boolean isTerminal() {
            return this != APPROVING;
        }
    }
}
