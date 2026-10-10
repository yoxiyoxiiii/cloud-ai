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
DROP TABLE IF EXISTS sys_leave;
DROP TABLE IF EXISTS sys_dept;
DROP TABLE IF EXISTS sys_data_perm_rule;
DROP TABLE IF EXISTS sys_data_perm_column;
DROP TABLE IF EXISTS sys_data_perm_log;
DROP TABLE IF EXISTS mq_tx_log;
DROP TABLE IF EXISTS mq_consume_dedup;
DROP TABLE IF EXISTS approval_projection;

-- 用户
CREATE TABLE sys_user (
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户ID',
    account     VARCHAR(30)  NOT NULL COMMENT '登录账号',
    nickname    VARCHAR(30)  NOT NULL DEFAULT '' COMMENT '昵称',
    dept_id     BIGINT       NULL     COMMENT '部门ID（sys_dept.id；NULL=未挂部门，部门类档位求值展开为空集）',
    password    VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码散列',
    status      TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    is_builtin  TINYINT      NOT NULL DEFAULT 0 COMMENT '内置标记：1=系统内置（禁删禁停用，昵称可改），0=用户创建',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_account (account),
    KEY idx_dept_id (dept_id)
) ENGINE = InnoDB COMMENT = '用户表';

-- 角色
CREATE TABLE sys_role (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '角色ID',
    name        VARCHAR(30) NOT NULL COMMENT '角色名称',
    role_key    VARCHAR(30) NOT NULL COMMENT '角色标识',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    is_builtin  TINYINT     NOT NULL DEFAULT 0 COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建',
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
    is_builtin  TINYINT     NOT NULL DEFAULT 0 COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建',
    create_by   VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT NULL COMMENT '创建时间',
    update_by   VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time DATETIME    DEFAULT NULL COMMENT '更新时间',
    deleted     TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    KEY idx_parent_id (parent_id)
) ENGINE = InnoDB COMMENT = '菜单权限表';

-- 部门表（数据权限轮 2026-10-10，设计 §3.1；与增量脚本 2026-10-10-data-permission.sql 同步落同一份定义）：
-- parent_id=0 为根的邻接表（沿 sys_menu 先例）。
-- 索引取舍：uk_parent_name 一键三用——同级重名查重 / 父节点子级扫描（最左前缀 parent_id）；
--   不另建 idx_parent_id（被 uk 最左前缀覆盖，沿 uk_type_value 先例）；不建 deleted 单列（0/1 低选择性）。
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

-- 字典类型/字典项（数据字典管理，2026-10-07；与增量脚本 2026-10-07-dict-mgmt.sql 同步落同一份定义）
CREATE TABLE sys_dict_type (
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '字典类型ID',
    dict_name   VARCHAR(30) NOT NULL COMMENT '字典名称，如：用户状态',
    dict_key    VARCHAR(50) NOT NULL COMMENT '字典键，全库唯一，如：user_status',
    status      TINYINT     NOT NULL DEFAULT 0 COMMENT '0正常 1停用',
    is_builtin  TINYINT     NOT NULL DEFAULT 0 COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建',
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
    is_builtin   TINYINT     NOT NULL DEFAULT 0 COMMENT '内置标记：1=系统内置（禁删禁改含停用），0=用户创建',
    create_by    VARCHAR(30)  DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT NULL COMMENT '创建时间',
    update_by    VARCHAR(30)  DEFAULT NULL COMMENT '更新人',
    update_time  DATETIME     DEFAULT NULL COMMENT '更新时间',
    deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_value (dict_type_id, value)
) ENGINE = InnoDB COMMENT = '字典项表';

