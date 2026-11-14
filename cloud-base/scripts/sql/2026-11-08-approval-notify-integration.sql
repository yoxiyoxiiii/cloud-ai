-- =====================================================================
-- 审批流任务通知接入站内信——模板种子（2026-11-08 审批通知轮）
-- 契约 docs/superpowers/contracts/2026-11-08-approval-notify-mq-api.md §2（模板与变量清单唯一权威）
-- 两段：①approval_remind（id=2）内容升级为任务到达五变量 ②approval_result（id=3）新增审批结果模板
-- 幂等可重放：UPDATE 带防御 WHERE（id+code+is_builtin+deleted，未命中=0 行无害）；
--   INSERT 用 NOT EXISTS（id=3 或 code 重复均拦）。
-- ⚠️ UPDATE 会覆盖内置模板的本地手工微调（种子纪律：内置模板内容以契约 §2 为准，调整请改模板管理页并知会契约）。
-- 基线同步：cloud_system.sql 内置模板段已同步同值（新环境免跑本脚本）。
-- 落库脚本 cloud-base/scripts/sql/2026-11-08-approval-notify-apply.py（拆分时自 AI 工程迁入）（pymysql utf8mb4）。
-- =====================================================================
USE cloud_system;

-- ---------- ① approval_remind 内容升级（任务到达：收件人=节点 assignee，变量集扩为五个） ----------
UPDATE sys_notify_template
   SET content_template = '您好 ${nickname}，${applyUserName} 提交的「${title}」（${businessTypeName}）有待办任务待您审批（节点：${taskName}），请及时处理。',
       variables_desc = 'nickname=收件人昵称；applyUserName=申请人昵称（账号已删回退账号）；title=单据标题；businessTypeName=业务类型名；taskName=待办节点名',
       update_by = 'admin',
       update_time = NOW()
 WHERE id = 2
   AND code = 'approval_remind'
   AND is_builtin = 1
   AND deleted = 0;

-- ---------- ② approval_result 新增（审批结果：收件人=申请人，resultText 三值映射） ----------
INSERT INTO sys_notify_template (id, code, name, title_template, content_template, type, level,
                                 variables_desc, is_builtin, create_by, create_time, update_by, update_time)
SELECT 3, 'approval_result', '审批结果通知', '审批结果通知',
       '您好 ${nickname}，您提交的「${title}」（${businessTypeName}）已${resultText}。',
       2, 0, 'nickname=收件人昵称；title=单据标题；businessTypeName=业务类型名；resultText=审批结果（已通过/已拒绝/已撤销）',
       1, 'admin', NOW(), 'admin', NOW()
  FROM DUAL
 WHERE NOT EXISTS (SELECT 1 FROM sys_notify_template WHERE id = 3 OR code = 'approval_result');

commit;
