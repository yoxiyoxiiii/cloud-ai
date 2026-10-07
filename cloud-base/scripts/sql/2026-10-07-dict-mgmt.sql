-- 2026-10-07 数据字典管理：sys_dict_type / sys_dict_data 两表 + 菜单种子 4 行
-- （设计 docs/superpowers/specs/2026-10-07-dict-management-design.md §3 / 契约 2026-10-07-dict-api.md）
-- 红线：不动任何既有表结构；sys_menu 仅 INSERT 4 行新种子（14/141/142/143）；
--       admin 账号与角色零触碰，仅补 sys_role_menu 4 行菜单绑定（role_id=1）；
--       字典业务数据零种子（管理页首开空态即验收态，设计 D7）。
-- 幂等提示：本脚本不幂等——建表 IF NOT EXISTS 可重复，INSERT 显式 id 不幂等；
-- 重复执行前先核对种子未落：SELECT id FROM sys_menu WHERE id IN (14,141,142,143) 应 0 行，
-- 且 SHOW TABLES LIKE 'sys_dict%' 应为空；不满足时停下排查，不盲跑。
-- 基线同步：cloud_system.sql 已同步两表与 4 行种子（绑定由基线 SELECT 全量式天然覆盖），
-- 新环境直接跑基线即可，无需本增量。
USE cloud_system;

-- 设计 §3.1：dict_key 全库唯一（uk_dict_key）；逻辑删除墓碑仍占键，服务层 DuplicateKey 兜底（设计 D8）
CREATE TABLE IF NOT EXISTS sys_dict_type (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '字典类型ID',
    dict_name   VARCHAR(30) NOT NULL COMMENT '字典名称，如：用户状态',
    dict_key    VARCHAR(50) NOT NULL COMMENT '字典键，全库唯一，如：user_status',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_dict_key (dict_key)
) ENGINE = InnoDB COMMENT = '字典类型表';

-- 设计 §3.1：(dict_type_id, value) 类型内唯一（uk_type_value）；最左前缀兼作项列表索引，不另建 idx。
-- value 为 MySQL 非保留关键字（5.7/8.0 均可裸用作列名），设计已核实、本脚本建表即验证。
CREATE TABLE IF NOT EXISTS sys_dict_data (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '字典项ID',
    dict_type_id BIGINT      NOT NULL COMMENT '所属字典类型ID（sys_dict_type.id，引用完整性由服务层维护，无外键）',
    label        VARCHAR(50) NOT NULL COMMENT '展示标签，如：启用',
    value        VARCHAR(50) NOT NULL COMMENT '存库值，同类型内唯一，如：0',
    sort         INT         NOT NULL DEFAULT 0 COMMENT '排序（同类型内升序）',
    status       TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by    VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by    VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_value (dict_type_id, value)
) ENGINE = InnoDB COMMENT = '字典项表';

-- 设计 §3.2 菜单种子：C 14 字典管理（icon Files 为前端 ICON_MAP 白名单既有枚举）+ F 141-143（统一 perms，设计 D3）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, create_time) VALUES
(14, 10, '字典管理', 'system:dict:list',   'C', '/system/dict', 'Files', 4, NOW()),
(141, 14, '字典新增', 'system:dict:add',    'F', '', '', 1, NOW()),
(142, 14, '字典修改', 'system:dict:edit',   'F', '', '', 2, NOW()),
(143, 14, '字典删除', 'system:dict:remove', 'F', '', '', 3, NOW());

-- admin 角色补绑 4 行（基线 sys_role_menu 的 SELECT 1, id FROM sys_menu 全量式天然覆盖，增量须显式补绑）
INSERT INTO sys_role_menu (role_id, menu_id, create_time) VALUES
(1, 14, NOW()), (1, 141, NOW()), (1, 142, NOW()), (1, 143, NOW());
