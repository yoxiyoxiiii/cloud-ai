-- 数据权限控制 + 规则引擎存量库增量（契约 2026-10-10-data-permission-api.md / 设计 2026-10-10-data-permission-design.md §3）。
-- 内容：四新表（sys_dept / sys_data_perm_rule / sys_data_perm_column / sys_data_perm_log）+
--   sys_user 加列 dept_id（含 idx_dept_id）+ 种子（根部门 / admin 挂部门 / admin 行规则 / 菜单 7 行 + admin 绑定）。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT id FROM sys_menu WHERE id IN (15,16,151,152,153,161,162);        -- 应 0 行（已实测空闲 2026-10-10）
--   2) SELECT table_name FROM information_schema.tables WHERE table_schema='cloud_system'
--        AND table_name IN ('sys_dept','sys_data_perm_rule','sys_data_perm_column','sys_data_perm_log');  -- 应 0 行
--   3) SHOW COLUMNS FROM sys_user LIKE 'dept_id';                                -- 应 0 行（列在即停：核对再定）
-- ⚠️ 不可重放：种子 id 固定（菜单 15/16/151/152/153/161/162、部门 1）——重放先 DELETE 对应 id 与
--   sys_role_menu 绑定、sys_data_perm_rule 全表（AUTO_INCREMENT 无显式 id，按 resource/subject 判存）。
-- 基线 cloud_system.sql 已同步同款定义，新环境直接跑基线无需本增量。
-- 规则表物理删除口径（设计 D9）：sys_data_perm_rule/sys_data_perm_column 无 deleted 列（配置态数据，
--   墓碑占 uk 会废掉 upsert「删了再配」语义，配置历史观察职责归决策留痕表）；sys_data_perm_log 只插不删。

USE cloud_system;

-- ① 部门表（设计 §3.1）：parent_id=0 为根的邻接表（沿 sys_menu 先例）；
--   索引取舍：uk_parent_name 一键三用——同级重名查重（countByParentAndName）/ 父节点子级扫描
--   （最左前缀 parent_id，树页与删除校验）；不另建 idx_parent_id（被 uk 最左前缀覆盖，沿 uk_type_value 先例）；
--   不建 deleted 单列（0/1 低选择性，规范明令）；求值器无按父查询路径（全量 listAll 内存组树）。
CREATE TABLE sys_dept (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '部门ID',
    parent_id   BIGINT      NOT NULL DEFAULT 0 COMMENT '父部门ID，0为根',
    name        VARCHAR(30) NOT NULL COMMENT '部门名称',
    sort        INT         NOT NULL DEFAULT 0 COMMENT '排序（同级内升序）',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    is_builtin  TINYINT     NOT NULL DEFAULT 0 COMMENT '内置标记：1=系统内置根部门（禁删），0=用户创建',
    create_by   VARCHAR(30) DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30) DEFAULT NULL COMMENT '更新人',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_parent_name (parent_id, name)
) ENGINE = InnoDB COMMENT = '部门表（树形，parent_id=0 为根；同级重名由 uk_parent_name 约束）';

-- ② 数据权限行级规则（设计 §3.2，物理删除——D9）；
--   索引取舍：uk_resource_subject 兼查重与求值查询（求值器按 resource 等值 + subject 过滤，最左前缀命中）；
--   无 deleted 列（规则删除=物理 DELETE；配置历史观察职责归决策留痕表）。
CREATE TABLE sys_data_perm_rule (
    id              BIGINT        NOT NULL AUTO_INCREMENT COMMENT '规则ID',
    resource        VARCHAR(50)   NOT NULL COMMENT '资源标识（DataPermResources 注册表管辖，如 leave）',
    subject_type    TINYINT       NOT NULL COMMENT '主体类型：0=角色 1=用户',
    subject_id      BIGINT        NOT NULL COMMENT '主体ID（subject_type=0 时 sys_role.id，=1 时 sys_user.id）',
    row_scope       TINYINT       NOT NULL COMMENT '行范围档位：0仅自己 1本部门 2本部门及以下 3自定义集合 4全部',
    custom_accounts VARCHAR(1000) NULL     COMMENT '自定义账号集合（row_scope=3 时生效，JSON 数组字符串如 ["zhang3","lisi4"]；其余档位 NULL）',
    create_by       VARCHAR(30)   DEFAULT NULL COMMENT '创建人',
    create_time     DATETIME      DEFAULT NULL COMMENT '创建时间',
    update_by       VARCHAR(30)   DEFAULT NULL COMMENT '更新人',
    update_time     DATETIME      DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_resource_subject (resource, subject_type, subject_id)
) ENGINE = InnoDB COMMENT = '数据权限行级规则（配置态数据物理删除无 deleted 列；同主体同资源至多一条，upsert 依据即 uk）';

