package com.cloudai.bpmn.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDate;

/**
 * 请假单实体（表 bpmn_leave，设计 D6 状态机：0审批中 →1已通过/2已拒绝/3已撤销，终态不可再变更）。
 * businessKey 关联：id 即流程实例 businessKey（String 化），process_instance_id 发起后同事务回填。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bpmn_leave")
public class BpmnLeave extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 请假标题（e2e 前缀锚点） */
    private String title;

    /** 请假类型：1事假 2病假 3年假（字典 bpmn_leave_type） */
    private Integer leaveType;

    /** 开始日期 */
    private LocalDate startDate;

    /** 结束日期 */
    private LocalDate endDate;

    /** 事由说明 */
    private String reason;

    /** 状态：0审批中 1已通过 2已拒绝 3已撤销（字典 bpmn_leave_status） */
    private Integer status;

    /** 申请人账号（sys_user.account） */
    private String applyUser;

    /** 审批人账号（发起时指定，引擎 assignee） */
    private String approver;

    /** 流程实例ID（发起后回填，关联 ACT） */
    private String processInstanceId;

    /** 状态枚举（内嵌实体，Enum 后缀规范；字段保持 Integer 映射，Java 侧禁魔法数） */
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

    /** 请假类型枚举（字典 bpmn_leave_type 的 Java 侧镜像） */
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
            throw new IllegalArgumentException("未知请假类型: " + code);
        }
    }
}
