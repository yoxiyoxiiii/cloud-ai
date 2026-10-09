-- 审批平台化存量库增量（契约 2026-10-08-approval-platform-api / 设计 2026-10-08-approval-platform-design）。
-- 三段：cloud_bpmn 域（DROP bpmn_leave + 两新表 + leave 配置种子行）/ cloud_system 域 sys_leave 建表 /
--       菜单 31 段 UPDATE 改造 + 34/341 新增 / 字典删 2 增 2。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT perms FROM sys_menu WHERE id IN (31,311,312); -- 应 bpmn:leave:list / bpmn:leave:add / bpmn:leave:cancel（防二次执行错改）
--   2) SELECT id FROM sys_menu WHERE id IN (34,341);        -- 应 0 行（含墓碑，物理判断 id 占用）
--   3) SELECT 1 FROM information_schema.tables WHERE table_schema='cloud_system' AND table_name='sys_leave';
--      -- 应 0 行（存在即停回报：建表幂等口径=存在即停，不静默跳过）
--   4) SELECT 1 FROM information_schema.tables WHERE table_schema='cloud_bpmn' AND table_name IN ('bpmn_approval','bpmn_business_type');
--      -- 应 0 行（已存在=部分执行过，停下核对表结构再定）
--   5) SELECT id FROM sys_dict_type WHERE dict_key IN ('bpmn_approval_status','system_leave_type'); -- 应 0 行
-- INSERT 类语句均以 FROM DUAL WHERE NOT EXISTS 幂等（MySQL 5.7 裸 SELECT 带 WHERE 需 FROM DUAL）；
-- 基线 cloud_bpmn.sql / cloud_system.sql 已同步同款定义与种子，新环境直接跑基线无需本增量。
-- 字典 DELETE 属内置种子演进 SQL（运行时保护矩阵只约束 API，不约束种子迁移——设计 D10）。
-- 权限快照时效：落库后 admin 须重新登录（或 refresh）进 OnlineSession 快照后新 perms 才生效。

USE cloud_bpmn;

-- bpmn_leave 老表 DROP（拍板③一次性切换：不迁数据，存量均为 e2e 终态/开发数据；ACT_* 引擎表残留允许）
DROP TABLE IF EXISTS bpmn_leave;

