-- cloud-system 库初始化（最小闭环：RBAC 5 表 + 初始数据）
-- ⚠️ 开发环境初始化脚本：DROP 并重建 cloud_system 全部表，生产环境禁止执行
CREATE DATABASE IF NOT EXISTS cloud_system DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
-- 引用完整性由服务层维护（微服务惯例，不建外键）；实测 MySQL 5.7.24+
USE cloud_system;

DROP TABLE IF EXISTS sys_user_role;
DROP TABLE IF EXISTS sys_role_menu;
DROP TABLE IF EXISTS sys_user;
DROP TABLE IF EXISTS sys_role;
DROP TABLE IF EXISTS sys_menu;
DROP TABLE IF EXISTS sys_dict_data;
DROP TABLE IF EXISTS sys_dict_type;

-- 用户
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    account     VARCHAR(30)  NOT NULL COMMENT '登录账号',
    nickname    VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '昵称',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码散列',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account (account)
) ENGINE = InnoDB COMMENT = '用户表';

-- 角色
CREATE TABLE sys_role (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    name        VARCHAR(30) NOT NULL COMMENT '角色名称',
    role_key    VARCHAR(30) NOT NULL COMMENT '角色标识',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_key (role_key)
) ENGINE = InnoDB COMMENT = '角色表';

-- 菜单/权限（M目录 C菜单 F按钮；perms 为权限标识，按钮/菜单有效）
CREATE TABLE sys_menu (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父ID，0为根',
    name        VARCHAR(30) NOT NULL COMMENT '名称',
    perms       VARCHAR(50) NOT NULL DEFAULT '' COMMENT '权限标识，如 system:user:list',
    type        CHAR(1)     NOT NULL DEFAULT 'C' COMMENT 'M目录 C菜单 F按钮',
    path        VARCHAR(100) NOT NULL DEFAULT '' COMMENT '前端路由路径，C型菜单有效（以/开头），空串=不进导航',
    icon        VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '菜单图标名（@element-plus/icons-vue 组件名），空串=默认图标',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE = InnoDB COMMENT = '菜单权限表';

-- 字典类型/字典项（数据字典管理，2026-10-07；与增量脚本 2026-10-07-dict-mgmt.sql 同步落同一份定义）
CREATE TABLE sys_dict_type (
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

-- (dict_type_id, value) 类型内唯一；最左前缀兼作项列表索引，不另建 idx；value 非保留关键字可裸用
CREATE TABLE sys_dict_data (
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

CREATE TABLE sys_user_role (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    user_id     BIGINT   NOT NULL COMMENT '用户ID',
    role_id     BIGINT   NOT NULL COMMENT '角色ID',
    create_time DATETIME DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_role (user_id, role_id),
    KEY idx_role_id (role_id)
) ENGINE = InnoDB COMMENT = '用户角色关联（纯关系表：物理删除，无逻辑删除列）';

CREATE TABLE sys_role_menu (
    id          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    role_id     BIGINT   NOT NULL COMMENT '角色ID',
    menu_id     BIGINT   NOT NULL COMMENT '菜单ID',
    create_time DATETIME DEFAULT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_menu (role_id, menu_id),
    KEY idx_menu_id (menu_id)
) ENGINE = InnoDB COMMENT = '角色菜单关联（纯关系表：物理删除，无逻辑删除列）';

-- ---------- 初始数据 ----------
INSERT INTO sys_role (id, name, role_key, create_time) VALUES (1, '管理员', 'admin', NOW());

-- path/icon 初值与增量脚本 2026-10-07-menu-nav.sql 语义等价（21 在线用户 path/icon 均空串，不进导航）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, create_time) VALUES
(10, 0, '系统管理',   '',                   'M', '',             'Setting',    1, NOW()),
(11, 10, '用户管理',  'system:user:list',   'C', '/system/user', 'User',       1, NOW()),
(12, 10, '角色管理',  'system:role:list',   'C', '/system/role', 'UserFilled', 2, NOW()),
(13, 10, '菜单管理',  'system:menu:list',   'C', '/system/menu', 'Menu',       3, NOW()),
(111, 11, '用户新增', 'system:user:add',    'F', '',             '',           1, NOW()),
(112, 11, '用户修改', 'system:user:edit',   'F', '',             '',           2, NOW()),
(113, 11, '用户删除', 'system:user:remove', 'F', '',             '',           3, NOW()),
(114, 11, '重置密码', 'system:user:resetPwd','F', '',            '',           4, NOW()),
(115, 11, '分配角色', 'system:user:assignRole','F', '',          '',           5, NOW()),
(121, 12, '角色新增', 'system:role:add',    'F', '',             '',           1, NOW()),
(122, 12, '角色修改', 'system:role:edit',   'F', '',             '',           2, NOW()),
(123, 12, '角色删除', 'system:role:remove', 'F', '',             '',           3, NOW()),
(124, 12, '分配权限', 'system:role:assignMenu','F', '',          '',           4, NOW()),
(131, 13, '菜单新增', 'system:menu:add',    'F', '',             '',           1, NOW()),
(132, 13, '菜单修改', 'system:menu:edit',   'F', '',             '',           2, NOW()),
(133, 13, '菜单删除', 'system:menu:remove', 'F', '',             '',           3, NOW()),
(20, 0, '认证管理',   '',                   'M', '',             'Lock',       2, NOW()),
(21, 20, '在线用户',  'sso:online:list',    'C', '',             '',           1, NOW()),
(211, 21, '强制下线', 'sso:online:kick',    'F', '',             '',           1, NOW()),
-- 字典管理菜单（种子分段 14x；与增量脚本 2026-10-07-dict-mgmt.sql 语义等价，
-- admin 绑定由下方 sys_role_menu 的 SELECT 全量式天然覆盖，不重复加显式绑定）
(14, 10, '字典管理',  'system:dict:list',   'C', '/system/dict', 'Files',      4, NOW()),
(141, 14, '字典新增', 'system:dict:add',    'F', '',             '',           1, NOW()),
(142, 14, '字典修改', 'system:dict:edit',   'F', '',             '',           2, NOW()),
(143, 14, '字典删除', 'system:dict:remove', 'F', '',             '',           3, NOW());

-- admin 账号（密码 admin123）
INSERT INTO sys_user (id, account, nickname, password, create_time) VALUES
(1, 'admin', '管理员', '$2a$10$.8cM9ZR8tr7HxklhRPzmwORoIi.z8urt0wZ4a3QaGwgFyyGMle4sG', NOW());

INSERT INTO sys_user_role (user_id, role_id, create_time) VALUES (1, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu;
