-- =====================================================================
-- MCP Server 用户级权限：ai_mcp_server_grant 授权表 + 初始授权一行（2026-10-30 轮）
-- 契约 docs/superpowers/contracts/2026-10-30-mcp-server-permission-api.md（§5 种子）
-- 设计 docs/superpowers/specs/2026-10-30-mcp-server-permission-design.md（§1.1 表结构 / §1.3 迁移口径）
-- ⚠️ 执行前前置断言（不满足→停下排查回报，不盲跑）：
--   1) cloud_ai: SHOW TABLES LIKE 'ai_mcp_server_grant'; -- 应 0 行（表未建，防重放）
--   2) cloud_ai: SELECT id FROM ai_mcp_server WHERE id = 1; -- 应 1 行（内置 SYSTEM 行在，
--      依赖 2026-10-30-mcp-server-mgmt.sql 已跑；初始授权行引用它）
-- 幂等口径沿既有脚本：表存在则跳过（重放 CREATE TABLE 报表已存在不视为错误）；
-- 种子行重放撞 uk_server_subject 不视为错误。无 cloud_system 段（零新权限标识、
-- 零菜单种子——两端点挂既有 ai:mcp:update，豁免消费既有 ai:mcp:list）。
-- 基线同步：cloud_ai.sql 已同步本建表语句与种子行——新环境直接跑基线，无需本增量。
-- 上线影响（默认拒绝收紧，用户拍板已接受）：无授权行的 server 上线后仅豁免者可用，
-- 业务用户需管理员在 MCP 管理页一次性手工授权（契约 §5.1 运营欠账）。
-- =====================================================================
USE cloud_ai;

-- MCP 服务用户授权（纯关系表，沿 ai_kb_grant 先例：物理删除、无 deleted 列、无 update
-- 审计列；保存 = 按 server_id 全删全插，同一事务由 service 收口）。
-- 默认拒绝 + 管理豁免：无授权行的 server 对运行用户不可用；持 ai:mcp:list 者豁免
-- （能管即能用）。运行面实时查库求值（每运行至多一次），授权保存下一运行即生效。
-- uk_server_subject(server_id, subject_type, subject_id) 两用：① 授权面板回显按
-- server_id 前缀取全量授权行；② 并发保存/插入查重兜底（IntegrityError）。
-- idx_subject(subject_type, subject_id) 命中运行面主路径「按主体反查可用 server id 集」
-- （三分支 OR + distinct）。不加 idx_server_id 单列——uk 最左前缀即 server_id，冗余。
-- 主体弱引用不建外键（主数据在 cloud_system 库，沿 ai_kb_grant 先例）。
CREATE TABLE ai_mcp_server_grant
(
    id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '授权ID（主键）',
    server_id    BIGINT       NOT NULL COMMENT 'MCP服务ID（ai_mcp_server.id，弱引用不建外键；内置 SYSTEM 行同样可授权）',
    subject_type TINYINT      NOT NULL COMMENT '主体类型：0=角色 1=部门 2=用户',
    subject_id   BIGINT       NOT NULL COMMENT '主体ID（sys_role.id / sys_dept.id / sys_user.id，主数据在 cloud_system 库）',
    create_by    VARCHAR(30)  NULL COMMENT '授权操作人账号',
    create_time  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间（DB 时钟口径，应用层不传时间）',
    PRIMARY KEY (id),
    UNIQUE KEY uk_server_subject (server_id, subject_type, subject_id),
    KEY idx_subject (subject_type, subject_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci
  COMMENT ='MCP服务用户授权（纯关系表：物理删除，随保存全删全插；默认拒绝+管理豁免，运行面实时求值）';

-- 初始授权：内置 SYSTEM server（id=1）授给内置管理员角色（sys_role id=1，cloud_system
-- 基线种子恒存在的保护角色）。取舍：管理员本就持 ai:mcp:list 豁免，此行近乎冗余——
-- 保留它的价值是授权状态在面板可见（避免「全库零授权」困惑），且豁免口径未来收紧时
-- 不回退管理员可用性。外部 server 不做种子（数量环境相关，全部走手工授权）。
INSERT INTO ai_mcp_server_grant (server_id, subject_type, subject_id) VALUES (1, 0, 1);

commit;