-- bpmn_approval：通用审批单（平台权威状态；索引设计见基线 cloud_bpmn.sql 头注/设计 D1）
CREATE TABLE bpmn_approval (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '审批单ID（即流程实例 businessKey）',
    business_type        VARCHAR(50)  NOT NULL COMMENT '业务类型编码（bpmn_business_type.type_code，如 leave）',
    business_key         VARCHAR(64)  NOT NULL COMMENT '业务单据标识（业务方主键字符串化，如请假单id）',
    title                VARCHAR(100) NOT NULL COMMENT '单据标题快照（待办/列表渲染，发起时定格）',
    process_key          VARCHAR(64)  NOT NULL COMMENT '流程定义key（发起时从配置快照，防配置后改漂移）',
    status               TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0审批中 1已通过 2已拒绝 3已撤销（字典 bpmn_approval_status）',
    apply_user           VARCHAR(30)  NOT NULL COMMENT '申请人账号（sys_user.account）',
    approver             VARCHAR(30)  NOT NULL COMMENT '审批人账号（发起时指定，引擎 assignee）',
    process_instance_id  VARCHAR(64)  NULL     COMMENT '流程实例ID（发起后回填，关联 ACT）',
    create_by            VARCHAR(30)  NULL     COMMENT '创建人',
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by            VARCHAR(30)  NULL     COMMENT '更新人',
    update_time          DATETIME     NULL     COMMENT '更新时间',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_business (business_type, business_key),
    KEY idx_apply_user (apply_user, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='通用审批单（平台权威状态；businessKey=id；驳回重报=业务方新单据）';

-- bpmn_business_type：业务类型接入配置（DB 配置先行，管理界面后置移交；索引设计见基线/设计 D2）
CREATE TABLE bpmn_business_type (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '配置ID',
    type_code    VARCHAR(50)  NOT NULL COMMENT '业务类型编码（如 leave；/inner 发起时传入）',
    type_name    VARCHAR(50)  NOT NULL COMMENT '业务类型名称（如 请假申请；待办/审批单列表展示）',
    process_key  VARCHAR(64)  NOT NULL COMMENT '默认流程定义key（如 leave_approval）',
    detail_route VARCHAR(200) NOT NULL COMMENT '前端详情路由模板（{businessKey} 占位符，如 /system/leave?approval={businessKey}）',
    create_by    VARCHAR(30)  NULL     COMMENT '创建人',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    VARCHAR(30)  NULL     COMMENT '更新人',
    update_time  DATETIME     NULL     COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_code (type_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='业务类型接入配置（DB 配置先行，管理界面后置移交）';

-- leave 配置种子行（首个接入方；detail_route 待办跳转协议见契约 §1）
INSERT INTO bpmn_business_type (type_code, type_name, process_key, detail_route, create_by, create_time, update_by, update_time)
SELECT 'leave', '请假申请', 'leave_approval', '/system/leave?approval={businessKey}', 'system', NOW(), 'system', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM bpmn_business_type WHERE type_code = 'leave');

USE cloud_system;

-- sys_leave 建表（结构=bpmn_leave 平移 + approval_id；不迁数据；存在即停见头注断言 3）
CREATE TABLE sys_leave (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '请假单ID（即平台 bpmn_approval.business_key）',
    title        VARCHAR(100) NOT NULL COMMENT '请假标题（e2e 前缀锚点）',
    leave_type   TINYINT      NOT NULL COMMENT '请假类型：1事假 2病假 3年假（字典 system_leave_type）',
    start_date   DATE         NOT NULL COMMENT '开始日期',
    end_date     DATE         NOT NULL COMMENT '结束日期',
    reason       VARCHAR(500) NULL     COMMENT '事由说明',
    status       TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0审批中 1已通过 2已拒绝 3已撤销（缓存快照，真相源=bpmn_approval.status，读时纠偏；字典 bpmn_approval_status）',
    approval_id  BIGINT       NULL     COMMENT '审批单ID（bpmn_approval.id，发起 Feign 成功后回填；撤销后仍保留）',
    apply_user   VARCHAR(30)  NOT NULL COMMENT '申请人账号（sys_user.account）',
    approver     VARCHAR(30)  NOT NULL COMMENT '审批人账号（发起时指定，引擎 assignee）',
    create_by    VARCHAR(30)  NULL     COMMENT '创建人',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    VARCHAR(30)  NULL     COMMENT '更新人',
    update_time  DATETIME     NULL     COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    KEY idx_apply_user (apply_user, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT = '请假单（cloud-system 业务台账；审批编排经 cloud-bpmn /inner/approval）';

-- 菜单 31 段 UPDATE 改造（契约 §8：请假迁 system 首个接入方；31/311/312 id 复用保住 sys_role_menu
-- 既有绑定与 e2e 种子断言的 id 稳定性；32/33 sort 顺调给 34 让位）
UPDATE sys_menu SET name='请假申请', perms='system:leave:list',   path='/system/leave' WHERE id=31;
UPDATE sys_menu SET perms='system:leave:add'    WHERE id=311;
UPDATE sys_menu SET perms='system:leave:cancel' WHERE id=312;
UPDATE sys_menu SET sort=3 WHERE id=32;
UPDATE sys_menu SET sort=4 WHERE id=33;

-- 菜单 34/341 新增（平台我的审批入口 + 撤销按钮；前置 id 占用核对见头注断言 2）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(34,  30, '我的审批', 'bpmn:approval:list',   'C', '/bpmn/approval', 'Document', 2, 1, NOW()),
(341, 34, '撤销审批', 'bpmn:approval:cancel', 'F', '', '', 1, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, m.id, NOW() FROM sys_menu m WHERE m.id IN (34,341)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu rm WHERE rm.role_id=1 AND rm.menu_id=m.id);

-- 字典迁移：删 bpmn_leave_status/bpmn_leave_type 种子行（类型+数据；语义由 bpmn_approval_status
-- （状态平台共用）与 system_leave_type（类型键迁名）承接，契约 §7）
DELETE dd FROM sys_dict_data dd
  JOIN sys_dict_type dt ON dd.dict_type_id = dt.id
 WHERE dt.dict_key IN ('bpmn_leave_status', 'bpmn_leave_type');
DELETE FROM sys_dict_type WHERE dict_key IN ('bpmn_leave_status', 'bpmn_leave_type');

-- 字典新增 bpmn_approval_status（审批状态，平台与业务侧共用；4 项）
INSERT INTO sys_dict_type (dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT '审批状态', 'bpmn_approval_status', 0, 1, 'system', NOW(), 'system', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_key = 'bpmn_approval_status');

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '审批中', '0', 1, 0, 1, 'system', NOW(), 'system', NOW() FROM sys_dict_type t
 WHERE t.dict_key = 'bpmn_approval_status' AND t.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = '0');

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '已通过', '1', 2, 0, 1, 'system', NOW(), 'system', NOW() FROM sys_dict_type t
 WHERE t.dict_key = 'bpmn_approval_status' AND t.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = '1');

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '已拒绝', '2', 3, 0, 1, 'system', NOW(), 'system', NOW() FROM sys_dict_type t
 WHERE t.dict_key = 'bpmn_approval_status' AND t.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = '2');

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '已撤销', '3', 4, 0, 1, 'system', NOW(), 'system', NOW() FROM sys_dict_type t
 WHERE t.dict_key = 'bpmn_approval_status' AND t.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = '3');

-- 字典新增 system_leave_type（请假类型，bpmn_leave_type 语义迁名；3 项）
INSERT INTO sys_dict_type (dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT '请假类型', 'system_leave_type', 0, 1, 'system', NOW(), 'system', NOW() FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM sys_dict_type WHERE dict_key = 'system_leave_type');

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '事假', '1', 1, 0, 1, 'system', NOW(), 'system', NOW() FROM sys_dict_type t
 WHERE t.dict_key = 'system_leave_type' AND t.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = '1');

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '病假', '2', 2, 0, 1, 'system', NOW(), 'system', NOW() FROM sys_dict_type t
 WHERE t.dict_key = 'system_leave_type' AND t.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = '2');

INSERT INTO sys_dict_data (dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time)
SELECT t.id, '年假', '3', 3, 0, 1, 'system', NOW(), 'system', NOW() FROM sys_dict_type t
 WHERE t.dict_key = 'system_leave_type' AND t.deleted = 0
   AND NOT EXISTS (SELECT 1 FROM sys_dict_data dd WHERE dd.dict_type_id = t.id AND dd.value = '3');
