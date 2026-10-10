package com.cloudai.system.mapper;

import com.cloudai.system.entity.SysDataPermColumn;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据权限列级规则表 SQL（XML：mapper/DataPermColumnMapper.xml）。
 * 物理删除（设计 D9）：按 save 全删全插维护，无独立 update 路径。
 */
public interface DataPermColumnMapper {

    /** 求值查询：角色列规则；roleIds 空集由调用方（求值器）跳过，防 IN () */
    List<SysDataPermColumn> listByRoleIds(@Param("resource") String resource,
                                          @Param("roleIds") List<Long> roleIds);

    /** 求值查询：用户直绑列规则 */
    List<SysDataPermColumn> listByUserId(@Param("resource") String resource,
                                         @Param("userId") Long userId);

    /** 规则分页行列规则回填：一批用户主体的列规则（userIds 空集由 Service 跳过，防 IN ()） */
    List<SysDataPermColumn> listByUserIds(@Param("resource") String resource,
                                          @Param("userIds") List<Long> userIds);

    /** 配置回显：该主体该资源全部列规则（uk_subject_column 前缀命中） */
    List<SysDataPermColumn> listBySubject(@Param("resource") String resource,
                                          @Param("subjectType") Integer subjectType,
                                          @Param("subjectId") Long subjectId);

    /** 批量插入；空列表由 Service 跳过调用 */
    int saveBatch(@Param("list") List<SysDataPermColumn> list);

    /** 按主体物理全删（save 全删全插 / 规则删除连带，无行返回 0 合法） */
    int deleteBySubject(@Param("resource") String resource,
                        @Param("subjectType") Integer subjectType,
                        @Param("subjectId") Long subjectId);
}
