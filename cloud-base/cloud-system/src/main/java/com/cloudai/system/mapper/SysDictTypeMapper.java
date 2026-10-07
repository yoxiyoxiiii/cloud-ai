package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysDictType;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 字典类型表 SQL（XML：mapper/SysDictTypeMapper.xml）。逻辑删除与审计字段显式维护，见 SysUserMapper 说明。
 */
public interface SysDictTypeMapper {

    /** 分页查询（全量未删除，含停用；新类型置首）：无 LIMIT，由 PaginationInnerInterceptor 追加 */
    IPage<SysDictType> pageList(Page<SysDictType> page);

    SysDictType findById(@Param("id") Long id);

    /** dict_key 查重；excludeId 非空时排除自身（编辑场景） */
    Long countByDictKey(@Param("dictKey") String dictKey, @Param("excludeId") Long excludeId);

    int save(SysDictType dictType);

    /** 动态更新（仅非空列，等价 NOT_NULL 字段策略） */
    int update(SysDictType dictType);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计 */
    int deleteById(@Param("id") Long id,
                   @Param("updateBy") String updateBy,
                   @Param("updateTime") LocalDateTime updateTime);
}
