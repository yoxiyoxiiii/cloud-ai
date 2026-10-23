package com.cloudai.system.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户表格列配置（每用户每表格一行，个人视图偏好：列顺序/显隐/列宽）：不继承 BaseEntity——
 * 配置态数据物理删除无 deleted 列（沿 sys_data_perm_rule 先例，墓碑占 uk 会废掉
 * 「删了再配」的 upsert 语义）；且无 create_by/update_by 列——本人自治数据，操作人恒为
 * user_id 本人，冗余无审计价值（列取舍记档见 docs/superpowers/specs/2026-10-23-table-config-design.md
 * §3.1）。审计时间两列保留，写入一律 SQL NOW()
 * （插入 create=update 同值口径）。config 为 JSON 串盲存（服务端只校验格式与上限，
 * 不理解列含义），序列化/反序列化在 convert 层做。
 */
@Data
public class SysTableConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 用户ID（sys_user.id；服务端从登录态取，端点只能读写本人配置） */
    private Long userId;

    /** 表格标识（页面级唯一，命名 <模块>:<页面> 如 system:user；字符集 [A-Za-z0-9:-]） */
    private String tableKey;

    /** 列配置 JSON 串（{"columns":[{"key":"account","visible":true,"width":160}]}；数组顺序即列序，width 可选） */
    private String config;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
