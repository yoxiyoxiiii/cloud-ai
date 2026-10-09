package com.cloudai.bpmn.mapper;

import com.cloudai.bpmn.entity.BpmnBusinessType;
import org.apache.ibatis.annotations.Param;

/**
 * 业务类型配置表 SQL（XML：mapper/BpmnBusinessTypeMapper.xml）。
 * DB 配置先行（管理界面后置移交），当前仅只读查询；查询带 deleted=0，无写语句。
 */
public interface BpmnBusinessTypeMapper {

    /** 按 type_code 查配置（uk_type_code 唯一路径，BusinessTypeRegistry 回源） */
    BpmnBusinessType findByTypeCode(@Param("typeCode") String typeCode);
}
