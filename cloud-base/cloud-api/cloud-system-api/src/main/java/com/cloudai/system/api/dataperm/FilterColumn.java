package com.cloudai.system.api.dataperm;

/**
 * 可过滤列声明单元（设计 D32，2026-10-11 谓词轮）：注册表 register/registerRemote 行的第三段
 * 清单元素——与「可配列」（列级动作，VO 字段名 String）显式区分的双清单之一。
 *
 * <ul>
 *   <li>field：entity 字段名（camelCase，断言锚 + detail 取值键）</li>
 *   <li>column：裸 DB 列名（snake_case，XML 硬编码分支由各 mapper 自加别名前缀，如 l.leave_type）</li>
 *   <li>type：值类型（操作符候选矩阵与值输入器的类型驱动依据）</li>
 *   <li>label：中文列名（配置台下拉与摘要文案，沿 RowScopeEnum.label() 先例）</li>
 * </ul>
 *
 * 刻意裁剪记档（D32）：不含 apply_user（账号维专属列，防与主体白名单语义重复配置）、
 * 派生列（sys_leave 投影 status）、title/reason（无 LIKE 下字符串精确匹配密度不足）。
 */
public record FilterColumn(String field, String column, FilterColumnTypeEnum type, String label) {
}
