package com.cloudai.system.convert;

import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.entity.SysDataPermColumn;
import com.cloudai.system.entity.SysDataPermLog;
import com.cloudai.system.entity.SysDataPermRule;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.vo.DataPermExplainVo;
import com.cloudai.system.vo.DataPermLogVo;
import com.cloudai.system.vo.DataPermRuleVo;
import com.cloudai.system.vo.MyScopeVo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据权限域转换（原生 setter 逐字段，禁三方拷贝工具）：规则分页行 / 配置回显 /
 * 留痕行 / 模拟解释 / 自查摘要 + customAccounts JSON 编解码（读路径降级空集）。
 */
@Slf4j
public final class DataPermConvert {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DataPermConvert() {
    }

    /** 规则分页行（subjectName/columns/customAccounts 由 Service 批量补齐后传入） */
    public static DataPermRuleVo toRuleVo(SysDataPermRule rule, String subjectName,
                                          List<String> customAccounts, List<DataPermRuleVo.ColumnRuleVo> columns) {
        DataPermRuleVo vo = new DataPermRuleVo();
        vo.setId(rule.getId());
        vo.setResource(rule.getResource());
        vo.setSubjectType(rule.getSubjectType());
        vo.setSubjectId(rule.getSubjectId());
        vo.setSubjectName(subjectName);
        vo.setRowScope(rule.getRowScope());
        vo.setCustomAccounts(customAccounts == null ? List.of() : customAccounts);
        vo.setColumns(columns == null ? List.of() : columns);
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

    /** 自查摘要：scopeLabel 五态判定（契约 §6.8）+ 列结论（无动作 null） */
    public static MyScopeVo toMyScopeVo(DataPermDecision decision) {
        MyScopeVo vo = new MyScopeVo();
        vo.setScopeLabel(resolveScopeLabel(decision));
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
        return vo;
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
     * scopeLabel 五态判定（主控 2026-10-10 终裁判定序：按命中档位语义分类，非按展开结果形状——
     * scopeLabel 是排查锚点，反映「配置了什么档」而非「展开后集合长什么样」；「仅自己」专属 SELF 档/无规则命中：
     * 部门档展开恰={自己}时若标「仅自己」会误导排查以为未配规则，且部门进人后档位标签漂移，
     * 部门档无论展开几人一律「指定范围」）：
     * 1 全部档 → 全部；2 空集 → 无（空范围）；
     * 3 命中规则含任一部门档（DEPT/DEPT_AND_CHILD）→ 指定范围（N 人）（N=accounts.size()，N=1 亦然——部门档语义优先于展开形状）；
     * 4 命中规则含任一 CUSTOM 档 → 集合含自己 → 自定义范围（含自己，共 N 人）；不含自己 → 指定范围（N 人）；
     * 5 兜底（部门/CUSTOM 均未命中，只剩 SELF 档或无规则）→ 仅自己。
     */
    private static String resolveScopeLabel(DataPermDecision decision) {
        if (decision.getDataScope().isAll()) {
            return "全部";
        }
        if (decision.getDataScope().isEmptyScope()) {
            return "无（空范围）";
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