-- 请假单（审批平台化：业务台账迁 cloud-system，契约 2026-10-08-approval-platform-api §5/设计 D4；
-- 2026-10-09 投影改造：快照列 status/approval_id 退役物理删除（Q2=A）——对外 status/approvalId 由
-- 读路径 LEFT JOIN approval_projection 派生/直出（契约 2026-10-09-approval-projection-api §4.1），
-- 本表仅业务台账字段；同一业务单据唯一审批由平台侧 bpmn_approval.uk_business 保证）
-- 索引设计（沿 bpmn_leave 先例）：idx_apply_user(apply_user, deleted)：唯一过滤面「我的请假」
--   WHERE apply_user=? AND deleted=0 ORDER BY id DESC；uk_ 无（title 可重名）
CREATE TABLE sys_leave (
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '请假单ID（即平台 bpmn_approval.business_key；MQ 事务消息形态下 snowflake 预生成显式插入）',
    title        VARCHAR(100) NOT NULL COMMENT '请假标题（e2e 前缀锚点）',
    leave_type   TINYINT      NOT NULL COMMENT '请假类型：1事假 2病假 3年假（字典 system_leave_type）',
    start_date   DATE         NOT NULL COMMENT '开始日期',
    end_date     DATE         NOT NULL COMMENT '结束日期',
    reason       VARCHAR(500) NULL     COMMENT '事由说明',
    apply_user   VARCHAR(30)  NOT NULL COMMENT '申请人账号（sys_user.account）',
    approver     VARCHAR(30)  NOT NULL COMMENT '审批人账号（发起时指定，引擎 assignee）',
    create_by    VARCHAR(30)  NULL     COMMENT '创建人',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by    VARCHAR(30)  NULL     COMMENT '更新人',
    update_time  DATETIME     NULL     COMMENT '更新时间',
    deleted      TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    KEY idx_apply_user (apply_user, deleted)
) ENGINE = InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT = '请假单（cloud-system 业务台账；审批编排经 cloud-bpmn /inner/approval）';

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

