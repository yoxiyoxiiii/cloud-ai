package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysDictData;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * 字典项表 SQL（XML：mapper/SysDictDataMapper.xml）。逻辑删除与审计字段显式维护，见 SysUserMapper 说明。
 */
public interface SysDictDataMapper {

    /** 分页查询某类型全部未删项（含停用），按 sort 升序 id 升序：无 LIMIT，由 PaginationInnerInterceptor 追加 */
    IPage<SysDictData> pageListByTypeId(Page<SysDictData> page, @Param("typeId") Long typeId);

    SysDictData findById(@Param("id") Long id);

    /** 某类型未删项计数（删除类型前的"禁删有项"校验） */
    Long countByTypeId(@Param("typeId") Long typeId);

    /** (typeId, value) 查重；excludeId 非空时排除自身（编辑场景） */
    Long countByTypeValue(@Param("typeId") Long typeId,
                          @Param("value") String value,
                          @Param("excludeId") Long excludeId);

    int save(SysDictData dictData);

    /** 动态更新（仅非空列，等价 NOT_NULL 字段策略） */
    int update(SysDictData dictData);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计 */
    int deleteById(@Param("id") Long id,
                   @Param("updateBy") String updateBy,
                   @Param("updateTime") LocalDateTime updateTime);
}
