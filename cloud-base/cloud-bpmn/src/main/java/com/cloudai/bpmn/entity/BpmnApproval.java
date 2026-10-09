package com.cloudai.bpmn.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 通用审批单实体（表 bpmn_approval，设计 D1：平台权威状态；businessKey=id）。
 * 状态机：0审批中 →1已通过/2已拒绝/3已撤销，终态不可再变更；同一业务单据唯一审批
 * （uk_business(business_type,business_key) 任意状态生效——驳回后重新发起=业务方生成新单据，契约 §1）。
 * deleted 列仅为 BaseEntity 规范一致性（审批留档语义，无 API 删除出口，行永不删）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bpmn_approval")
public class BpmnApproval extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型编码（bpmn_business_type.type_code，如 leave） */
    private String businessType;

    /** 业务单据标识（业务方主键字符串化，如请假单id） */
    private String businessKey;

    /** 单据标题快照（待办/列表渲染，发起时定格） */
    private String title;

    /** 流程定义key（发起时从配置快照，防配置后改漂移） */
    private String processKey;

    /** 状态：0审批中 1已通过 2已拒绝 3已撤销（字典 bpmn_approval_status） */
    private Integer status;

    /** 申请人账号（sys_user.account） */
    private String applyUser;

    /** 审批人账号（发起时指定，引擎 assignee） */
    private String approver;

    /** 流程实例ID（发起后回填，关联 ACT；撤销后置 null） */
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
}
