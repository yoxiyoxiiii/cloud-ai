package com.cloudai.system.mapper;

import com.cloudai.system.entity.SysMenu;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 菜单表 SQL（XML：mapper/SysMenuMapper.xml）。逻辑删除与审计字段显式维护，见 SysUserMapper 说明。
 */
public interface SysMenuMapper {

    /** 全量菜单，按 sort,id 升序（树构建数据源） */
    List<SysMenu> selectAllMenus();

    SysMenu selectMenuById(@Param("id") Long id);

    /** 子菜单计数（删除前校验） */
    Long countByParentId(@Param("parentId") Long parentId);

    int insertMenu(SysMenu menu);

    /** 动态更新（仅非空列，等价原 updateById NOT_NULL 策略） */
    int updateMenu(SysMenu menu);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计 */
    int deleteMenuById(@Param("id") Long id,
                       @Param("updateBy") String updateBy,
                       @Param("updateTime") LocalDateTime updateTime);
}
