package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.dto.NotifyTemplatePageQuery;
import com.cloudai.system.entity.SysNotifyTemplate;
import org.apache.ibatis.annotations.Param;

/**
 * 站内信模板表 SQL（XML：mapper/SysNotifyTemplateMapper.xml）。
 * 管理域配置表：查询显式 deleted=0，删除为 UPDATE deleted=1 留更新审计；
 * 审计时间列由 SQL NOW() 取数据库时钟，操作人由 Service 显式传参。
 */
public interface SysNotifyTemplateMapper {

    SysNotifyTemplate findById(@Param("id") Long id);

    /** 分页查询：无 LIMIT，由 PaginationInnerInterceptor 追加；name/code 模糊 + type 精确 */
    IPage<SysNotifyTemplate> pageList(Page<SysNotifyTemplate> page, @Param("query") NotifyTemplatePageQuery query);

    /** 模板编码查重（编辑时传 excludeId 排除自身；墓碑行占键场景由 DuplicateKeyException 兜底） */
    Long countByCode(@Param("code") String code, @Param("excludeId") Long excludeId);

    int save(SysNotifyTemplate template);

    /** 动态更新（仅非空列；操作人随实体 updateBy 传入，update_time 取 NOW()） */
    int update(SysNotifyTemplate template);

    /** 逻辑删除：UPDATE deleted=1 并留更新审计（update_time 取 NOW()） */
    int deleteById(@Param("id") Long id,
                   @Param("updateBy") String updateBy);
}
