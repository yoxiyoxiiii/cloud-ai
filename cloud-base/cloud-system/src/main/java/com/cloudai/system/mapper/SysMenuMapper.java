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
    List<SysMenu> listAll();

    SysMenu findById(@Param("id") Long id);

    /** 子菜单计数（删除前校验） */
    Long countByParentId(@Param("parentId") Long parentId);

    /** 按账号聚合可见导航菜单（user→启用角色→菜单 JOIN，DISTINCT 多角色去重；F 型与空 path 的 C 在 SQL 层排除） */
    List<SysMenu> listNavByAccount(@Param("account") String account);

    int save(SysMenu menu);

    /** 动态更新（仅非空列，等价原 updateById NOT_NULL 策略） */
    int update(SysMenu menu);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计 */
    int deleteById(@Param("id") Long id,
                   @Param("updateBy") String updateBy,
                   @Param("updateTime") LocalDateTime updateTime);
}
