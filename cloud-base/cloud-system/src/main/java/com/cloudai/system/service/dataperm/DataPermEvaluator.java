package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.entity.SysDataPermColumn;
import com.cloudai.system.entity.SysDataPermLog;
import com.cloudai.system.entity.SysDataPermRule;
import com.cloudai.system.entity.SysDept;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.DataPermColumnMapper;
import com.cloudai.system.mapper.DataPermLogMapper;
import com.cloudai.system.mapper.DataPermRuleMapper;
import com.cloudai.system.mapper.SysDeptMapper;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据权限求值器（设计 §5.2 / D3-D7 / D10）：显式编程式，cloud-system 内聚（D6）。
 * 实时求值无缓存（D10）：规则两查（角色 IN + 用户直绑）+ 用户 deptId 一查 + 部门全量一查（部门档时）
 * + 部门成员一查（部门档时）——内部系统量级下 4-5 次索引小查询可承受。
 * 多规则并集宽松者胜（D3）；无规则默认 SELF + 列全可见（fail-safe）；admin 靠种子规则得 ALL，代码无特例。
 * 留痕（D7）：真实查询（list/detail）同步落 sys_data_perm_log 一条，insert 异常 catch log.error 不抛
 * （可观察组件自身不得成为读路径故障源）；explain 模拟与 my-scope 自查不留痕。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPermEvaluator {

    /** 规则主体不存在或已停用（契约 §8；explain 场景=目标用户无效） */
    private static final int ERR_SUBJECT_INVALID = 3032;

    /** 留痕摘要截断上限（DDL VARCHAR(500)） */
    private static final int DIGEST_MAX = 500;

    private final DataPermRuleMapper ruleMapper;
    private final DataPermColumnMapper columnMapper;
    private final DataPermLogMapper logMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;
    private final SysDeptMapper deptMapper;
    private final ObjectMapper objectMapper;

    /** 真实决策（当前登录人，SecurityUtils.currentUser——设计 §5.4 additive）：恒留痕（D7） */
    public DataPermDecision evaluate(String resource, String operation, String businessKey) {
        LoginUser current = SecurityUtils.currentUser();
        String account = current == null ? null : current.getAccount();
        Long userId = current == null ? null : current.getUserId();
        DataPermDecision decision = doEvaluate(account, userId, resource, operation);
        persistLog(decision, businessKey);
        return decision;
    }

    /** 自查求值（my-scope，契约 §3.9）：当前登录人但不留痕——非真实业务决策，与 explain 同口径（D7） */
    public DataPermDecision evaluateForSelf(String resource) {
        LoginUser current = SecurityUtils.currentUser();
        String account = current == null ? null : current.getAccount();
        Long userId = current == null ? null : current.getUserId();
        return doEvaluate(account, userId, resource, null);
    }

    /** 模拟解释（任意用户）：复用同一 doEvaluate，不留痕（模拟非真实决策，混入会污染决策史——D7） */
    public DataPermDecision explain(String account, String resource) {
        if (account == null || account.isBlank()) {
            throw new BusinessException("账号不能为空");
        }
        DataPermResources.assertResource(resource);
        SysUser target = userMapper.findByAccount(account);
        if (target == null || !Integer.valueOf(SysUser.StatusEnum.NORMAL.getCode()).equals(target.getStatus())) {
            throw new BusinessException(ERR_SUBJECT_INVALID, "规则主体不存在或已停用: " + account);
        }
        return doEvaluate(account, target.getId(), resource, null);
    }

    /** deny 补痕（设计 D7/D13）：详情被行级拒绝后补记安全审计事件（谁在何时试图越权访问哪张单） */
    public void logDeny(String resource, String businessKey) {
        DataPermResources.assertResource(resource);
        String account = SecurityUtils.currentAccount();
        SysDataPermLog denyRow = new SysDataPermLog();
        denyRow.setAccount(account);
        denyRow.setResource(resource);
        denyRow.setOperation(DataPermOperation.DENY);
        // deny 行是拒绝事件标记而非范围结论：scopeSummary 固定 'deny'（NOT NULL 约束），规则摘要留空
        denyRow.setScopeSummary("deny");
        denyRow.setBusinessKey(businessKey);
        denyRow.setCreateTime(LocalDateTime.now());
        try {
            logMapper.save(denyRow);
        } catch (Exception e) {
            log.error("数据权限 deny 留痕失败（不阻断）：resource={}, businessKey={}", resource, businessKey, e);
        }
        log.warn("数据权限拒绝访问（IDOR 收口，3026）：account={}, resource={}, businessKey={}",
                account, resource, businessKey);
    }

    private DataPermDecision doEvaluate(String account, Long userId, String resource, String operation) {
        DataPermResources.assertResource(resource);
        List<Long> roleIds = userId == null ? List.of() : roleMapper.listEnabledRoleIdsByUserId(userId);
        DataPermDecision decision = new DataPermDecision();
        decision.setResource(resource);
        decision.setAccount(account);
        decision.setOperation(operation);
        List<SysDataPermRule> rules = collectRules(resource, userId, roleIds);
        resolveRowScope(account, userId, rules, decision);
        resolveColumns(resource, userId, roleIds, decision);
        buildNarratives(decision);
        return decision;
    }

    /** 命中规则收集（D3）：全部启用角色规则 ∪ 用户直绑规则；roleIds 空跳过角色段查询防 IN () */
    private List<SysDataPermRule> collectRules(String resource, Long userId, List<Long> roleIds) {
        List<SysDataPermRule> rules = new ArrayList<>();
        if (!roleIds.isEmpty()) {
            rules.addAll(ruleMapper.listByRoleIds(resource, roleIds));
        }
        if (userId != null) {
            rules.addAll(ruleMapper.listByUserId(resource, userId));
        }
        return rules;
    }

    /** 行级收敛（D3）：任一 ALL → all=true 短路（其余规则不再展开）；否则逐档展开并集 */
    private void resolveRowScope(String account, Long userId, List<SysDataPermRule> rules,
                                 DataPermDecision decision) {
        List<DataPermDecision.HitRule> hits = rules.stream().map(r -> toHitRule(r, account)).toList();
        decision.setHitRules(new ArrayList<>(hits));
        if (rules.isEmpty()) {
            // 无规则默认 SELF（fail-safe，D3）：普通用户「我的请假」行为与旧版一致；匿名防御=空集
            decision.setDataScope(account == null ? DataScope.of(Set.of()) : DataScope.of(Set.of(account)));
            return;
        }
        boolean all = rules.stream().anyMatch(
                r -> Integer.valueOf(SysDataPermRule.RowScopeEnum.ALL.getCode()).equals(r.getRowScope()));
        if (all) {
            decision.setDataScope(DataScope.all());
            return;
        }
        Set<String> accounts = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            Set<String> expanded = expandRule(account, userId, rules.get(i));
            accounts.addAll(expanded);
            decision.getHitRules().get(i).setExpandedCount(expanded.size());
        }
        decision.setDataScope(DataScope.of(accounts));
    }

    /** 单规则按档展开为账号集合：SELF→{自己}；DEPT/DEPT_AND_CHILD→部门成员；CUSTOM→JSON 解析 */
    private Set<String> expandRule(String account, Long userId, SysDataPermRule rule) {
        Integer scope = rule.getRowScope();
        if (Integer.valueOf(SysDataPermRule.RowScopeEnum.SELF.getCode()).equals(scope)) {
            return account == null ? Set.of() : Set.of(account);
        }
        if (Integer.valueOf(SysDataPermRule.RowScopeEnum.DEPT.getCode()).equals(scope)
                || Integer.valueOf(SysDataPermRule.RowScopeEnum.DEPT_AND_CHILD.getCode()).equals(scope)) {
            return expandDeptScope(userId,
                    Integer.valueOf(SysDataPermRule.RowScopeEnum.DEPT_AND_CHILD.getCode()).equals(scope));
        }
        if (Integer.valueOf(SysDataPermRule.RowScopeEnum.CUSTOM.getCode()).equals(scope)) {
            return parseCustomAccounts(rule.getCustomAccounts());
        }
        return Set.of();
    }

    /** 部门档展开（D1/D4）：求值器内终结部门语义——子树 deptId 集合 → 启用账号集合 */
    private Set<String> expandDeptScope(Long userId, boolean includeChildren) {
        SysUser self = userId == null ? null : userMapper.findById(userId);
        if (self == null || self.getDeptId() == null) {
            // 用户未挂部门：该规则展开为空集（D3），不炸读路径
            return Set.of();
        }
        List<Long> deptIds = new ArrayList<>();
        deptIds.add(self.getDeptId());
        if (includeChildren) {
            deptIds.addAll(collectChildDeptIds(self.getDeptId()));
        }
        return new HashSet<>(userMapper.listEnabledAccountsByDeptIds(deptIds));
    }

    /** 子树收集（D1）：全量 listAll 内存递归——部门量级十百级零压力，避免 path 列维护负担 */
    private List<Long> collectChildDeptIds(Long rootDeptId) {
        Map<Long, List<Long>> children = deptMapper.listAll().stream()
                .collect(Collectors.groupingBy(SysDept::getParentId,
                        Collectors.mapping(SysDept::getId, Collectors.toList())));
        List<Long> result = new ArrayList<>();
        Deque<Long> queue = new ArrayDeque<>();
        queue.add(rootDeptId);
        while (!queue.isEmpty()) {
            for (Long child : children.getOrDefault(queue.poll(), List.of())) {
                if (!result.contains(child)) {
                    result.add(child);
                    queue.add(child);
                }
            }
        }
        return result;
    }

    /** CUSTOM 解析（D3）：JSON 数组串 → 账号集合；解析异常 log.error + 空集降级（防坏配置炸读路径） */
    private Set<String> parseCustomAccounts(String json) {
        if (json == null || json.isBlank()) {
            return Set.of();
        }
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
            return new LinkedHashSet<>(list);
        } catch (Exception e) {
            log.error("自定义账号集合解析失败（降级空集）：{}", json, e);
            return Set.of();
        }
    }

    /** 列级收敛（D3）：角色 ∪ 用户列规则，同列冲突取最宽松（可视 > 脱敏(1) > 隐藏(0)，数值大者胜） */
    private void resolveColumns(String resource, Long userId, List<Long> roleIds, DataPermDecision decision) {
        List<SysDataPermColumn> columns = new ArrayList<>();
        if (!roleIds.isEmpty()) {
            columns.addAll(columnMapper.listByRoleIds(resource, roleIds));
        }
        if (userId != null) {
            columns.addAll(columnMapper.listByUserId(resource, userId));
        }
        Map<String, Integer> merged = new HashMap<>();
        for (SysDataPermColumn col : columns) {
            merged.merge(col.getColumnKey(), col.getAction(), Math::max);
        }
        Set<String> hidden = merged.entrySet().stream()
                .filter(e -> Integer.valueOf(SysDataPermColumn.ActionEnum.HIDDEN.getCode()).equals(e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        Set<String> masked = merged.entrySet().stream()
                .filter(e -> Integer.valueOf(SysDataPermColumn.ActionEnum.MASKED.getCode()).equals(e.getValue()))
                .map(Map.Entry::getKey).collect(Collectors.toSet());
        decision.setColumnScope(ColumnScope.of(hidden, masked));
    }

    private DataPermDecision.HitRule toHitRule(SysDataPermRule rule, String account) {
        DataPermDecision.HitRule hit = new DataPermDecision.HitRule();
        hit.setRuleId(rule.getId());
        hit.setSubjectId(rule.getSubjectId());
        hit.setSubjectType(rule.getSubjectType());
        hit.setSubjectName(resolveSubjectName(rule, account));
        hit.setRowScope(rule.getRowScope());
        if (Integer.valueOf(SysDataPermRule.RowScopeEnum.CUSTOM.getCode()).equals(rule.getRowScope())) {
            hit.setCustomCount(parseCustomAccounts(rule.getCustomAccounts()).size());
        }
        return hit;
    }

    /** 主体名解析：角色规则查角色名（已删降级）；用户直绑规则=求值对象本人账号 */
    private String resolveSubjectName(SysDataPermRule rule, String account) {
        if (Integer.valueOf(SysDataPermRule.SubjectTypeEnum.ROLE.getCode()).equals(rule.getSubjectType())) {
            SysRole role = roleMapper.findById(rule.getSubjectId());
            return role == null ? "(已删除)" : role.getName();
        }
        return account;
    }

    /** narratives（全中文可解释）：每命中规则一句 + 收敛结论一句 + 列决策一句 */
    private void buildNarratives(DataPermDecision decision) {
        List<String> narratives = decision.getNarratives();
        decision.getHitRules().forEach(hit -> narratives.add(ruleNarrative(hit)));
        narratives.add(scopeNarrative(decision));
        if (!decision.getColumnScope().isEmpty()) {
            narratives.add(columnNarrative(decision.getColumnScope()));
        }
    }

    private String ruleNarrative(DataPermDecision.HitRule hit) {
        String kind = Integer.valueOf(SysDataPermRule.SubjectTypeEnum.ROLE.getCode()).equals(hit.getSubjectType())
                ? "角色" : "用户";
        StringBuilder sb = new StringBuilder("命中").append(kind).append("[").append(hit.getSubjectName()).append("]：");
        SysDataPermRule.RowScopeEnum scope = SysDataPermRule.RowScopeEnum.of(hit.getRowScope());
        sb.append(scope.label());
        if (hit.getExpandedCount() > 0) {
            sb.append("，展开 ").append(hit.getExpandedCount()).append(" 人");
        }
        return sb.toString();
    }

    private String scopeNarrative(DataPermDecision decision) {
        if (decision.getDataScope().isAll()) {
            return "行范围收敛：全部（行过滤豁免）";
        }
        if (decision.getHitRules().isEmpty()) {
            return "无规则命中，默认仅自己";
        }
        if (decision.getDataScope().isEmptyScope()) {
            return "行范围收敛：空集（无可见数据）";
        }
        return "行范围收敛：并集共 " + decision.getDataScope().getAccounts().size() + " 个账号";
    }

    private String columnNarrative(ColumnScope columnScope) {
        return "列决策：" + columnScope.getSummary();
    }

    /** 留痕落库（D7）：真实决策要素快照；insert 异常 catch log.error 不抛 */
    private void persistLog(DataPermDecision decision, String businessKey) {
        try {
            SysDataPermLog logRow = new SysDataPermLog();
            logRow.setAccount(decision.getAccount());
            logRow.setResource(decision.getResource());
            logRow.setOperation(decision.getOperation());
            logRow.setRuleIds(decision.getHitRules().isEmpty() ? null
                    : decision.getHitRules().stream().map(h -> String.valueOf(h.getRuleId()))
                            .collect(Collectors.joining(",")));
            logRow.setRuleDigest(digestRules(decision));
            logRow.setScopeSummary(summarizeScope(decision));
            logRow.setColumnSummary(decision.getColumnScope().isEmpty() ? null
                    : decision.getColumnScope().getSummary());
            logRow.setBusinessKey(businessKey);
            logRow.setCreateTime(LocalDateTime.now());
            logMapper.save(logRow);
        } catch (Exception e) {
            log.error("数据权限决策留痕失败（不阻断读路径，D7）：account={}, resource={}",
                    decision.getAccount(), decision.getResource(), e);
        }
    }

    /** 决策摘要（设计 §5.2 格式示例）：role:主管(id=2)本部门及以下展开8人|user:zhang3(id=5)自定义集合2人 */
    private String digestRules(DataPermDecision decision) {
        String digest = decision.getHitRules().stream()
                .map(this::digestRule)
                .collect(Collectors.joining("|"));
        return digest.length() > DIGEST_MAX ? digest.substring(0, DIGEST_MAX) : digest;
    }

    private String digestRule(DataPermDecision.HitRule hit) {
        String kind = Integer.valueOf(SysDataPermRule.SubjectTypeEnum.ROLE.getCode()).equals(hit.getSubjectType())
                ? "role:" : "user:";
        StringBuilder sb = new StringBuilder(kind).append(hit.getSubjectName())
                .append("(id=").append(hit.getSubjectId()).append(")")
                .append(SysDataPermRule.RowScopeEnum.of(hit.getRowScope()).label());
        if (hit.getExpandedCount() > 0) {
            sb.append("展开").append(hit.getExpandedCount()).append("人");
        }
        return sb.toString();
    }

    /** 范围结论：all=过滤豁免 / accounts=N / empty=空集（无规则默认 SELF 命中 accounts≥1） */
    private String summarizeScope(DataPermDecision decision) {
        if (decision.getDataScope().isAll()) {
            return "all";
        }
        return decision.getDataScope().isEmptyScope() ? "empty"
                : "accounts=" + decision.getDataScope().getAccounts().size();
    }
}
