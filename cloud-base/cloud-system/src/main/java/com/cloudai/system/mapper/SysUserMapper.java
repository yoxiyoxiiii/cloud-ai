package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.vo.SysUserVo;
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

    /** 分页查询：无 LIMIT，由 PaginationInnerInterceptor 追加；数据权限轮 D14 改型 VO 直出（JOIN sys_dept 派生 deptName） */
    IPage<SysUserVo> pageList(Page<SysUserVo> page);

    Long countByAccount(@Param("account") String account);

    /** 部门删除前置校验：挂载在职（未删）用户计数（含停用账号，契约 §2.4） */
    Long countByDeptId(@Param("deptId") Long deptId);

    /** 数据权限求值展开：部门(含子树)→启用账号集合（status=0 未删）；deptIds 空集由 Service 跳过调用防 IN () */
    List<String> listEnabledAccountsByDeptIds(@Param("deptIds") List<Long> deptIds);

    /** 登录权限聚合：账号 → 启用角色 → 启用菜单的权限标识（DISTINCT，非空 perms） */
    List<String> listPermsByAccount(@Param("account") String account);

    /** 翻译回源全量：未删用户 id/account/nickname 投影（小表全扫取舍见翻译设计 §6） */
    List<SysUser> listTransAll();

    /** 审批人选项：仅启用账号 id/account/nickname 投影（契约 2026-10-08-approval-platform-api §5.5） */
    List<SysUser> listEnabledOptions();

    /** 批量名称回填：数据权限规则分页行 subjectName（ids 空集由 Service 跳过，防 IN ()） */
    List<SysUser> listByIds(@Param("ids") List<Long> ids);

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
