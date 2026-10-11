package com.cloudai.system.convert;

import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.FilterColumn;
import com.cloudai.system.api.dataperm.PredicateOperatorEnum;
import com.cloudai.system.dto.PredicateItem;
import com.cloudai.system.entity.SysDataPermColumn;
import com.cloudai.system.entity.SysDataPermLog;
import com.cloudai.system.entity.SysDataPermRule;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermResources;
import com.cloudai.system.vo.DataPermExplainVo;
import com.cloudai.system.vo.DataPermLogVo;
import com.cloudai.system.vo.DataPermRuleVo;
import com.cloudai.system.vo.FilterColumnVo;
import com.cloudai.system.vo.MyScopeVo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 数据权限域转换（原生 setter 逐字段，禁三方拷贝工具）：规则分页行 / 配置回显 /
 * 留痕行 / 模拟解释 / 自查摘要 + customAccounts/predicates JSON 编解码
 * （customAccounts 读路径降级空集；predicates 解析失败返回 null 由求值器按 D39 整规则不授权，
 * 回显场景调用方自行空列表兜底——两口径刻意分流，见 {@link #parsePredicates}）。
 */
@Slf4j
public final class DataPermConvert {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DataPermConvert() {
    }

    /**
     * 规则分页行（subjectName/columns/customAccounts 由 Service 批量补齐后传入；predicates/摘要自实体解析）。
     * 操作符扩展轮 D45：原值形态（无字典 resolver）——Service 侧经 {@link #toRuleVo(SysDataPermRule, String,
     * List, List, BiFunction)} 五参 overload 注入 TranslationCacheService 构造的 resolver 产译文摘要。
     */
    public static DataPermRuleVo toRuleVo(SysDataPermRule rule, String subjectName,
                                          List<String> customAccounts, List<DataPermRuleVo.ColumnRuleVo> columns) {
        return toRuleVo(rule, subjectName, customAccounts, columns, null);
    }

    /** 规则分页行（操作符扩展轮 D45 五参形态）：dictResolver 非空时 predicateSummary 字典列值译标签 */
    public static DataPermRuleVo toRuleVo(SysDataPermRule rule, String subjectName,
                                          List<String> customAccounts, List<DataPermRuleVo.ColumnRuleVo> columns,
                                          BiFunction<String, List<String>, Map<String, String>> dictResolver) {
        DataPermRuleVo vo = new DataPermRuleVo();
        vo.setId(rule.getId());
        vo.setResource(rule.getResource());
        vo.setSubjectType(rule.getSubjectType());
        vo.setSubjectId(rule.getSubjectId());
        vo.setSubjectName(subjectName);
        vo.setRowScope(rule.getRowScope());
        vo.setCustomAccounts(customAccounts == null ? List.of() : customAccounts);
        vo.setColumns(columns == null ? List.of() : columns);
        fillPredicates(vo, rule.getResource(), parsePredicates(rule.getPredicates()), dictResolver);
        vo.setUpdateBy(rule.getUpdateBy());
        vo.setUpdateTime(rule.getUpdateTime());
        return vo;
    }

    /** 留痕行（实体与 VO 字段一一对应） */
    public static DataPermLogVo toLogVo(SysDataPermLog logRow) {
        DataPermLogVo vo = new DataPermLogVo();
        vo.setId(logRow.getId());
        vo.setAccount(logRow.getAccount());
        vo.setResource(logRow.getResource());
        vo.setOperation(logRow.getOperation());
        vo.setRuleIds(logRow.getRuleIds());
        vo.setRuleDigest(logRow.getRuleDigest());
        vo.setScopeSummary(logRow.getScopeSummary());
        vo.setColumnSummary(logRow.getColumnSummary());
        vo.setBusinessKey(logRow.getBusinessKey());
        vo.setCreateTime(logRow.getCreateTime());
        return vo;
    }

    /** 模拟解释：求值决策 → 契约 §6.7 视图（rowAll/计数/含自己判定 + 列决策 hidden 前 masked 后字典序） */
    public static DataPermExplainVo toExplainVo(DataPermDecision decision) {
        DataPermExplainVo vo = new DataPermExplainVo();
        vo.setAccount(decision.getAccount());
        vo.setResource(decision.getResource());
        decision.getHitRules().forEach(hit -> vo.getHitRules().add(toHitRuleVo(hit)));
        boolean rowAll = decision.getDataScope().isAll();
        vo.setRowAll(rowAll);
        Set<String> accounts = rowAll ? null : decision.getDataScope().getAccounts();
        boolean empty = !rowAll && decision.getDataScope().isEmptyScope();
        vo.setRowAccountCount(rowAll || empty ? 0 : accounts.size());
        vo.setSelfIncluded(rowAll || (!empty && accounts.contains(decision.getAccount())));
        vo.getColumns().addAll(toColumnDecisionVos(decision.getColumnScope()));
        vo.getNarratives().addAll(decision.getNarratives());
        return vo;
    }

    /** 自查摘要：scopeLabel 五态判定（契约 §6.8）+ 列结论（无动作 null）。原值形态（D45 两参 overload 委托） */
    public static MyScopeVo toMyScopeVo(DataPermDecision decision) {
        return toMyScopeVo(decision, null);
    }

    /** 自查摘要（操作符扩展轮 D45 两参形态）：dictResolver 非空时第六态条件摘要字典列值译标签 */
    public static MyScopeVo toMyScopeVo(DataPermDecision decision,
            BiFunction<String, List<String>, Map<String, String>> dictResolver) {
        MyScopeVo vo = new MyScopeVo();
        vo.setScopeLabel(resolveScopeLabel(decision, dictResolver));
        vo.setColumnSummary(decision.getColumnScope().isEmpty() ? null
                : decision.getColumnScope().getSummary());
        return vo;
    }

    /** customAccounts JSON 数组串 → 账号列表；坏数据降级空列表（读路径不炸） */
    public static List<String> parseCustomAccounts(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> list = OBJECT_MAPPER.readValue(json, new TypeReference<List<String>>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.error("customAccounts 解析失败（降级空列表）：{}", json, e);
            return List.of();
        }
    }

    /** customAccounts 账号列表 → JSON 数组串（DDL VARCHAR(1000) 承载，超长由 DB 报错兜底） */
    public static String toCustomAccountsJson(List<String> accounts) {
        try {
            return OBJECT_MAPPER.writeValueAsString(accounts == null ? List.of() : accounts);
        } catch (Exception e) {
            throw new IllegalStateException("customAccounts 序列化失败", e);
        }
    }

    /**
     * predicates JSON 数组串 → 条件三元组列表（谓词轮 D30/D39）：null/空白 → 空列表（无条件）；
     * <b>解析失败返回 null</b>——与 {@link #parseCustomAccounts} 的空列表降级刻意分流：
     * 空列表=无条件（授权语义放宽位），坏数据降级空列表即 fail-open；求值器以 null 为
     * 「该规则条件恒不满足、整规则不授权」信号（D39 fail-closed）。回显场景（rule/config VO）
     * 调用方对 null 空列表兜底——仅展示无授权语义。
     */
    public static List<PredicateItem> parsePredicates(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<PredicateItem> list = OBJECT_MAPPER.readValue(json, new TypeReference<List<PredicateItem>>() {
            });
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.error("predicates 解析失败（求值侧整规则不授权 D39 / 回显侧空列表兜底）：{}", json, e);
            return null;
        }
    }

    /** 条件三元组列表 → predicates JSON（落库承载；≤2000 上限由配置校验链 3037 先行把守） */
    public static String toPredicatesJson(List<PredicateItem> predicates) {
        try {
            return OBJECT_MAPPER.writeValueAsString(predicates == null ? List.of() : predicates);
        } catch (Exception e) {
            throw new IllegalStateException("predicates 序列化失败", e);
        }
    }

    /**
     * 条件中文摘要（契约 §1.6，config/rule/my-scope 共用）：每谓词 {@code {列label}{操作符中文}{值}}
     * （值原样配置值，IN 顿号连接、BETWEEN v0~v1），多谓词分号连接——如 {@code 审批单号介于1~100;状态等于0}。
     * 无条件/全部不可叙述 → null；越界条件（字段不在清单）跳过该谓词（D39 一致性检查告警对象）。
     * 操作符扩展轮 D45：原值形态——两参 overload 委托三参（null resolver）。
     */
    public static String toPredicateSummary(String resource, List<PredicateItem> predicates) {
        return toPredicateSummary(resource, predicates, null);
    }

    /**
     * 条件中文摘要（操作符扩展轮 D45 三参形态）：dictResolver 非空且列声明 dictKey 在场时
     * 值文本逐值译字典标签（译文缺失回退原值）——如 {@code 请假类型等于1} → {@code 请假类型等于事假}；
     * digest（机器面向）与 wire 回显不经本路径（原值，设计 D45 不翻译面）。
     */
    public static String toPredicateSummary(String resource, List<PredicateItem> predicates,
            BiFunction<String, List<String>, Map<String, String>> dictResolver) {
        if (predicates == null || predicates.isEmpty()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (PredicateItem item : predicates) {
            String part = predicateSummaryPart(resource, item, dictResolver);
            if (part != null) {
                parts.add(part);
            }
        }
        return parts.isEmpty() ? null : String.join(";", parts);
    }

    private static String predicateSummaryPart(String resource, PredicateItem item,
            BiFunction<String, List<String>, Map<String, String>> dictResolver) {
        FilterColumn declared = DataPermResources.findFilterColumn(resource, item.getField());
        PredicateOperatorEnum operator;
        try {
            operator = PredicateOperatorEnum.of(item.getOperator());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (declared == null || item.getValues() == null || item.getValues().isEmpty()
                || (operator == PredicateOperatorEnum.BETWEEN && item.getValues().size() < 2)) {
            return null;
        }
        List<String> values = translateValues(declared, item.getValues(), dictResolver);
        String valueText = switch (operator) {
            case IN -> String.join("、", values);
            case BETWEEN -> values.get(0) + "~" + values.get(1);
            default -> values.get(0);
        };
        return declared.label() + operator.getLabel() + valueText;
    }

    /**
     * 字典值文本（操作符扩展轮 D45，summary/narratives 共用单源）：列声明 dictKey 在场且 resolver
     * 可用 → 逐值 {@code resolver.apply(dictKey, values).getOrDefault(v, v)}（译文缺失回退原值）；
     * resolver 异常回退原值列表——显示层 fail-open（与权限域 fail-open 严格区分记档：标签无害）。
     */
    public static List<String> translateValues(FilterColumn declared, List<String> values,
            BiFunction<String, List<String>, Map<String, String>> dictResolver) {
        if (dictResolver == null || declared.dictKey() == null) {
            return values;
        }
        try {
            Map<String, String> labels = dictResolver.apply(declared.dictKey(), values);
            if (labels == null) {
                return values;
            }
            return values.stream().map(v -> labels.getOrDefault(v, v)).toList();
        } catch (Exception e) {
            log.error("条件值字典翻译失败（显示层 fail-open 回退原值）：dictKey={}", declared.dictKey(), e);
            return values;
        }
    }

    /** 可过滤列声明 → FilterColumnVo（资源注册表端点出参，契约 §5.2；+dictKey，操作符扩展轮 D45） */
    public static FilterColumnVo toFilterColumnVo(FilterColumn column) {
        FilterColumnVo vo = new FilterColumnVo();
        vo.setField(column.field());
        vo.setColumn(column.column());
        vo.setType(column.type().name());
        vo.setLabel(column.label());
        vo.setDictKey(column.dictKey());
        return vo;
    }

    /** 列规则实体列表 → ColumnRuleVo 列表（id 升序入参，保序转换） */
    public static List<DataPermRuleVo.ColumnRuleVo> toColumnRuleVos(List<SysDataPermColumn> columns) {
        List<DataPermRuleVo.ColumnRuleVo> result = new ArrayList<>();
        if (columns == null) {
            return result;
        }
        for (SysDataPermColumn col : columns) {
            DataPermRuleVo.ColumnRuleVo item = new DataPermRuleVo.ColumnRuleVo();
            item.setColumnKey(col.getColumnKey());
            item.setAction(col.getAction());
            result.add(item);
        }
        return result;
    }

    private static DataPermExplainVo.HitRuleVo toHitRuleVo(DataPermDecision.HitRule hit) {
        DataPermExplainVo.HitRuleVo vo = new DataPermExplainVo.HitRuleVo();
        vo.setRuleId(hit.getRuleId());
        vo.setSubjectType(hit.getSubjectType());
        vo.setSubjectName(hit.getSubjectName());
        vo.setRowScope(hit.getRowScope());
        vo.setCustomCount(hit.getCustomCount());
        vo.setExpandedCount(hit.getExpandedCount());
        vo.getPredicates().addAll(hit.getPredicates() == null ? List.of() : hit.getPredicates());
        return vo;
    }

    /** 规则行条件回显：解析失败 null → 空列表兜底（仅展示语义）+ 中文摘要（无条件 null；D45 三参带译文） */
    private static void fillPredicates(DataPermRuleVo vo, String resource, List<PredicateItem> predicates,
            BiFunction<String, List<String>, Map<String, String>> dictResolver) {
        vo.setPredicates(predicates == null ? List.of() : predicates);
        vo.setPredicateSummary(toPredicateSummary(resource, vo.getPredicates(), dictResolver));
    }

    private static List<DataPermExplainVo.ColumnDecisionVo> toColumnDecisionVos(ColumnScope columnScope) {
        List<DataPermExplainVo.ColumnDecisionVo> result = new ArrayList<>();
        columnScope.getHiddenColumns().stream().sorted().forEach(key -> {
            DataPermExplainVo.ColumnDecisionVo item = new DataPermExplainVo.ColumnDecisionVo();
            item.setColumnKey(key);
            item.setAction(SysDataPermColumn.ActionEnum.HIDDEN.getCode());
            result.add(item);
        });
        columnScope.getMaskedColumns().stream().sorted().forEach(key -> {
            DataPermExplainVo.ColumnDecisionVo item = new DataPermExplainVo.ColumnDecisionVo();
            item.setColumnKey(key);
            item.setAction(SysDataPermColumn.ActionEnum.MASKED.getCode());
            result.add(item);
        });
        return result;
    }

    /**
     * scopeLabel 判定序 v2（契约 §6，2026-10-11 谓词轮语义变更——无条件五态判定序与文案逐字不变）：
     * 1 行范围豁免（任一 ALL 档<b>无条件</b>规则命中）→ 全部；
     * 2 行范围空集（无任何可匹配组，判据 rowFilter.isEmptyScope——ALL+条件用户 dataScope 空但行集非空）→ 无（空范围）；
     * 3 任一命中规则含条件 → 条件范围（{条件中文摘要}）——全部命中条件规则的谓词摘要分号连接（§1.6）；
     * 4 命中任一部门档（DEPT/DEPT_AND_CHILD）→ 指定范围（N 人）（N=1 亦然——部门档语义优先于展开形状）；
     * 5 命中任一 CUSTOM 档 → 含自己 → 自定义范围（含自己，共 N 人）；不含自己 → 指定范围（N 人）；
     * 6 兜底（只剩 SELF 档或无规则）→ 仅自己。
     * 判据 1/2 以 rowFilter 为准（行集判定唯一权威 D31）；rowFilter 缺席（手工构造 Decision 的既有
     * 单测路径）回退 dataScope 同义判定——无条件场景两判据等价，五态文案逐字不变（零 diff 红线）。
     */
    private static String resolveScopeLabel(DataPermDecision decision,
            BiFunction<String, List<String>, Map<String, String>> dictResolver) {
        RowFilterView view = RowFilterView.of(decision);
        if (view.rowAll()) {
            return "全部";
        }
        if (view.empty()) {
            return "无（空范围）";
        }
        if (hasConditionalRule(decision)) {
            return "条件范围（" + conditionalScopeText(decision, dictResolver) + "）";
        }
        Set<String> accounts = decision.getDataScope().getAccounts();
        String self = decision.getAccount();
        if (hasDeptTierRule(decision)) {
            return "指定范围（" + accounts.size() + " 人）";
        }
        if (hasCustomTierRule(decision)) {
            if (self != null && accounts.contains(self)) {
                return "自定义范围（含自己，共 " + accounts.size() + " 人）";
            }
            return "指定范围（" + accounts.size() + " 人）";
        }
        return "仅自己";
    }

    /** 命中规则是否含条件（predicates 非空）——条件态判据（§6 第 3 位） */
    private static boolean hasConditionalRule(DataPermDecision decision) {
        return decision.getHitRules().stream()
                .anyMatch(hit -> hit.getPredicates() != null && !hit.getPredicates().isEmpty());
    }

    /** 条件态摘要：全部命中条件规则的谓词摘要分号连接（§1.6；全不可叙述时空串兜底；D45 三参带译文） */
    private static String conditionalScopeText(DataPermDecision decision,
            BiFunction<String, List<String>, Map<String, String>> dictResolver) {
        List<String> parts = new ArrayList<>();
        for (DataPermDecision.HitRule hit : decision.getHitRules()) {
            String summary = toPredicateSummary(decision.getResource(), hit.getPredicates(), dictResolver);
            if (summary != null) {
                parts.add(summary);
            }
        }
        return String.join(";", parts);
    }

    /**
     * rowFilter/dataScope 双判据视图（label v2 内部工具）：rowFilter 在场以其为准（D31 行集唯一权威）；
     * 缺席（既有单测手工构造 Decision）回退 dataScope——无条件场景等价，防御 NPE 而非语义分叉。
     */
    private record RowFilterView(boolean rowAll, boolean empty) {

        static RowFilterView of(DataPermDecision decision) {
            if (decision.getRowFilter() != null) {
                return new RowFilterView(decision.getRowFilter().isRowAll(),
                        decision.getRowFilter().isEmptyScope());
            }
            return new RowFilterView(decision.getDataScope().isAll(),
                    decision.getDataScope().isEmptyScope());
        }
    }

    /** 命中规则是否含部门档（DEPT/DEPT_AND_CHILD）——枚举常量引用，禁魔法数（编码规范 8） */
    private static boolean hasDeptTierRule(DataPermDecision decision) {
        Integer dept = SysDataPermRule.RowScopeEnum.DEPT.getCode();
        Integer deptAndChild = SysDataPermRule.RowScopeEnum.DEPT_AND_CHILD.getCode();
        return decision.getHitRules().stream()
                .anyMatch(hit -> dept.equals(hit.getRowScope()) || deptAndChild.equals(hit.getRowScope()));
    }

    /** 命中规则是否含 CUSTOM 档——对称 {@link #hasDeptTierRule}，枚举常量引用，禁魔法数（编码规范 8） */
    private static boolean hasCustomTierRule(DataPermDecision decision) {
        Integer custom = SysDataPermRule.RowScopeEnum.CUSTOM.getCode();
        return decision.getHitRules().stream()
                .anyMatch(hit -> custom.equals(hit.getRowScope()));
    }
}
