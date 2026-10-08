USE cloud_system;
-- bpmn 部署权限菜单种子（契约 2026-10-08-bpmn-diagram-designer-api §5，sys_menu 331 F 节点挂 33 下，
-- perms bpmn:definition:deploy，is_builtin=1——权限全集 7→8）。
-- 幂等：INSERT 不幂等——执行前核对 id 331 空闲（含墓碑，不带 deleted=0，物理判断 id 占用）：
--   SELECT id FROM sys_menu WHERE id = 331;
-- 应 0 行；非 0（含墓碑占位）→ 停下排查回报，不盲跑。基线 cloud_system.sql 已同步同款行，
-- 新环境直接跑基线无需本增量。
-- admin 绑定用 FROM DUAL WHERE NOT EXISTS 增量（MySQL 5.7 裸 SELECT 带 WHERE 会 1064，需 FROM DUAL；
-- 基线的 sys_role_menu SELECT 全量式天然覆盖，不重复加显式绑定）。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）进 OnlineSession 快照后 bpmn:definition:deploy 才生效。

INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(331, 33, '部署流程', 'bpmn:definition:deploy', 'F', '', '', 1, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, 331, NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM sys_role_menu WHERE role_id = 1 AND menu_id = 331);
