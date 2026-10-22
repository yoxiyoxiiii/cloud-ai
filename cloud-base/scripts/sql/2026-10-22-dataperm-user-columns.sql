-- 列级数据权限前端动态列轮（契约 2026-10-22-dataperm-column-visibility-api §5）：
-- sys_user 增 mobile/email 两可空列（数据权限资源 user 的可配列）+ admin 示例值。
-- 零新索引：两列本轮无任何查询路径（列表无筛选/无查重/无关联，设计 §8 记档取舍）。
ALTER TABLE sys_user
    ADD COLUMN mobile VARCHAR(20) DEFAULT NULL COMMENT '手机号（可空=未填；数据权限资源 user 的可配列，可被列规则脱敏为 *** 或隐藏为 null）' AFTER nickname,
    ADD COLUMN email VARCHAR(64) DEFAULT NULL COMMENT '邮箱（可空=未填；数据权限资源 user 的可配列，可被列规则脱敏为 *** 或隐藏为 null）' AFTER mobile;

-- admin 示例值（冒烟/e2e 直观锚点；ASCII 值无 GBK 坑）
UPDATE sys_user SET mobile = '13800000001', email = 'admin@example.com' WHERE id = 1 AND deleted = 0;
