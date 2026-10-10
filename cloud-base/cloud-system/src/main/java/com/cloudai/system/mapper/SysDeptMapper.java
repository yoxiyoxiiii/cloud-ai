package com.cloudai.system.mapper;

import com.cloudai.system.entity.SysDept;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 部门表 SQL（XML：mapper/SysDeptMapper.xml）。
 * 逻辑删除与审计字段由手写 SQL 显式维护：查询带 deleted=0，删除为 UPDATE deleted=1；
 * INSERT/UPDATE 的审计列（create_by/create_time/update_by/update_time）由 Service 显式传参。
 */
public interface SysDeptMapper {

    /** 全量未删除部门（含停用，树数据源——tag 区分沿 dict 口径），sort/id 升序 */
    List<SysDept> listAll();

    SysDept findById(@Param("id") Long id);

    /** 同层级重名查重；excludeId 非空时排除自身（编辑场景） */
    Long countByParentAndName(@Param("parentId") Long parentId,
                              @Param("name") String name,
                              @Param("excludeId") Long excludeId);

    /** 未删子部门计数（删除前置校验） */
    Long countByParentId(@Param("parentId") Long parentId);

    int save(SysDept dept);

    /** 动态更新（仅非空列，等价 NOT_NULL 字段策略；审计两值随实体传入） */
    int update(SysDept dept);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计 */
    int deleteById(@Param("id") Long id,
                   @Param("updateBy") String updateBy,
                   @Param("updateTime") LocalDateTime updateTime);
}
