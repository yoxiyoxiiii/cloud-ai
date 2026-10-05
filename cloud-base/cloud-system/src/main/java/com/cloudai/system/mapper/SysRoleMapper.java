package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysRole;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 角色表 SQL（XML：mapper/SysRoleMapper.xml）。逻辑删除与审计字段显式维护，见 SysUserMapper 说明。
 */
public interface SysRoleMapper {

    /** 分页查询：无 LIMIT，由 PaginationInnerInterceptor 追加 */
    IPage<SysRole> pageList(Page<SysRole> page);

    /** 启用状态角色（status=0），按 id 升序 */
    List<SysRole> listEnabled();

    SysRole findById(@Param("id") Long id);

    /** role_key 查重；excludeId 非空时排除自身（编辑场景） */
    Long countByRoleKey(@Param("roleKey") String roleKey, @Param("excludeId") Long excludeId);

    int save(SysRole role);

    /** 动态更新（仅非空列，等价原 updateById NOT_NULL 策略） */
    int update(SysRole role);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计 */
    int deleteById(@Param("id") Long id,
                   @Param("updateBy") String updateBy,
                   @Param("updateTime") LocalDateTime updateTime);
}
