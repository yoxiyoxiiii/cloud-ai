package com.cloudai.bpmn.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.bpmn.entity.BpmnApproval;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 通用审批单表 SQL（XML：mapper/BpmnApprovalMapper.xml）。
 * 逻辑删除与审计字段由手写 SQL 显式维护：查询带 deleted=0（本表无删除端点，删除语句不存在——审批留档语义，
 * 行永不删故 uk_business 无墓碑占键问题）；INSERT/UPDATE 的审计列由 Service 显式传参。
 */
public interface BpmnApprovalMapper {

    BpmnApproval findById(@Param("id") Long id);

    /** 按业务键反查审批单（uk_business 唯一路径；发起查重 4015 与 /inner cancel 定位共用） */
    BpmnApproval findByBusiness(@Param("businessType") String businessType,
                                @Param("businessKey") String businessKey);

    /** 我的审批分页：无 LIMIT，由 PaginationInnerInterceptor 追加；恒按申请人过滤（契约 §3.1） */
    IPage<BpmnApproval> pageList(Page<BpmnApproval> page, @Param("applyUser") String applyUser);

    /** 批量按业务键查（/inner status-list 纠偏回源；单批 ≤100 由调用方分批保证） */
    List<BpmnApproval> listByBusinessKeys(@Param("businessType") String businessType,
                                          @Param("businessKeys") List<String> businessKeys);

    int save(BpmnApproval approval);

    /** 状态回写：status + process_instance_id + 更新审计两值（发起回填/办理回写/撤销共用） */
    int updateStatusById(@Param("id") Long id,
                         @Param("status") Integer status,
                         @Param("processInstanceId") String processInstanceId,
                         @Param("updateBy") String updateBy,
                         @Param("updateTime") LocalDateTime updateTime);
}
