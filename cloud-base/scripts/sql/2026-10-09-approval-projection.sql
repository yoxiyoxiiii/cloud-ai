-- 审批状态本地投影改造存量库增量（契约 2026-10-09-approval-projection-api §6 / 设计 2026-10-09-approval-projection-design D2/D6）。
-- 执行顺序：先段①建投影表，后段②删 sys_leave 两列（读路径 JOIN 依赖投影表先在；建议与应用同发布窗口完成）。
-- 存量口径（Q5=B 拍板）：投影表空表起步不回填——删列前存量 sys_leave 行 JOIN 无行一律读作
--   status=0（审批中）/approvalId=null（演示库清扫容忍，不做迁移）。
-- 回滚口径：段② 删列不可逆——状态可从平台 bpmn_approval 反查重建，勿手工回插旧列
--   （与投影表双源冲突，设计 R6）。
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) SELECT 1 FROM information_schema.tables WHERE table_schema='cloud_system'
--        AND table_name='approval_projection';   -- 应 0 行（存在即停：核对表结构再定）
--   2) SHOW COLUMNS FROM sys_leave LIKE 'status'; -- 应 1 行（列在才可 DROP；0 行=已被处理即停）
-- 基线 cloud_system.sql 已同步同款定义，新环境直接跑基线无需本增量。

USE cloud_system;

-- ① 审批状态本地投影（设计 D2 原文）：bpmn_approval 只读副本（read model），真相源不变；
--   cloud-bpmn-api 框架组件写入（事件消费 upsert + 定时对账），业务表 JOIN 读，业务侧禁止直写。
-- 索引取舍：uk_business 一索三用——①业务 JOIN（pageList/findById 按键等值关联探针）②消费 upsert
--   （INSERT ... ON DUPLICATE KEY UPDATE 依据）③对账按键回写。idx_approval_status 命中对账范围扫描
--   （WHERE approval_status=0 拉非终态行）；低基数选择性差，但单库审批单量级（演示/单机）下全扫亦无压力，
--   建索引只为语义显式。无按 approval_id 反查路径（detailPath 跳转由前端列表行匹配，无服务端端点），不建。
--   无逻辑删除列：行永不删（审批留档语义同 bpmn_approval；mq_tx_log 同款先例），无 deleted 过滤负担。
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

-- ② sys_leave 快照退役（Q2=A）：状态/审批单关联两列物理删除，读路径改 JOIN approval_projection 派生
--   （对外 status = CASE WHEN create_result=2 THEN 4 ELSE IFNULL(approval_status,0) END；无行→0 审批中）；
--   消息/字典/菜单种子零变化。
ALTER TABLE sys_leave
    DROP COLUMN status,
    DROP COLUMN approval_id;
