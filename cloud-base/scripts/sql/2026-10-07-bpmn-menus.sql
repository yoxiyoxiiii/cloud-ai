USE cloud_system;
-- bpmn 请假工作流菜单种子（契约 2026-10-07-bpmn-leave-api §9，sys_menu 30 段 7 行 is_builtin=1）。
-- 幂等：INSERT 不幂等——执行前核对 7 个 id 全空闲（含墓碑，不带 deleted=0，物理判断 id 占用）：
--   SELECT id FROM sys_menu WHERE id IN (30,31,32,33,311,312,321);
-- 应 0 行；非 0（含墓碑占位）→ 停下排查回报，不盲跑。基线 cloud_system.sql 已同步同款行，
-- 新环境直接跑基线无需本增量。
-- 红线：不动既有 23 行菜单与任何其他数据；admin 绑定用 IN 列表增量（基线的 SELECT 全量式天然覆盖）。
-- 权限快照时效：新 perms 需 admin 重新登录（或 refresh）进 OnlineSession 快照后才生效。

INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(30,  0,  '流程管理', '',                    'M', '',               'Tickets', 3, 1, NOW()),
(31,  30, '我的申请', 'bpmn:leave:list',     'C', '/bpmn/leave',     'Document',1, 1, NOW()),
(32,  30, '待办任务', 'bpmn:task:list',      'C', '/bpmn/task',      'Bell',    2, 1, NOW()),
(33,  30, '流程定义', 'bpmn:definition:list','C', '/bpmn/definition','Files',   3, 1, NOW()),
(311, 31, '发起申请', 'bpmn:leave:add',      'F', '', '', 1, 1, NOW()),
(312, 31, '撤销申请', 'bpmn:leave:cancel',   'F', '', '', 2, 1, NOW()),
(321, 32, '办理任务', 'bpmn:task:complete',  'F', '', '', 1, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (30,31,32,33,311,312,321);
