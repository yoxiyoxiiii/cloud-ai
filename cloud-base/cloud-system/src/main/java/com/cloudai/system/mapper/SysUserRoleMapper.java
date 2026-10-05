package com.cloudai.system.mapper;

import com.cloudai.system.entity.SysUserRole;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户-角色关系表 SQL（XML：mapper/SysUserRoleMapper.xml）。
 * 纯关系表：无逻辑删除列，物理 DELETE；create_time 由 Service 显式传值。
 */
public interface SysUserRoleMapper {

    List<Long> selectRoleIdsByUserId(@Param("userId") Long userId);

    /** 批量插入；空列表由 Service 跳过调用 */
    int insertBatch(@Param("list") List<SysUserRole> list);

    int deleteByUserId(@Param("userId") Long userId);

    int deleteByRoleId(@Param("roleId") Long roleId);
}
