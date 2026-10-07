package com.cloudai.bpmn.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.bpmn.entity.BpmnLeave;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 请假单表 SQL（XML：mapper/BpmnLeaveMapper.xml）。
 * 逻辑删除与审计字段由手写 SQL 显式维护：查询带 deleted=0（本表无删除端点，删除语句不存在——审批留档语义）；
 * INSERT/UPDATE 的审计列由 Service 显式传参（更新走 updateStatusById 三参形态）。
 */
public interface BpmnLeaveMapper {

    BpmnLeave findById(@Param("id") Long id);

    /** 我的申请分页：无 LIMIT，由 PaginationInnerInterceptor 追加；恒按申请人过滤（契约 §2.2） */
    IPage<BpmnLeave> pageList(Page<BpmnLeave> page, @Param("applyUser") String applyUser);

    int save(BpmnLeave leave);

    /** 状态回写：status + process_instance_id + 更新审计两值（发起回填/办理回写/撤销共用） */
    int updateStatusById(@Param("id") Long id,
                         @Param("status") Integer status,
                         @Param("processInstanceId") String processInstanceId,
                         @Param("updateBy") String updateBy,
                         @Param("updateTime") LocalDateTime updateTime);
}
