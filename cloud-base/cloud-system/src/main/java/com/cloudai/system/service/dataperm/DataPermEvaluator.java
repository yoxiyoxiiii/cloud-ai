package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataPermOperation;
import com.cloudai.system.api.dataperm.DataScope;
import com.cloudai.system.api.dataperm.FilterColumn;
import com.cloudai.system.api.dataperm.PredicateOperatorEnum;
import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.api.dataperm.RowGroup;
import com.cloudai.system.api.dataperm.RowPredicate;
import com.cloudai.system.convert.DataPermConvert;
import com.cloudai.system.dto.PredicateItem;
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
 * 组件化轮（2026-10-10 D18/D23）：终态类型 DataScope/ColumnScope/DataPermOperation 改引 api 包
 * （行为零变更）；新增 evaluateFor 账号显式入口（/inner/data-perm 跨服务求值薄壳底层），
 * logDeny 签名账号显式化——leave 本地调用路径行为不变（D26）。
 * 谓词轮（2026-10-11 D31/D33/D39）：resolveRowScope 升级 resolveRowFilter——每规则解析条件组并
 * enrichment（坏 JSON/越界字段/归一失败 → 该规则整体不授权，fail-closed 无放行分支）、豁免判定
 * （ALL 无条件才豁免——ALL+条件不豁免）、无条件规则并成单组 + 死组丢弃；无条件用户代数输出与
 * 旧版逐字等价（单规则退化为单组 (apply_user IN (...))，dataScope 账号并集口径不变——零 diff 红线）。
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
    private final TranslationCacheService translationCacheService;

    /** 真实决策（当前登录人，SecurityUtils.currentUser——设计 §5.4 additive）：恒留痕（D7） */
    public DataPermDecision evaluate(String resource, String operation, String businessKey) {
        LoginUser current = SecurityUtils.currentUser();
        String account = current == null ? null : current.getAccount();
        Long userId = current == null ? null : current.getUserId();
        DataPermDecision decision = doEvaluate(account, userId, resource, operation);
        persistLog(decision, businessKey);
        return decision;
    }

    /**
     * 账号显式真实决策（组件化 D20/D23，/inner/data-perm/evaluate 底层）：恒留痕（D7）。
     * account→用户解析（findByAccount→userId）：不存在/停用不报错，userId=null 收敛——
     * 无规则默认 SELF={account}，方向安全不越权（D20）；与 explain 的 3032 语义不同
     * （explain 是管理侧模拟需明确报目标无效，跨服务求值是读路径防御）。
     */
    public DataPermDecision evaluateFor(String account, String resource, String operation, String businessKey) {
        if (account == null || account.isBlank()) {
            throw new BusinessException("账号不能为空");
        }
        DataPermResources.assertResource(resource);
        Long userId = resolveUserIdOrNull(account);
        DataPermDecision decision = doEvaluate(account, userId, resource, operation);
        persistLog(decision, businessKey);
        return decision;
    }

    /** 账号→userId 解析：不存在或停用返回 null（收敛口径见 evaluateFor javadoc，D20） */
    private Long resolveUserIdOrNull(String account) {
        SysUser target = userMapper.findByAccount(account);
        if (target == null || !Integer.valueOf(SysUser.StatusEnum.NORMAL.getCode()).equals(target.getStatus())) {
            return null;
        }
        return target.getId();
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

    /**
     * deny 补痕（设计 D7/D13；组件化 D23 账号显式化——/inner/data-perm/deny 薄壳直调，
     * 本地调用方显式传 SecurityUtils.currentAccount() 行为等价）：详情被行级拒绝后补记
     * 安全审计事件（谁在何时试图越权访问哪张单）。
     */
    public void logDeny(String account, String resource, String businessKey) {
        DataPermResources.assertResource(resource);
        SysDataPermLog denyRow = new SysDataPermLog();
        denyRow.setAccount(account);
        denyRow.setResource(resource);
        denyRow.setOperation(DataPermOperation.DENY);
        // deny 行是拒绝事件标记而非范围结论：scopeSummary 固定 'deny'（NOT NULL 约束），规则摘要留空
        denyRow.setScopeSummary("deny");
        denyRow.setBusinessKey(businessKey);
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
        // roleIds 先取：columnOnly 资源的列规则收敛同样依赖角色维度，取数不因分支跳过
        List<Long> roleIds = userId == null ? List.of() : roleMapper.listEnabledRoleIdsByUserId(userId);
        DataPermDecision decision = new DataPermDecision();
        decision.setResource(resource);
        decision.setAccount(account);
        decision.setOperation(operation);
        if (DataPermResources.isColumnOnly(resource)) {
            // 列级-only（列级数据权限轮 D60）：行档位与谓词不参与收敛——行范围恒全部、无行过滤、
            // 行规则不收集（hitRules 留空=rule_digest 不出现行规则，与「行级不生效」契约声明自洽）；
            // 留痕 scope_summary 恒 all；列段照常求值（并集宽松者胜）
            decision.setDataScope(DataScope.all());
            decision.setRowFilter(RowFilter.rowAll());
            resolveColumns(resource, userId, roleIds, decision);
            buildNarratives(decision);
            return decision;
        }
        List<SysDataPermRule> rules = collectRules(resource, userId, roleIds);
        resolveRowFilter(resource, account, userId, rules, decision);
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

    /**
     * 行级收敛（D3 + 谓词轮 D31 四步代数）：解析 enrichment → 豁免判定 → 逐规则建组 → 合并。
     * <b>无条件规则零 diff</b>：无规则默认 SELF 单组、非豁免逐档展开并集、豁免 all() 短路——
     * dataScope 取值与旧版逐字等价；rowFilter 为行集判定唯一权威（list SQL / detail 判定读它）。
     */
    private void resolveRowFilter(String resource, String account, Long userId, List<SysDataPermRule> rules,
            DataPermDecision decision) {
        List<DataPermDecision.HitRule> hits = rules.stream().map(r -> toHitRule(r, account)).toList();
        decision.setHitRules(new ArrayList<>(hits));
        if (rules.isEmpty()) {
            // 无规则默认 SELF（fail-safe，D3）：普通用户「我的请假」行为与旧版一致；匿名防御=空集
            applyDefaultSelfScope(account, decision);
            return;
        }
        List<List<RowPredicate>> predicatesPerRule = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            predicatesPerRule.add(enrichRulePredicates(resource, rules.get(i), decision.getHitRules().get(i)));
        }
        if (hasExemptRule(rules, predicatesPerRule)) {
            // 豁免态（任一 ALL 无条件）：rowAll 短路；dataScope 保持 all() 既有取值（零 diff 红线记档）
            decision.setDataScope(DataScope.all());
            decision.setRowFilter(RowFilter.rowAll());
            return;
        }
        mergeGroups(account, userId, rules, predicatesPerRule, decision);
    }

    /** 无规则默认 SELF 落 filter 形态（行集=apply_user IN ({自己})，与 dataScope 逐字等价） */
    private void applyDefaultSelfScope(String account, DataPermDecision decision) {
        Set<String> selfOnly = account == null ? Set.of() : Set.of(account);
        decision.setDataScope(DataScope.of(selfOnly));
        decision.setRowFilter(selfOnly.isEmpty() ? RowFilter.of(List.of())
                : RowFilter.of(List.of(RowGroup.of(false, selfOnly, List.of()))));
    }

    /**
     * 规则条件组解析 + enrichment（D33/D39）：JSON 坏 / 字段越界 / 值归一失败 → 返回 null =
     * 该规则整体不授权（组与账号维均不贡献，fail-closed 无放行分支——不部分采用：组内 AND
     * 部分采用即放大语义）。成功返回空列表（无条件）或类型化谓词列表（条件规则）。
     */
    private List<RowPredicate> enrichRulePredicates(String resource, SysDataPermRule rule,
            DataPermDecision.HitRule hit) {
        List<PredicateItem> items = DataPermConvert.parsePredicates(rule.getPredicates());
        if (items == null) {
            return null;
        }
        hit.setPredicates(items);
        if (items.isEmpty()) {
            return List.of();
        }
        List<RowPredicate> enriched = new ArrayList<>();
        for (PredicateItem item : items) {
            RowPredicate predicate = enrichPredicate(resource, item);
            if (predicate == null) {
                return null;
            }
            enriched.add(predicate);
        }
        return enriched;
    }

    /** 单条件 enrichment：注册表定位声明列（3035 同源）→ 操作符枚举 → 值逐个类型化；任一失败 null */
    private RowPredicate enrichPredicate(String resource, PredicateItem item) {
        FilterColumn declared = DataPermResources.findFilterColumn(resource, item.getField());
        if (declared == null) {
            log.error("条件字段不在资源可过滤清单（该规则整体不授权，D39）：resource={}, field={}",
                    resource, item.getField());
            return null;
        }
        PredicateOperatorEnum operator;
        try {
            operator = PredicateOperatorEnum.of(item.getOperator());
        } catch (IllegalArgumentException e) {
            log.error("条件操作符非法（该规则整体不授权，D39）：resource={}, field={}, operator={}",
                    resource, item.getField(), item.getOperator());
            return null;
        }
        try {
            List<Object> typed = new ArrayList<>();
            for (String value : item.getValues() == null ? List.<String>of() : item.getValues()) {
                typed.add(declared.type().normalize(value));
            }
            return RowPredicate.of(item.getField(), declared.column(), operator, typed);
        } catch (IllegalArgumentException e) {
            log.error("条件值归一失败（该规则整体不授权，D39）：resource={}, field={}", resource, item.getField(), e);
            return null;
        }
    }

    /** 豁免判定（D31 ①）：任一 ALL 档且无条件（解析失败规则不构成豁免——fail-closed） */
    private boolean hasExemptRule(List<SysDataPermRule> rules, List<List<RowPredicate>> predicatesPerRule) {
        for (int i = 0; i < rules.size(); i++) {
            if (isAllTier(rules.get(i))
                    && predicatesPerRule.get(i) != null && predicatesPerRule.get(i).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 组装（D31 ②③）：无条件规则账号并成单组（首位——单规则用户退化为 (apply_user IN (...))，
     * 与旧 SQL 行集语义逐字等价）；死组（!allAccounts && 账号空）丢弃——AND 语义下条件救不回账号空集；
     * dataScope=非 ALL 活规则账号维并集（ALL+条件规则展开恒空集零贡献，与旧版一致）。
     */
    private void mergeGroups(String account, Long userId, List<SysDataPermRule> rules,
            List<List<RowPredicate>> predicatesPerRule, DataPermDecision decision) {
        List<RowGroup> groups = new ArrayList<>();
        Set<String> unconditionalAccounts = new LinkedHashSet<>();
        Set<String> accountUnion = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            SysDataPermRule rule = rules.get(i);
            List<RowPredicate> predicates = predicatesPerRule.get(i);
            Set<String> expanded = expandRule(account, userId, rule);
            decision.getHitRules().get(i).setExpandedCount(expanded.size());
            if (predicates == null) {
                continue; // D39 降级规则：整体不授权（组与账号维均不贡献）
            }
            accountUnion.addAll(expanded);
            if (predicates.isEmpty()) {
                unconditionalAccounts.addAll(expanded);
                continue;
            }
            if (!isAllTier(rule) && expanded.isEmpty()) {
                continue; // 死组丢弃（D31 ③）
            }
            groups.add(RowGroup.of(isAllTier(rule), expanded, predicates));
        }
        if (!unconditionalAccounts.isEmpty()) {
            groups.add(0, RowGroup.of(false, unconditionalAccounts, List.of()));
        }
        decision.setDataScope(DataScope.of(accountUnion));
        decision.setRowFilter(RowFilter.of(groups));
    }

    /** 档位是否 ALL（与 RowScopeEnum.of 判定同源的字面量封装，私有 helper 免枚举散落） */
    private boolean isAllTier(SysDataPermRule rule) {
        return Integer.valueOf(SysDataPermRule.RowScopeEnum.ALL.getCode()).equals(rule.getRowScope());
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

    /** narratives（全中文可解释）：每命中规则一句 + 每条件一句 + 收敛结论一句 + 列决策一句 */
    private void buildNarratives(DataPermDecision decision) {
        List<String> narratives = decision.getNarratives();
        decision.getHitRules().forEach(hit -> {
            narratives.add(ruleNarrative(hit));
            addPredicateNarratives(decision.getResource(), hit, narratives);
        });
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

    /** 每条件一句（契约 §2.5，谓词轮 D36）：命中条件：{列label} {操作符中文} {值}——IN 顿号连接、BETWEEN v0~v1 */
    private void addPredicateNarratives(String resource, DataPermDecision.HitRule hit, List<String> narratives) {
        if (hit.getPredicates() == null) {
            return;
        }
        for (PredicateItem item : hit.getPredicates()) {
            String sentence = predicateNarrative(resource, item);
            if (sentence != null) {
                narratives.add(sentence);
            }
        }
    }

    /**
     * 单条件叙述句：列声明/操作符/值形态缺失（越界配置）不叙述——explain 的 HitRuleVo.predicates 仍原样回显。
     * 操作符扩展轮 D45：字典列（declared.dictKey 非空）值文本译标签（译文缺失回退原值，显示层 fail-open）；
     * digest 与 wire 回显不经本方法（原值）。resolver = 注入的 TranslationCacheService::findDictLabels。
     */
    private String predicateNarrative(String resource, PredicateItem item) {
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
        List<String> values = DataPermConvert.translateValues(declared, item.getValues(),
                translationCacheService::findDictLabels);
        String valueText = switch (operator) {
            case IN -> String.join("、", values);
            case BETWEEN -> values.get(0) + "~" + values.get(1);
            default -> values.get(0);
        };
        return "命中条件：" + declared.label() + " " + operator.getLabel() + " " + valueText;
    }

    private String scopeNarrative(DataPermDecision decision) {
        // 列级-only 资源行范围不适用（列级数据权限轮 D60）：行段恒全部短路，不落既有收敛句式
        if (DataPermResources.isColumnOnly(decision.getResource())) {
            return "该资源仅列级权限生效，行范围不适用";
        }
        if (decision.getDataScope().isAll()) {
            return "行范围收敛：全部（行过滤豁免）";
        }
        if (decision.getHitRules().isEmpty()) {
            return "无规则命中，默认仅自己";
        }
        int conditional = countConditionalRules(decision);
        if (rowScopeEmpty(decision)) {
            return "行范围收敛：空集（无可见数据）";
        }
        if (conditional > 0) {
            // 谓词轮 D36：含条件时账号数不再是行集语义——改述规则构成
            return "行范围收敛：并集含条件过滤（" + decision.getHitRules().size() + " 条规则，"
                    + conditional + " 条含条件）";
        }
        return "行范围收敛：并集共 " + decision.getDataScope().getAccounts().size() + " 个账号";
    }

    /** 含条件命中规则数（label/摘要/结论句增量判据，D36） */
    private int countConditionalRules(DataPermDecision decision) {
        return (int) decision.getHitRules().stream()
                .filter(hit -> hit.getPredicates() != null && !hit.getPredicates().isEmpty())
                .count();
    }

    /** 空集判据 v2（D31）：rowFilter 优先（ALL+条件用户 dataScope 空但行集非空）；rowFilter 缺席回退 dataScope */
    private boolean rowScopeEmpty(DataPermDecision decision) {
        if (decision.getRowFilter() != null) {
            return decision.getRowFilter().isEmptyScope();
        }
        return decision.getDataScope().isEmptyScope();
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
            logMapper.save(logRow);
        } catch (Exception e) {
            log.error("数据权限决策留痕失败（不阻断读路径，D7）：account={}, resource={}",
                    decision.getAccount(), decision.getResource(), e);
        }
    }

    /** 决策摘要（设计 §5.2 + 谓词轮 D36）：无条件形态逐字不变；条件规则尾缀 ∧{条件摘要} */
    private String digestRules(DataPermDecision decision) {
        String digest = decision.getHitRules().stream()
                .map(hit -> digestRule(decision.getResource(), hit))
                .collect(Collectors.joining("|"));
        return digest.length() > DIGEST_MAX ? digest.substring(0, DIGEST_MAX) : digest;
    }

    private String digestRule(String resource, DataPermDecision.HitRule hit) {
        String kind = Integer.valueOf(SysDataPermRule.SubjectTypeEnum.ROLE.getCode()).equals(hit.getSubjectType())
                ? "role:" : "user:";
        StringBuilder sb = new StringBuilder(kind).append(hit.getSubjectName())
                .append("(id=").append(hit.getSubjectId()).append(")")
                .append(SysDataPermRule.RowScopeEnum.of(hit.getRowScope()).label());
        if (hit.getExpandedCount() > 0) {
            sb.append("展开").append(hit.getExpandedCount()).append("人");
        }
        String predDigest = predicateDigest(resource, hit.getPredicates());
        if (!predDigest.isEmpty()) {
            sb.append("∧").append(predDigest);
        }
        return sb.toString();
    }

    /**
     * 条件摘要（契约 §1.6 ASCII 紧凑型）：EQ {column}={v} / GTE {column}&gt;={v} / LTE {column}&lt;={v} /
     * BETWEEN {column}:{v0}~{v1} / IN {column}:{v1,v2}（IN 形态契约未示例，本轮外推定义——分号连接）；
     * 操作符扩展轮 D41 新四符号：GT {column}&gt;{v} / LT {column}&lt;{v} / NE {column}!={v} /
     * PREFIX {column}^{v}（值一律原值不翻译——digest 机器面向，D45 不翻译面）。
     * 越界条件/非法操作符跳过（求值 enrichment 已拒其授权；explain 出参仍有原样回显可排查）。
     */
    private String predicateDigest(String resource, List<PredicateItem> predicates) {
        if (predicates == null || predicates.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (PredicateItem item : predicates) {
            FilterColumn declared = DataPermResources.findFilterColumn(resource, item.getField());
            PredicateOperatorEnum operator;
            try {
                operator = PredicateOperatorEnum.of(item.getOperator());
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (declared == null || item.getValues() == null || item.getValues().isEmpty()
                    || (operator == PredicateOperatorEnum.BETWEEN && item.getValues().size() < 2)) {
                continue;
            }
            parts.add(digestPredicate(declared.column(), operator, item.getValues()));
        }
        return String.join(";", parts);
    }

    private String digestPredicate(String column, PredicateOperatorEnum operator, List<String> values) {
        return switch (operator) {
            case EQ -> column + "=" + values.get(0);
            case GTE -> column + ">=" + values.get(0);
            case LTE -> column + "<=" + values.get(0);
            case GT -> column + ">" + values.get(0);
            case LT -> column + "<" + values.get(0);
            case NE -> column + "!=" + values.get(0);
            case PREFIX -> column + "^" + values.get(0);
            case IN -> column + ":" + String.join(",", values);
            case BETWEEN -> column + ":" + values.get(0) + "~" + values.get(1);
        };
    }

    /** 范围结论：all=过滤豁免 / empty=空集 / accounts=N（无条件，逐字不变）/ accounts=N;cond=K（含条件，D36） */
    private String summarizeScope(DataPermDecision decision) {
        if (decision.getDataScope().isAll()) {
            return "all";
        }
        if (rowScopeEmpty(decision)) {
            return "empty";
        }
        int conditional = countConditionalRules(decision);
        return conditional > 0
                ? "accounts=" + decision.getDataScope().getAccounts().size() + ";cond=" + conditional
                : "accounts=" + decision.getDataScope().getAccounts().size();
    }
}
