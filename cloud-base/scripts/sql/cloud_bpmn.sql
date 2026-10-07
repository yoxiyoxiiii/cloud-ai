-- cloud-bpmn 库初始化（阶段4：bpmn_leave 业务表）
-- ⚠️ 开发环境重置口径：本脚本仅 DROP/CREATE bpmn_leave（业务表）；ACT_* 引擎表由 Flowable
--    database-schema-update=true 启动自动创建并维护，不进本脚本、重置时不动（设计 D2/D6）。
-- 字符集混排记档（设计 D2）：库 utf8mb4/utf8mb4_general_ci；Flowable 自建 ACT_* 表为表级 utf8/utf8_bin
--    （7.2.0 实抓）——两域查询不混（无跨表 JOIN），功能无碍，差异在此记档。
CREATE DATABASE IF NOT EXISTS cloud_bpmn DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE cloud_bpmn;

DROP TABLE IF EXISTS bpmn_leave;

-- 索引设计（依据 mapper 查询清单，设计 D6 取舍）：
--   idx_apply_user(apply_user, deleted)：唯一过滤面「我的申请」WHERE apply_user=? AND deleted=0 ORDER BY id DESC；
--     带 deleted 组合避免回表后再滤。审批人维度不建（待办/已办走 ACT_RU_TASK/ACT_HI_TASKINST，assignee_ 引擎自建索引）。
--   idx_status 不建：「我的申请」不按状态过滤（前端降级链展示），无查询路径。
--   uk_ 无：title 可重名，无业务唯一键（同日重复请假合法，防重不设）。
CREATE TABLE bpmn_leave (
    id                   BIGINT       NOT NULL AUTO_INCREMENT COMMENT '请假单ID',
    title                VARCHAR(100) NOT NULL COMMENT '请假标题（e2e 前缀锚点）',
    leave_type           TINYINT      NOT NULL COMMENT '请假类型：1事假 2病假 3年假（字典 bpmn_leave_type）',
    start_date           DATE         NOT NULL COMMENT '开始日期',
    end_date             DATE         NOT NULL COMMENT '结束日期',
    reason               VARCHAR(500) NULL     COMMENT '事由说明',
    status               TINYINT      NOT NULL DEFAULT 0 COMMENT '状态：0审批中 1已通过 2已拒绝 3已撤销（字典 bpmn_leave_status）',
    apply_user           VARCHAR(30)  NOT NULL COMMENT '申请人账号（sys_user.account）',
    approver             VARCHAR(30)  NOT NULL COMMENT '审批人账号（发起时指定，引擎 assignee）',
    process_instance_id  VARCHAR(64)  NULL     COMMENT '流程实例ID（发起后回填，关联 ACT）',
    create_by            VARCHAR(30)  NULL     COMMENT '创建人',
    create_time          DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_by            VARCHAR(30)  NULL     COMMENT '更新人',
    update_time          DATETIME     NULL     COMMENT '更新时间',
    deleted              TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常 1已删',
    PRIMARY KEY (id),
    KEY idx_apply_user (apply_user, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='请假单（demo 业务，流程引擎 businessKey=id）';