-- MQ 事务消息本地事务流水（MQ 化改造，契约 2026-10-09-rocketmq-tx-approval-api / 设计 D8；
-- 与增量脚本 2026-10-09-rocketmq-tx-approval.sql 语义等价。索引取舍：uk_tx_no 命中回查点查、
-- idx_business 命中运维排查；无按时间查询路径不建 create_time 索引——清理低峰全表扫可接受）
CREATE TABLE mq_tx_log (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    tx_no         VARCHAR(64)  NOT NULL COMMENT '事务消息流水号（发送前预生成 UUID，userProperty TX_NO 回传；回查唯一依据：行存在=COMMIT 行缺失=ROLLBACK）',
    topic         VARCHAR(64)  NOT NULL COMMENT '目标 topic（TX_APPROVAL_CREATE / APPROVAL_EVENT_NOTIFY）',
    channel       VARCHAR(64)  NOT NULL COMMENT '业务通道标识（starter TxLocalExecutor.channel，如 leave-create/terminal-complete/terminal-cancel）',
    business_type VARCHAR(50)  NOT NULL COMMENT '业务类型（消息业务维度，如 leave）',
    business_key  VARCHAR(64)  NOT NULL COMMENT '业务键（流1=leaveId 流2=approvalId）',
    result_digest VARCHAR(500) NULL     COMMENT '本地事务结果摘要（executor 返回值 JSON 截断，仅运维观测）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（=本地事务提交时间）',
    update_time   DATETIME     NULL     COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tx_no (tx_no),
    KEY idx_business (business_type, business_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 事务消息本地事务流水（starter 回查依据；审计保留 N 天后定时清理）';

-- MQ 消费通用去重表（先插后消费/失败删行放行重试；仅事件消费方 cloud_system 库。
-- 索引取舍：uk_group_key 即约束即索引；idx_create_time 命中清理范围删除）
CREATE TABLE mq_consume_dedup (
    id             BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    consumer_group VARCHAR(64)  NOT NULL COMMENT '消费组（g_<svc>_<purpose>，如 g_system_approval_event）',
    msg_key        VARCHAR(190) NOT NULL COMMENT '消息幂等键（优先消息 KEYS 业务键，缺省 msgId）',
    create_time    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间（=消费开始时间，清理锚点）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_group_key (consumer_group, msg_key),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='MQ 消费通用去重表（先插后消费/失败删行放行重试；N 天定时清理）';

-- 审批状态本地投影（2026-10-09 投影改造，契约 2026-10-09-approval-projection-api §1 / 设计 D2；
-- 与增量脚本 2026-10-09-approval-projection.sql 段① 语义等价）：bpmn_approval 只读副本（read model），
-- 真相源不变；cloud-bpmn-api 框架组件写入（事件消费 upsert + 定时对账），业务表 JOIN 读，业务侧禁止直写。
-- 索引取舍：uk_business 一索三用——业务 JOIN 探针 / 消费 upsert 依据 / 对账按键回写；
--   idx_approval_status 命中对账范围扫描（低基数量小取舍记档，语义显式）；无按 approval_id 反查路径不建；
--   无逻辑删除列：行永不删（审批留档语义同 bpmn_approval），无 deleted 过滤负担。
CREATE TABLE approval_projection (
    id                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    business_type       VARCHAR(50)  NOT NULL COMMENT '业务类型编码（同 bpmn_approval.business_type，如 leave）',
    business_key        VARCHAR(64)  NOT NULL COMMENT '业务单据键（业务方主键字符串化，如请假单id）',
    approval_id         BIGINT       NULL     COMMENT '审批单id（bpmn_approval.id；CREATE_RESULT/SUCCESS 事件或对账回填；发起失败为 NULL）',
    process_instance_id VARCHAR(64)  NULL     COMMENT '流程实例ID（引擎实例标识；CREATE_RESULT/SUCCESS 事件或对账回填——Round E Q4 新增）',
    create_result       TINYINT      NOT NULL DEFAULT 0 COMMENT '发起结果：0未知(结果事件未达) 1成功 2失败（CREATE_RESULT 事件回填；与 approval_status 独立收敛，互不覆盖）',
    approval_status     TINYINT      NOT NULL DEFAULT 0 COMMENT '审批状态：0审批中 1已通过 2已拒绝 3已撤销（忠实投影 bpmn_approval.status，永不落4；对外 status=4 发起失败由读侧 CASE 从 create_result=2 派生）',
    create_by           VARCHAR(30)  NULL     COMMENT '创建人（写入方标识：bpmn-event=事件消费 / approval-reconcile=对账回写）',
    create_time         DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by           VARCHAR(30)  NULL     COMMENT '更新人（同 create_by 语义）',
    update_time         DATETIME     NULL     COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_business (business_type, business_key),
    KEY idx_approval_status (approval_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
  COMMENT='审批状态本地投影（bpmn_approval 只读副本，真相源不变；cloud-bpmn-api 框架组件写入：事件消费 upsert + 定时对账；业务表 JOIN 读，业务侧禁止直写）';

-- 数据权限行级规则（数据权限轮 2026-10-10 设计 §3.2，与增量脚本同步落同一份定义；物理删除——D9）：
-- 索引取舍：uk_resource_subject 兼查重与求值查询（求值器 resource 等值 + subject 过滤最左前缀命中）；
--   无 deleted 列（规则删除=物理 DELETE，墓碑占 uk 会废掉 upsert「删了再配」语义，配置历史观察归留痕表）。
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

-- 数据权限列级规则（设计 §3.3，物理删除同 D9）：
-- 索引取舍：uk_subject_column 兼查重与求值/回显查询（最左前缀 resource+subject 覆盖按主体取列规则）；
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

-- 数据权限决策留痕（设计 §3.4，只插不删的流水表——沿 mq_tx_log 先例）：
-- 索引取舍：idx_account_time 命中排查页「按用户查决策史」（等值+时间倒序范围）；
--   idx_resource_time 命中「按资源查」；无 update 路径；清理任务（保留窗口）移交。
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

-- ---------- 初始数据 ----------
-- 内置种子一律显式 is_builtin=1（内置保护契约 §1：后续新增内置种子 SQL 须带 is_builtin=1）
INSERT INTO sys_role (id, name, role_key, is_builtin, create_time) VALUES (1, '管理员', 'admin', 1, NOW());

-- path/icon 初值与增量脚本 2026-10-07-menu-nav.sql 语义等价（21 在线用户 path/icon 均空串，不进导航）
INSERT INTO sys_menu (id, parent_id, name, perms, type, path, icon, sort, is_builtin, create_time) VALUES
(10, 0, '系统管理',   '',                   'M', '',             'Setting',    1, 1, NOW()),
(11, 10, '用户管理',  'system:user:list',   'C', '/system/user', 'User',       1, 1, NOW()),
(12, 10, '角色管理',  'system:role:list',   'C', '/system/role', 'UserFilled', 2, 1, NOW()),
(13, 10, '菜单管理',  'system:menu:list',   'C', '/system/menu', 'Menu',       3, 1, NOW()),
(111, 11, '用户新增', 'system:user:add',    'F', '',             '',           1, 1, NOW()),
(112, 11, '用户修改', 'system:user:edit',   'F', '',             '',           2, 1, NOW()),
(113, 11, '用户删除', 'system:user:remove', 'F', '',             '',           3, 1, NOW()),
(114, 11, '重置密码', 'system:user:resetPwd','F', '',            '',           4, 1, NOW()),
(115, 11, '分配角色', 'system:user:assignRole','F', '',          '',           5, 1, NOW()),
(121, 12, '角色新增', 'system:role:add',    'F', '',             '',           1, 1, NOW()),
(122, 12, '角色修改', 'system:role:edit',   'F', '',             '',           2, 1, NOW()),
(123, 12, '角色删除', 'system:role:remove', 'F', '',             '',           3, 1, NOW()),
(124, 12, '分配权限', 'system:role:assignMenu','F', '',          '',           4, 1, NOW()),
(131, 13, '菜单新增', 'system:menu:add',    'F', '',             '',           1, 1, NOW()),
(132, 13, '菜单修改', 'system:menu:edit',   'F', '',             '',           2, 1, NOW()),
(133, 13, '菜单删除', 'system:menu:remove', 'F', '',             '',           3, 1, NOW()),
(20, 0, '认证管理',   '',                   'M', '',             'Lock',       2, 1, NOW()),
(21, 20, '在线用户',  'sso:online:list',    'C', '',             '',           1, 1, NOW()),
(211, 21, '强制下线', 'sso:online:kick',    'F', '',             '',           1, 1, NOW()),
-- 字典管理菜单（种子分段 14x；与增量脚本 2026-10-07-dict-mgmt.sql 语义等价，
-- admin 绑定由下方 sys_role_menu 的 SELECT 全量式天然覆盖，不重复加显式绑定）
(14, 10, '字典管理',  'system:dict:list',   'C', '/system/dict', 'Files',      4, 1, NOW()),
(141, 14, '字典新增', 'system:dict:add',    'F', '',             '',           1, 1, NOW()),
(142, 14, '字典修改', 'system:dict:edit',   'F', '',             '',           2, 1, NOW()),
(143, 14, '字典删除', 'system:dict:remove', 'F', '',             '',           3, 1, NOW()),
-- 流程管理菜单（30 段 10 行；与增量脚本 2026-10-07-bpmn-menus.sql / 2026-10-08-bpmn-deploy-menu.sql /
-- 2026-10-08-approval-platform.sql 语义等价——契约 2026-10-08-approval-platform-api §8：
-- 31 段改造为 system 请假域（请假申请/system:leave:*，path /system/leave），34/341 新增平台我的审批；
-- admin 绑定由下方 sys_role_menu 的 SELECT 全量式天然覆盖，不重复加显式绑定）
(30,  0,  '流程管理', '',                    'M', '',               'Tickets',   3, 1, NOW()),
(31,  30, '请假申请', 'system:leave:list',   'C', '/system/leave',   'Document',  1, 1, NOW()),
(34,  30, '我的审批', 'bpmn:approval:list',  'C', '/bpmn/approval',  'Document',  2, 1, NOW()),
(32,  30, '待办任务', 'bpmn:task:list',      'C', '/bpmn/task',      'Bell',      3, 1, NOW()),
(33,  30, '流程定义', 'bpmn:definition:list','C', '/bpmn/definition','Files',     4, 1, NOW()),
(311, 31, '发起申请', 'system:leave:add',    'F', '', '', 1, 1, NOW()),
(312, 31, '撤销申请', 'system:leave:cancel', 'F', '', '', 2, 1, NOW()),
(321, 32, '办理任务', 'bpmn:task:complete',  'F', '', '', 1, 1, NOW()),
(331, 33, '部署流程', 'bpmn:definition:deploy', 'F', '', '', 1, 1, NOW()),
(341, 34, '撤销审批', 'bpmn:approval:cancel','F', '', '', 1, 1, NOW()),
-- 数据权限菜单（15/16 菜单级 + 151/152/153、161/162 按钮级，全 is_builtin=1；与增量脚本
-- 2026-10-10-data-permission.sql 语义等价，admin 绑定由下方 sys_role_menu 的 SELECT 全量式天然覆盖）
(15,  10, '部门管理', 'system:dept:list',     'C', '/system/dept',      'OfficeBuilding', 5, 1, NOW()),
(151, 15, '部门新增', 'system:dept:add',      'F', '', '', 1, 1, NOW()),
(152, 15, '部门修改', 'system:dept:edit',     'F', '', '', 2, 1, NOW()),
(153, 15, '部门删除', 'system:dept:remove',   'F', '', '', 3, 1, NOW()),
(16,  10, '数据权限', 'system:dataPerm:list', 'C', '/system/data-perm', 'Key',            6, 1, NOW()),
(161, 16, '保存规则', 'system:dataPerm:save',   'F', '', '', 1, 1, NOW()),
(162, 16, '删除规则', 'system:dataPerm:remove', 'F', '', '', 2, 1, NOW());

-- admin 账号（密码 admin123；数据权限轮起挂根部门 dept_id=1——与增量脚本 UPDATE 语义等价）
INSERT INTO sys_user (id, account, nickname, dept_id, password, is_builtin, create_time) VALUES
(1, 'admin', '管理员', 1, '$2a$10$.8cM9ZR8tr7HxklhRPzmwORoIi.z8urt0wZ4a3QaGwgFyyGMle4sG', 1, NOW());

INSERT INTO sys_user_role (user_id, role_id, create_time) VALUES (1, 1, NOW());
INSERT INTO sys_role_menu (role_id, menu_id, create_time)
SELECT 1, id, NOW() FROM sys_menu;

-- 数据权限种子（设计 §3.6，与增量脚本 2026-10-10-data-permission.sql 语义等价）：
-- 根部门 is_builtin=1 内置保护禁删；admin 行规则=leave 全部档（无规则默认 SELF 会使 admin 看不到全量，
-- 违背管理员预期——代码无 admin 特例，规则面前人人平等，设计 D3）
INSERT INTO sys_dept (id, parent_id, name, sort, status, is_builtin, create_by, create_time, update_by, update_time)
VALUES (1, 0, '总公司', 0, 0, 1, 'admin', NOW(), 'admin', NOW());

INSERT INTO sys_data_perm_rule (resource, subject_type, subject_id, row_scope, custom_accounts,
                                create_by, create_time, update_by, update_time)
VALUES ('leave', 0, 1, 4, NULL, 'admin', NOW(), 'admin', NOW());

-- 内置字典种子 user_status（与增量脚本 2026-10-07-translation.sql 语义等价；
-- label 文案锁定契约 2026-10-07-translation-api §0.3，create_by='system' 内置标记）
INSERT INTO sys_dict_type (id, dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time) VALUES
(1, '用户状态', 'user_status', 0, 1, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (id, dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time) VALUES
(1, 1, '正常', '0', 1, 0, 1, 'system', NOW(), 'system', NOW()),
(2, 1, '停用', '1', 2, 0, 1, 'system', NOW(), 'system', NOW());

-- 内置字典种子 common_status（通用状态；与增量脚本 2026-10-07-common-status-seed.sql 语义等价，
-- 存量环境 id 由 AUTO_INCREMENT 自动分配，id 非契约内容（内置保护按 is_builtin 判定，与 id 无关）；
-- role/menu/dict 四域 status 译文字典键（契约 2026-10-07-translation-api §8.1/§8.3））
INSERT INTO sys_dict_type (id, dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time) VALUES
(2, '通用状态', 'common_status', 0, 1, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (id, dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time) VALUES
(3, 2, '正常', '0', 1, 0, 1, 'system', NOW(), 'system', NOW()),
(4, 2, '停用', '1', 2, 0, 1, 'system', NOW(), 'system', NOW());

-- 内置字典种子 bpmn_approval_status/system_leave_type（审批平台化迁移，契约 2026-10-08-approval-platform-api §7；
-- 与增量脚本 2026-10-08-approval-platform.sql 语义等价；基线 dict_type id=5/6 头注释 id 非契约内容——
-- 存量环境 AUTO_INCREMENT 分配，内置保护按 is_builtin 判定与 id 无关。原 bpmn_leave_status/bpmn_leave_type
-- 随请假域废弃删除（状态字典平台共用 bpmn_approval_status，类型字典键迁 system_leave_type）。
-- 「发起失败」(4) 为 MQ 化改造新增（契约 2026-10-09-rocketmq-tx-approval-api §1.2，与增量
-- 2026-10-09-rocketmq-tx-approval.sql 语义等价——增量环境 dict_data id 由 AUTO_INCREMENT 分配））
INSERT INTO sys_dict_type (id, dict_name, dict_key, status, is_builtin, create_by, create_time, update_by, update_time) VALUES
(5, '审批状态', 'bpmn_approval_status', 0, 1, 'system', NOW(), 'system', NOW()),
(6, '请假类型', 'system_leave_type',    0, 1, 'system', NOW(), 'system', NOW());

INSERT INTO sys_dict_data (id, dict_type_id, label, value, sort, status, is_builtin, create_by, create_time, update_by, update_time) VALUES
(5,  5, '审批中', '0', 1, 0, 1, 'system', NOW(), 'system', NOW()),
(6,  5, '已通过', '1', 2, 0, 1, 'system', NOW(), 'system', NOW()),
(7,  5, '已拒绝', '2', 3, 0, 1, 'system', NOW(), 'system', NOW()),
(8,  5, '已撤销', '3', 4, 0, 1, 'system', NOW(), 'system', NOW()),
(12, 5, '发起失败', '4', 5, 0, 1, 'system', NOW(), 'system', NOW()),
(9,  6, '事假',   '1', 1, 0, 1, 'system', NOW(), 'system', NOW()),
(10, 6, '病假',   '2', 2, 0, 1, 'system', NOW(), 'system', NOW()),
(11, 6, '年假',   '3', 3, 0, 1, 'system', NOW(), 'system', NOW());
