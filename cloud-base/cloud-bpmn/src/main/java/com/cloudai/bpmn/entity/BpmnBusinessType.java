package com.cloudai.bpmn.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 业务类型接入配置实体（表 bpmn_business_type，设计 D2：DB 配置先行，管理界面后置移交）。
 * 发起时按 type_code 查配置，process_key 快照进审批单防配置后改漂移；
 * detail_route 为前端详情路由模板（{businessKey} 占位符，待办跳转协议）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("bpmn_business_type")
public class BpmnBusinessType extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务类型编码（如 leave；/inner 发起时传入） */
    private String typeCode;

    /** 业务类型名称（如 请假申请；待办/审批单列表展示） */
    private String typeName;

    /** 默认流程定义key（如 leave_approval） */
    private String processKey;

    /** 前端详情路由模板（{businessKey} 占位符，如 /system/leave?approval={businessKey}） */
    private String detailRoute;
}
