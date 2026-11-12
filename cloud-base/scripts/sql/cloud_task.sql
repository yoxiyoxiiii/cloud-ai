-- cloud_task 库初始化（通用任务中心：async_task 流水表）
-- ⚠️ 开发环境初始化脚本：DROP 并重建 cloud_task 全部表，生产环境禁止执行
-- 与增量脚本 2026-11-10-task-center.sql 段① 一致；设计 docs/superpowers/specs/2026-11-10-task-center-design.md §4
CREATE DATABASE IF NOT EXISTS cloud_task DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE cloud_task;

DROP TABLE IF EXISTS async_task;

-- 流水式表：任务行无软删语义，终态行到期物理清理（沿 mq_tx_log/sys_shared_secret 先例）——无 deleted 列；
-- 索引取舍：uk_type_biz=提交幂等查重 + 并发兜底（biz_key NULL 多行共存=不传键不查重）；
-- idx_status_create=pickup 认领扫描（status=0 等值 + create_time 升序）+ 状态筛选分页；
-- idx_create_time=无筛选默认时间倒序分页
CREATE TABLE async_task (
    id               BIGINT        NOT NULL AUTO_INCREMENT COMMENT '任务ID（对外字符串化，全局 Jackson Long→String 口径）',
    task_type        VARCHAR(50)   NOT NULL COMMENT '任务类型码（handler 注册键，如 demo-batch）',
    biz_key          VARCHAR(100)  DEFAULT NULL COMMENT '业务幂等键（NULL=不查重；非空时与 task_type 库级唯一，防双击/重试风暴重复提交）',
    status           TINYINT       NOT NULL DEFAULT 0 COMMENT '状态：0排队 1执行中 2成功 3失败(含超时) 4已取消',
    progress         INT           NOT NULL DEFAULT 0 COMMENT '进度百分比 0-100',
    phase            VARCHAR(50)   DEFAULT NULL COMMENT '执行阶段描述（handler 上报，如 批处理中）',
    params           JSON          DEFAULT NULL COMMENT '任务参数（提交方 JSON 对象文本，handler 按类型自解析）',
    result           JSON          DEFAULT NULL COMMENT '成功结果摘要（handler 返回，JSON 文本）',
    error_msg        VARCHAR(1000) DEFAULT NULL COMMENT '失败原因（异常摘要或超时说明；成功/取消为 NULL）',
    cancel_requested TINYINT       NOT NULL DEFAULT 0 COMMENT '协作式取消标志：1=已请求取消（handler 检查点生效后转取消终态）',
    retry_count      INT           NOT NULL DEFAULT 0 COMMENT '已重试次数（自动+手动共计数）',
    max_retries      INT           NOT NULL DEFAULT 3 COMMENT '最大自动重试次数（提交可指定；耗尽后失败为终态）',
    timeout_seconds  INT           NOT NULL DEFAULT 600 COMMENT '执行超时秒数（提交可指定；超时判失败）',
    executor_id      VARCHAR(64)   DEFAULT NULL COMMENT '认领执行器标识 host:port（多实例演进预留，CAS 认领写入口径）',
    submit_by        VARCHAR(50)   DEFAULT NULL COMMENT '提交人账号（业务方从登录态显式传入，审计用）',
    submit_source    VARCHAR(50)   DEFAULT NULL COMMENT '提交来源服务名（如 cloud-system，列表筛选用）',
    start_time       DATETIME      DEFAULT NULL COMMENT '开始执行时间（超时判定基准）',
    finish_time      DATETIME      DEFAULT NULL COMMENT '终态时间',
    create_time      DATETIME      DEFAULT NULL COMMENT '提交时间',
    update_time      DATETIME      DEFAULT NULL COMMENT '状态/进度更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_type_biz (task_type, biz_key),
    KEY idx_status_create (status, create_time),
    KEY idx_create_time (create_time)
) ENGINE = InnoDB COMMENT = '异步任务（通用任务中心）';
