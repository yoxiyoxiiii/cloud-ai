-- 用户自定义表格配置轮（契约 2026-10-23-table-config-api §4 / 设计 §3.1）：新表 sys_table_config。
-- 每用户每表格一行，config VARCHAR(4000) 存 JSON 串整存整取；零种子（配置 lazy：GET 无行=默认列序，
-- 首次保存才落行）。索引唯一：uk_user_table 命中三端点全部查询路径（user_id+table_key 等值定位，
-- 兼 upsert 判存查重）；零二级索引——无跨用户/按时间/管理面查询路径（设计 §8 记档取舍）。
CREATE TABLE sys_table_config (
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    user_id     BIGINT        NOT NULL COMMENT '用户ID（sys_user.id；服务端从登录态取，不收前端传参——端点只能读写本人配置）',
    table_key   VARCHAR(64)   NOT NULL COMMENT '表格标识（页面级唯一，命名 <模块>:<页面> 如 system:user / system:dict；字符集限字母/数字/冒号/连字符）',
    config      VARCHAR(4000) NOT NULL COMMENT '列配置 JSON 串（{"columns":[{"key":"account","visible":true,"width":160}]}；数组顺序即列序，width 可选；服务端盲存——只校验格式与上限，不校验列含义）',
    create_time DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_time DATETIME      DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_table (user_id, table_key)
) ENGINE = InnoDB COMMENT = '用户表格列配置（个人视图偏好：列顺序/显隐/列宽；权限是硬边界，隐藏列由列级数据权限在前端合并时另行过滤，本表只存偏好；GET 无行=页面默认列序；PUT 整体覆盖 upsert；DELETE 物理删行=恢复默认——沿 sys_data_perm_rule 配置态物理删先例无 deleted 列；无 create_by/update_by 冗余——操作人恒为 user_id 本人）';
