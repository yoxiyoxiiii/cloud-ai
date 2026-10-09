package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysLeave;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 请假单表 SQL（XML：mapper/SysLeaveMapper.xml）。
 * 逻辑删除与审计字段由手写 SQL 显式维护：查询带 deleted=0（本表无删除端点——业务台账留档语义）；
 * INSERT/UPDATE 的审计列由 Service 显式传参。
 */
public interface SysLeaveMapper {

    SysLeave findById(@Param("id") Long id);

    /** 我的请假分页：无 LIMIT，由 PaginationInnerInterceptor 追加；恒按申请人过滤（契约 §5.2） */
    IPage<SysLeave> pageList(Page<SysLeave> page, @Param("applyUser") String applyUser);

    int save(SysLeave leave);

    /** 状态快照回写：纠偏/撤销共用（更新审计两值随参） */
    int updateStatusById(@Param("id") Long id,
                         @Param("status") Integer status,
                         @Param("updateBy") String updateBy,
                         @Param("updateTime") LocalDateTime updateTime);

    /** 发起审批后回填审批单关联（同事务内） */
    int updateApprovalId(@Param("id") Long id,
                         @Param("approvalId") Long approvalId,
                         @Param("updateBy") String updateBy,
                         @Param("updateTime") LocalDateTime updateTime);
}
