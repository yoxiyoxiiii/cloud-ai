package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysUser;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户表 SQL（XML：mapper/SysUserMapper.xml）。
 * 逻辑删除与审计字段由手写 SQL 显式维护：查询带 deleted=0，删除为 UPDATE deleted=1；
 * INSERT/UPDATE 的审计列（create_by/create_time/update_by/update_time）由 Service 显式传参。
 */
public interface SysUserMapper {

    /** 按账号查（账号唯一，LIMIT 1 兜底） */
    SysUser findByAccount(@Param("account") String account);

    SysUser findById(@Param("id") Long id);

    /** 分页查询：无 LIMIT，由 PaginationInnerInterceptor 追加 */
    IPage<SysUser> pageList(Page<SysUser> page);

    Long countByAccount(@Param("account") String account);

    /** 登录权限聚合：账号 → 启用角色 → 启用菜单的权限标识（DISTINCT，非空 perms） */
    List<String> listPermsByAccount(@Param("account") String account);

    int save(SysUser user);

    /** 动态更新（仅非空列，等价原 updateById NOT_NULL 策略） */
    int update(SysUser user);

    int updatePassword(@Param("id") Long id,
                       @Param("password") String password,
                       @Param("updateBy") String updateBy,
                       @Param("updateTime") LocalDateTime updateTime);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计 */
    int deleteById(@Param("id") Long id,
                   @Param("updateBy") String updateBy,
                   @Param("updateTime") LocalDateTime updateTime);
}
