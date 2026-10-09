-- cloud-bpmn 库初始化（审批平台化：bpmn_approval 通用审批单 + bpmn_business_type 业务类型配置）
-- ⚠️ 开发环境重置口径：本脚本仅 DROP/CREATE 业务表；ACT_* 引擎表由 Flowable
--    database-schema-update=true 启动自动创建并维护，不进本脚本、重置时不动（设计 D2/D6）。
--    存量 bpmn_leave 已于 2026-10-08 审批平台化 DROP（请假迁 cloud-system，无数据迁移——契约
--    2026-10-08-approval-platform-api §1 清扫纪律），增量见 2026-10-08-approval-platform.sql。
-- 字符集混排记档（设计 D2）：库 utf8mb4/utf8mb4_general_ci；Flowable 自建 ACT_* 表为表级 utf8/utf8_bin
--    （7.2.0 实抓）——两域查询不混（无跨表 JOIN），功能无碍，差异在此记档。
CREATE DATABASE IF NOT EXISTS cloud_bpmn DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE cloud_bpmn;

DROP TABLE IF EXISTS bpmn_approval;
DROP TABLE IF EXISTS bpmn_business_type;

-- 索引设计（依据 mapper 查询清单，设计 D1 取舍）：
--   uk_business(business_type, business_key)：/inner/approval/create 查重（任意状态存在即拒——行永不删、
--     uk 跨终态生效，Service 前置查 4015 + DuplicateKeyException 兜底）与按业务键反查审批单两路径二合一；
--     语义约定=同一业务单据唯一审批（驳回后重新发起=业务方生成新单据，契约 §1）。
--   idx_apply_user(apply_user, deleted)：「我的审批」分页 WHERE apply_user=? AND deleted=0 ORDER BY id DESC；
--     带 deleted 组合避免回表后再滤。approver/status 维度不建：待办走 ACT_RU_TASK 引擎自建索引，
--     MVP 无按状态过滤的查询路径。
--   deleted 列仅为 BaseEntity 规范一致性（审批留档语义，无 API 删除出口）；行永不删 → uk 无墓碑占键问题。
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

-- 索引设计（设计 D2）：uk_type_code(type_code)：type_code 等值查（BusinessTypeRegistry.findByTypeCode）
--   与配置唯一性二合一；量级=业务类型数（个位级），无其他查询路径，不建冗余索引。
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

-- leave 业务类型种子行（契约 2026-10-08-approval-platform-api §1/设计 D2；与增量脚本
-- 2026-10-08-approval-platform.sql 语义等价——本表无 is_builtin 列，MVP 配置行即种子，管理界面后置再评估保护）
INSERT INTO bpmn_business_type (type_code, type_name, process_key, detail_route, create_by, create_time, update_by, update_time)
VALUES ('leave', '请假申请', 'leave_approval', '/system/leave?approval={businessKey}', 'system', NOW(), 'system', NOW());
