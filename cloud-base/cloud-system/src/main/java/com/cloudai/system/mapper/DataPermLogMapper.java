package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysDataPermLog;
import org.apache.ibatis.annotations.Param;

/**
 * 数据权限决策留痕表 SQL（XML：mapper/DataPermLogMapper.xml）。
 * 只插不删的流水表（设计 D7/D9）：无 update/delete 路径；查询命中 idx_account_time / idx_resource_time。
 */
public interface DataPermLogMapper {

    /** 留痕落库（insert 失败由求值器 catch log.error 不抛——可观察组件不得成为读路径故障源，D7） */
    int save(SysDataPermLog log);

    /** 留痕分页（按账号/资源检索，动态条件）；无 LIMIT 由分页插件追加 */
    IPage<SysDataPermLog> pageList(Page<SysDataPermLog> page,
                                   @Param("account") String account,
                                   @Param("resource") String resource);
}
