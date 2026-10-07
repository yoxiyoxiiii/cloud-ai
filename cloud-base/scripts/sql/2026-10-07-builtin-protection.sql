-- 2026-10-07 内置数据保护：5 表 is_builtin 只读列 + 种子置 1
-- （契约 docs/superpowers/contracts/2026-10-07-builtin-protection-api.md §3 / 计划 B1 / 设计 §4.1-4.2）
-- 幂等提示：ALTER 不幂等——执行前经回查通道核对 5 表均无 is_builtin 列：
--   SELECT table_name FROM information_schema.columns
--    WHERE table_schema = 'cloud_system' AND column_name = 'is_builtin';  应 0 行
--   UPDATE 天然幂等，可重复执行。
-- 基线同步：cloud_system.sql 已同步本列定义与种子 INSERT 显式 is_builtin=1，新环境直接跑基线即可，无需本增量。
-- 红线：不动其他列与数据；索引零增量（is_builtin 不进任何查询条件，基数 0/1 不值得单建）。
USE cloud_system;

-- 5 表同款（列位置 AFTER status，业务属性紧邻状态语义）；sys_user 的 COMMENT 为用户域文案（仅禁删禁停，昵称可改）
ALTER TABLE sys_role ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0
    COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建' AFTER status;
ALTER TABLE sys_menu ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0
    COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建' AFTER status;
ALTER TABLE sys_dict_type ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0
    COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建' AFTER status;
ALTER TABLE sys_dict_data ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0
    COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建' AFTER status;
ALTER TABLE sys_user ADD COLUMN is_builtin TINYINT NOT NULL DEFAULT 0
    COMMENT '内置标记：1=系统内置（禁删禁停用，昵称可改），0=用户创建' AFTER status;

-- 种子置 1（UPDATE 天然幂等；菜单 id 清单与基线 sys_menu INSERT 逐一对齐，共 23 行：
-- 10,11,12,13,14,20,21,211 为目录/菜单级，111-115/121-124/131-133/141-143 为按钮级）
UPDATE sys_role      SET is_builtin = 1 WHERE id = 1;
UPDATE sys_menu      SET is_builtin = 1 WHERE id IN (10,11,12,13,14,20,21,211,111,112,113,114,115,121,122,123,124,131,132,133,141,142,143);
UPDATE sys_dict_type SET is_builtin = 1 WHERE id = 1;
UPDATE sys_dict_data SET is_builtin = 1 WHERE id IN (1,2);
UPDATE sys_user      SET is_builtin = 1 WHERE id = 1;
