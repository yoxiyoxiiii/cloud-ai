-- 2026-10-07 业务动态路由：sys_menu 扩路由字段（设计 D1 / 契约 2026-10-07-menu-nav-api.md §5）
-- 红线：只补新列值（path/icon），不动 name/perms/type/sort/status/deleted/审计任何既有列；
--       admin 账号与角色绑定零触碰（sys_user/sys_user_role/sys_role_menu 不涉）。
-- 幂等提示：本脚本不幂等——重复执行 ALTER 会报 Duplicate column，重复执行前先核对列已存在
-- （SHOW COLUMNS FROM sys_menu LIKE 'path'）；基线脚本 cloud_system.sql 已同步两列与种子值，
-- 新环境直接跑基线即可，无需本增量。
USE cloud_system;

ALTER TABLE sys_menu
    ADD COLUMN path VARCHAR(100) NOT NULL DEFAULT '' COMMENT '前端路由路径，C型菜单有效（以/开头），空串=不进导航' AFTER type,
    ADD COLUMN icon VARCHAR(50)  NOT NULL DEFAULT '' COMMENT '菜单图标名（@element-plus/icons-vue 组件名），空串=默认图标' AFTER path;

UPDATE sys_menu SET icon = 'Setting'    WHERE id = 10;  -- 系统管理 M
UPDATE sys_menu SET path = '/system/user', icon = 'User'       WHERE id = 11;
UPDATE sys_menu SET path = '/system/role', icon = 'UserFilled' WHERE id = 12;
UPDATE sys_menu SET path = '/system/menu', icon = 'Menu'       WHERE id = 13;
UPDATE sys_menu SET icon = 'Lock'       WHERE id = 20;  -- 认证管理 M（path 留空）
-- id 21 在线用户：path/icon 均不补（无前端页面，保持不进导航，契约 §2）
