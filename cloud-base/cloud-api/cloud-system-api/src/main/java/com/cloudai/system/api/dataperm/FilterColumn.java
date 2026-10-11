package com.cloudai.system.api.dataperm;

/**
 * 可过滤列声明单元（设计 D32，2026-10-11 谓词轮；扩展轮 D42/D45 增第 5 组件 dictKey）：
 * 注册表 register/registerRemote 行的第三段清单元素——与「可配列」（列级动作，VO 字段名 String）
 * 显式区分的双清单之一。
 *
 * <ul>
 *   <li>field：entity 字段名（camelCase，断言锚 + detail 取值键）</li>
 *   <li>column：裸 DB 列名（snake_case，XML 硬编码分支由各 mapper 自加别名前缀，如 l.leave_type）</li>
 *   <li>type：值类型（操作符候选矩阵与值输入器的类型驱动依据）</li>
 *   <li>label：中文列名（配置台下拉与摘要文案，沿 RowScopeEnum.label() 先例）</li>
 *   <li>dictKey：值域字典键（扩展轮 D45）——非空 = 摘要/narratives 值译字典标签 + 前端值输入器
 *       字典下拉（leaveType→system_leave_type、status→bpmn_approval_status）；null = 无字典原值显示</li>
 * </ul>
 *
 * 裁剪记档（D32 + 扩展轮 D42 修订）：不含 apply_user（账号维专属列，防与主体白名单语义重复配置）、
 * 派生列（sys_leave 投影 status）；reason 仍不含（长文本列无过滤价值）；title 随 PREFIX 兑现进清单
 * （D32 当年「无 LIKE 下字符串精确匹配密度不足」的排除前提失效）。
 */
public record FilterColumn(String field, String column, FilterColumnTypeEnum type, String label,
                           String dictKey) {
}
