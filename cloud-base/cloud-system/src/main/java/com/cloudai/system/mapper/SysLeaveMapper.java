package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.service.dataperm.DataScope;
import com.cloudai.system.vo.SysLeaveVo;
import org.apache.ibatis.annotations.Param;

/**
 * 请假单表 SQL（XML：mapper/SysLeaveMapper.xml；投影轮 2026-10-09 设计 D6 改版）。
 * 读路径 JOIN approval_projection 派生 status/approvalId（写权归框架组件，业务侧只读）；
 * 返回 VO——派生列无实体承载，SQL/映射层即投影（先例 selectPermsByAccount），
 * 「Service 统一转换」本链路无实体可转。逻辑删除与审计字段由手写 SQL 显式维护。
 */
public interface SysLeaveMapper {

    /** 详情读：JOIN 投影派生 status（§1.5 CASE）+ approval_id 直出 */
    SysLeaveVo findById(@Param("businessType") String businessType, @Param("id") Long id);

    /**
     * 请假分页（数据权限轮契约 §4.1 改型）：行集=数据权限求值范围——scope.all=true 过滤豁免全量；
     * 否则按账号白名单 IN 过滤 apply_user。空集范围由 Service 短路不进本方法，XML 永不收到空集合。
     */
    IPage<SysLeaveVo> pageList(Page<SysLeaveVo> page,
                               @Param("businessType") String businessType,
                               @Param("scope") DataScope scope);

    /** 发起落库（snowflake 预生成 id 显式插入，半消息体发送前需知 businessKey，设计 R2） */
    int save(SysLeave leave);
}
