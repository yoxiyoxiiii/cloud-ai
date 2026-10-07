USE cloud_system;
-- bpmn 请假工作流内置字典种子（契约 2026-10-07-bpmn-leave-api §6，is_builtin=1 自动落入
-- builtin-protection §2 字典域 3015/3016 保护）。
-- 幂等：INSERT 不幂等——执行前核对种子未落（应 0 行）：
--   SELECT id FROM sys_dict_type WHERE dict_key IN ('bpmn_leave_status', 'bpmn_leave_type') AND deleted = 0;
-- 墓碑同样占 uk_dict_key（含已删行返回非 0 也停）：SELECT id FROM sys_dict_type WHERE dict_key IN ('bpmn_leave_status', 'bpmn_leave_type');
-- 不满足时停下排查，不盲跑。基线 cloud_system.sql 已同步本 2 类型 7 项（type id=3/4、data id=5-11，id 非契约），
-- 新环境直接跑基线无需本增量。
-- 红线：不动 user_status/common_status 与其他任何数据；不进 sys_menu（菜单种子独立脚本 2026-10-07-bpmn-menus.sql）。

INSERT INTO sys_dict_type (dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time)
VALUES ('请假状态', 'bpmn_leave_status', 0, 1, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '审批中', '0', 1, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'bpmn_leave_status' AND t.deleted = 0;

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '已通过', '1', 2, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'bpmn_leave_status' AND t.deleted = 0;

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '已拒绝', '2', 3, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'bpmn_leave_status' AND t.deleted = 0;

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '已撤销', '3', 4, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'bpmn_leave_status' AND t.deleted = 0;

INSERT INTO sys_dict_type (dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time)
VALUES ('请假类型', 'bpmn_leave_type', 0, 1, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '事假', '1', 1, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'bpmn_leave_type' AND t.deleted = 0;

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '病假', '2', 2, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'bpmn_leave_type' AND t.deleted = 0;

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '年假', '3', 3, 0, 1, 'system', NOW(), 'system', NOW()
  FROM sys_dict_type t WHERE t.dict_key = 'bpmn_leave_type' AND t.deleted = 0;
