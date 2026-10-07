USE cloud_system;
-- 幂等：INSERT 不幂等——执行前核对种子未落（应 0 行）：
--   SELECT id FROM sys_dict_type WHERE dict_key = 'common_status' AND deleted = 0;
-- 不满足时停下排查，不盲跑。基线已同步本 3 行，新环境直接跑基线无需本增量。
-- 红线：不动 user_status 与其他任何数据；不进 sys_menu（无新权限节点）。

INSERT INTO sys_dict_type (dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time)
VALUES ('通用状态', 'common_status', 0, 1, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '正常', '0', 1, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'common_status' AND t.deleted = 0;

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '停用', '1', 2, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'common_status' AND t.deleted = 0;
