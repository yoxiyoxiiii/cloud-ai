-- 2026-10-07 字段统一翻译：user_status 内置字典种子（sys_dict_type 1 行 + sys_dict_data 2 行）
-- （设计 docs/superpowers/specs/2026-10-07-field-translation-design.md D7 / 契约 2026-10-07-translation-api.md §0.3）
-- 红线：不动任何既有表结构与数据；不进 sys_menu（无新权限节点，契约 §0.3）；
--       admin 账号与角色零触碰。
-- 幂等提示：INSERT 显式 id 不幂等——执行前核对种子未落：
--   SELECT id FROM sys_dict_type WHERE id = 1;        应 0 行
--   SELECT id FROM sys_dict_data WHERE id IN (1, 2);  应 0 行
-- 不满足时停下排查（历史 e2e 测试墓碑占键需先物理清理），不盲跑。
-- 基线同步：cloud_system.sql 已同步本 3 行种子，新环境直接跑基线即可，无需本增量。
USE cloud_system;

-- label 文案锁定契约 §0.3（与前端 STATUS_MAP 硬编码文案逐字一致：正常/停用——e2e 状态列断言零改动的前提）；
-- create_by='system' 内置标记（区别人工 admin 操作，设计 D7）
INSERT INTO sys_dict_type (id, dict_name, dict_key, status, create_by, create_time, update_by, update_time) VALUES
(1, '用户状态', 'user_status', 0, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (id, dict_type_id, label, value, sort, status, create_by, create_time, update_by, update_time) VALUES
(1, 1, '正常', '0', 1, 0, 'system', NOW(), 'system', NOW()),
(2, 1, '停用', '1', 2, 0, 'system', NOW(), 'system', NOW());
