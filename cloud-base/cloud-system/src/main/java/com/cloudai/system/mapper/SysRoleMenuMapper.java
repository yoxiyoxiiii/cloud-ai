package com.cloudai.system.mapper;

import com.cloudai.system.entity.SysRoleMenu;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色-菜单关系表 SQL（XML：mapper/SysRoleMenuMapper.xml）。
 * 纯关系表：无逻辑删除列，物理 DELETE；create_time 由 Service 显式传值。
 */
public interface SysRoleMenuMapper {

    List<Long> listMenuIdsByRoleId(@Param("roleId") Long roleId);

    /** 批量插入；空列表由 Service 跳过调用 */
    int saveBatch(@Param("list") List<SysRoleMenu> list);

    int deleteByRoleId(@Param("roleId") Long roleId);

    int deleteByMenuId(@Param("menuId") Long menuId);
}