-- ③ 数据权限列级规则（设计 §3.3，物理删除同 D9）；
--   索引取舍：uk_subject_column 兼查重与求值/回显查询（最左前缀 resource+subject 覆盖按主体取列规则）；
--   列规则按 save 全删全插维护，无独立 update 路径。
CREATE TABLE sys_data_perm_column (
    id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '列规则ID',
    resource     VARCHAR(50) NOT NULL COMMENT '资源标识（同 sys_data_perm_rule.resource）',
    subject_type TINYINT     NOT NULL COMMENT '主体类型：0=角色 1=用户',
    subject_id   BIGINT      NOT NULL COMMENT '主体ID',
    column_key   VARCHAR(50) NOT NULL COMMENT '列标识（资源注册表 VO 字段名，如 reason；配置经 3034 校验）',
    action       TINYINT     NOT NULL COMMENT '列动作：0隐藏 1脱敏',
    create_by    VARCHAR(30) DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by    VARCHAR(30) DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME    DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_subject_column (resource, subject_type, subject_id, column_key)
) ENGINE = InnoDB COMMENT = '数据权限列级规则（同 D9 物理删除；一主体一资源一列至多一条）';

-- ④ 数据权限决策留痕（设计 §3.4，只插不删的流水表——沿 mq_tx_log 先例）；
--   索引取舍：idx_account_time 命中排查页主路径「按用户查决策史」（等值+时间倒序范围）；
--   idx_resource_time 命中「按资源查」（排查某资源全体决策）；无 update 路径；清理任务（保留窗口）移交。
CREATE TABLE sys_data_perm_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '留痕ID',
    account        VARCHAR(30)  NOT NULL COMMENT '决策对象账号（求值时的登录人）',
    resource       VARCHAR(50)  NOT NULL COMMENT '资源标识',
    operation      VARCHAR(20)  NOT NULL COMMENT '操作：list 分页查询 / detail 详情查询 / deny 详情被拒（安全审计事件）',
    rule_ids       VARCHAR(500) NULL     COMMENT '命中行规则ID集合（逗号分隔；NULL=无规则命中走默认档）',
    rule_digest    VARCHAR(500) NULL     COMMENT '决策摘要（命中规则与档位可读描述，如 role:主管(id=2)本部门及以下|user:zhang3自定义集合2人）',
    scope_summary  VARCHAR(200) NOT NULL COMMENT '行范围结论：all=过滤豁免 / accounts=N（展开账号数）/ empty=空集',
    column_summary VARCHAR(200) NULL     COMMENT '列决策结论（如 reason:脱敏;title:隐藏；NULL=无列动作）',
    business_key   VARCHAR(64)  NULL     COMMENT '业务键（detail/deny 时为目标单据 id；list 为 NULL）',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '决策时间',
    PRIMARY KEY (id),
    KEY idx_account_time (account, create_time),
    KEY idx_resource_time (resource, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='数据权限决策留痕（每次真实查询决策一条 + 详情拒绝补记 deny；排查页按账号/资源检索；只插不删，清理策略移交）';

-- ⑤ sys_user 加列（设计 §3.5）：NULL=未挂部门（部门类档位求值展开为空集，语义见 D3）；
--   idx_dept_id 命中求值器「部门(含子树)→启用账号集合」WHERE dept_id IN (...) AND status=0 AND deleted=0
--   与部门删除前挂载用户 count 校验；求值器走 IN 多值 range scan，单值等值同命中。
ALTER TABLE sys_user
    ADD COLUMN dept_id BIGINT NULL COMMENT '部门ID（sys_dept.id；NULL=未挂部门，部门类档位求值展开为空集）' AFTER nickname,
    ADD KEY idx_dept_id (dept_id);

-- ⑥ 种子（设计 §3.6）：根部门 is_builtin=1（内置保护禁删）；admin 挂根部门，其余存量用户保持 NULL；
--   admin 行规则种子=leave 全部档（无规则默认 SELF 会使 admin 行为回退看不到全量，违背管理员预期——
--   代码无 admin 特例，规则面前人人平等是「可解释」前提，设计 D3）；菜单种子 id 已核对空闲。
INSERT INTO sys_dept (id, parent_id, name, sort, status, is_builtin, create_by, create_time, update_by, update_time)
VALUES (1, 0, '总公司', 0, 0, 1, 'admin', NOW(), 'admin', NOW());

UPDATE sys_user SET dept_id = 1 WHERE id = 1 AND deleted = 0;

INSERT INTO sys_data_perm_rule (resource, subject_type, subject_id, row_scope, custom_accounts,
                                create_by, create_time, update_by, update_time)
VALUES ('leave', 0, 1, 4, NULL, 'admin', NOW(), 'admin', NOW());

-- 数据权限菜单（15/16 菜单级 + 151/152/153、161/162 按钮级，全 is_builtin=1；与基线 cloud_system.sql 同步落位；
-- admin 绑定走显式 id 列表——基线全量 SELECT 式天然覆盖零改动，沿 dict 先例）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(15,  10, '部门管理', 'system:dept:list',     'C', '/system/dept',      'OfficeBuilding', 5, 1, NOW()),
(151, 15, '部门新增', 'system:dept:add',      'F', '', '', 1, 1, NOW()),
(152, 15, '部门修改', 'system:dept:edit',     'F', '', '', 2, 1, NOW()),
(153, 15, '部门删除', 'system:dept:remove',   'F', '', '', 3, 1, NOW()),
(16,  10, '数据权限', 'system:dataPerm:list', 'C', '/system/data-perm', 'Key',            6, 1, NOW()),
(161, 16, '保存规则', 'system:dataPerm:save',   'F', '', '', 1, 1, NOW()),
(162, 16, '删除规则', 'system:dataPerm:remove', 'F', '', '', 2, 1, NOW());

INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu WHERE id IN (15, 151, 152, 153, 16, 161, 162);
