package com.cloudai.system.mapper;

import com.cloudai.system.entity.SysTableConfig;
import org.apache.ibatis.annotations.Param;

/**
 * 用户表格列配置表 SQL（XML：mapper/SysTableConfigMapper.xml）。
 * 配置态物理删除表（沿 sys_data_perm_rule 先例）：无 deleted 列，语句无 deleted 条件；
 * 审计时间由 SQL NOW() 取数据库时钟（插入 create=update 同值），无 create_by/update_by 列；
 * 三端点唯一查询路径 = uk_user_table(user_id, table_key) 等值定位（uk 兼 upsert 判存查重）。
 */
public interface SysTableConfigMapper {

    /** uk 等值定位（LIMIT 1 唯一键防御）；无行返回 null=页面默认列序 */
    SysTableConfig findConfig(@Param("userId") Long userId, @Param("tableKey") String tableKey);

    /** 新增配置行（并发撞 uk 由 Service catch DuplicateKeyException 幂等收敛为 update） */
    int saveConfig(SysTableConfig config);

    /** 按 uk 定位整体覆盖 config + update_time（upsert 更新分支） */
    int updateConfig(SysTableConfig config);

    /** 物理删行=恢复默认；行不存在影响 0 行同样成功（幂等） */
    int deleteConfig(@Param("userId") Long userId, @Param("tableKey") String tableKey);
}
