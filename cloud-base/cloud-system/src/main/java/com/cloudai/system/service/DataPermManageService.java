package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.DataPermLogPageQuery;
import com.cloudai.system.dto.DataPermRulePageQuery;
import com.cloudai.system.dto.DataPermRuleSaveRequest;
import com.cloudai.system.dto.ExplainQuery;
import com.cloudai.system.dto.MyScopeQuery;
import com.cloudai.system.dto.RuleConfigQuery;
import com.cloudai.system.entity.SysDataPermColumn;
import com.cloudai.system.entity.SysDataPermLog;
import com.cloudai.system.entity.SysDataPermRule;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.DataPermColumnMapper;
import com.cloudai.system.mapper.DataPermLogMapper;
import com.cloudai.system.mapper.DataPermRuleMapper;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.convert.DataPermConvert;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import com.cloudai.system.service.dataperm.DataPermResources;
import com.cloudai.system.vo.DataPermExplainVo;
import com.cloudai.system.vo.DataPermLogVo;
import com.cloudai.system.vo.DataPermResourceVo;
import com.cloudai.system.vo.DataPermRuleConfigVo;
import com.cloudai.system.vo.DataPermRuleVo;
import com.cloudai.system.vo.MyScopeVo;
import com.cloudai.system.vo.SubjectOptionVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 数据权限配置域服务（契约 §3 九端点）：规则分页（subjectName 批量二查 + columns 按
 * 主体类型各一次 IN 批查回填）/ 配置回显 / upsert 全量覆盖保存 / 级联删除 /
 * 资源注册表 / 主体选项 / 留痕检索 / 模拟解释 / 自查摘要。
 * 求值逻辑不在本类（归 DataPermEvaluator）；本类只做配置态 CRUD 与出参组装。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPermManageService {

    /** 数据权限规则不存在（契约 §8） */
    private static final int ERR_RULE_NOT_FOUND = 3031;
    /** 规则主体不存在或已停用（契约 §8） */
    private static final int ERR_SUBJECT_INVALID = 3032;
    /** 自定义范围包含无效账号（契约 §8） */
    private static final int ERR_CUSTOM_INVALID = 3033;

    private final DataPermRuleMapper ruleMapper;
    private final DataPermColumnMapper columnMapper;
    private final DataPermLogMapper logMapper;
    private final SysRoleMapper roleMapper;
    private final SysUserMapper userMapper;
    private final DataPermEvaluator evaluator;

    /**
     * 规则分页（契约 §3.1）：resource 非法值宽松语义=不报错返回空集（与 §3.2 配置回显严格 3034 相区分）；
     * subjectName 按 subjectType 分流批量二查（角色 IN / 用户 IN）；columns 按 (resource,主体类型) 分组
     * 各一次 IN 批查（角色一次 + 用户一次），内存按主体回填。
     */
    public PageResult<DataPermRuleVo> pageListRules(DataPermRulePageQuery query) {
        String resource = blankToNull(query.getResource());
        if (resource != null && !DataPermResources.isRegistered(resource)) {
            return PageResult.of(0, List.of());
        }
        Long subjectId = null;
        if (query.getSubjectId() != null && !query.getSubjectId().isBlank()) {
            try {
                subjectId = Long.parseLong(query.getSubjectId().trim());
            } catch (NumberFormatException e) {
                return PageResult.of(0, List.of());
            }
        }
        IPage<SysDataPermRule> page = ruleMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), resource, query.getSubjectType(), subjectId);
        List<DataPermRuleVo> rows = fillRuleVos(page.getRecords());
        return PageResult.of(page.getTotal(), rows);
    }

    /** 配置回显（契约 §3.2）：严格校验（缺失 1002 / 非法 3034 / 主体无效 3032）；无行规则=新增态默认值 */
    public DataPermRuleConfigVo findRuleConfig(RuleConfigQuery query) {
        validateResourceParam(query.getResource());
        Integer subjectType = requireSubjectType(query.getSubjectType());
        Long subjectId = requireSubject(query.getSubjectId(), subjectType);
        SysDataPermRule rule = ruleMapper.findBySubject(query.getResource(), subjectType, subjectId);
        List<DataPermRuleVo.ColumnRuleVo> columns = DataPermConvert.toColumnRuleVos(
                columnMapper.listBySubject(query.getResource(), subjectType, subjectId));
        DataPermRuleConfigVo vo = new DataPermRuleConfigVo();
        vo.setResource(query.getResource());
        vo.setSubjectType(subjectType);
        vo.setSubjectId(subjectId);
        vo.setConfigured(rule != null);
        vo.setRowScope(rule == null ? null : rule.getRowScope());
        vo.setCustomAccounts(rule != null && isCustomScope(rule.getRowScope())
                ? DataPermConvert.parseCustomAccounts(rule.getCustomAccounts())
                : List.of());
        vo.setColumns(columns);
        return vo;
    }

    /**
     * 保存规则（契约 §3.3，upsert 全量覆盖）：校验链 注册表 3034 → 枚举 1002 → 主体 3032 →
     * CUSTOM 账号 3033/联动 1002；事务内行规则 insert/update + 列规则物理全删后批插（空列表仅删不插）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveRule(DataPermRuleSaveRequest req) {
        validateRuleRequest(req);
        Long subjectId = requireSubject(req.getSubjectId(), req.getSubjectType());
        validateCustomAccounts(req);
        upsertRule(req, subjectId);
        replaceColumns(req, subjectId);
    }

    /** 删除规则（契约 §3.4）：3031 存在性 → 事务内物理删行规则 + 连带物理删同主体全部列规则 */
    @Transactional(rollbackFor = Exception.class)
    public void deleteRule(Long id) {
        SysDataPermRule rule = ruleMapper.findById(id);
        if (rule == null) {
            throw new BusinessException(ERR_RULE_NOT_FOUND, "数据权限规则不存在");
        }
        ruleMapper.deleteById(id);
        columnMapper.deleteBySubject(rule.getResource(), rule.getSubjectType(), rule.getSubjectId());
    }

    /** 资源注册表（契约 §3.5）：配置弹窗资源下拉与列配置动态渲染的数据源 */
    public List<DataPermResourceVo> listResources() {
        List<DataPermResourceVo> result = new ArrayList<>();
        for (String resource : DataPermResources.listRegisteredResources()) {
            DataPermResourceVo vo = new DataPermResourceVo();
            vo.setResource(resource);
            vo.setColumns(DataPermResources.getConfigurableColumns(resource));
            result.add(vo);
        }
        return result;
    }

    /** 主体选项（契约 §3.6）：启用主体（角色 status=0 / 用户 status=0）；label 后端拼好 */
    public List<SubjectOptionVo> listSubjectOptions(Integer type) {
        Integer subjectType = requireSubjectType(type);
        if (Integer.valueOf(SysDataPermRule.SubjectTypeEnum.ROLE.getCode()).equals(subjectType)) {
            return roleMapper.listEnabled().stream()
                    .map(role -> subjectOption(role.getId(), role.getName())).toList();
        }
        return userMapper.listEnabledOptions().stream()
                .map(user -> subjectOption(user.getId(),
                        (user.getNickname() == null ? user.getAccount() : user.getNickname())
                                + "(" + user.getAccount() + ")"))
                .toList();
    }

    /** 决策留痕分页（契约 §3.7）：动态条件检索，account/resource 精确过滤 */
    public PageResult<DataPermLogVo> pageListLog(DataPermLogPageQuery query) {
        IPage<SysDataPermLog> page = logMapper.pageList(new Page<>(query.getPageNum(), query.getPageSize()),
                blankToNull(query.getAccount()), blankToNull(query.getResource()));
        List<DataPermLogVo> rows = page.getRecords().stream()
                .map(DataPermConvert::toLogVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    /** 模拟解释（契约 §3.8）：以目标账号身份完整求值，不执行业务查询、不留痕 */
    public DataPermExplainVo explain(ExplainQuery query) {
        if (query.getAccount() == null || query.getAccount().isBlank()) {
            throw new BusinessException("账号不能为空");
        }
        validateResourceParam(query.getResource());
        DataPermDecision decision = evaluator.explain(query.getAccount().trim(), query.getResource());
        return DataPermConvert.toExplainVo(decision);
    }

    /** 我的数据范围（契约 §3.9）：当前登录人自查，不留痕（D7）——轻量摘要供 leave 页提示条 */
    public MyScopeVo myScope(MyScopeQuery query) {
        validateResourceParam(query.getResource());
        DataPermDecision decision = evaluator.evaluateForSelf(query.getResource());
        return DataPermConvert.toMyScopeVo(decision);
    }

    // ---- 分页行回填（subjectName 批量二查 + columns 分组批查） ----

    private List<DataPermRuleVo> fillRuleVos(List<SysDataPermRule> rules) {
        Map<Long, String> roleNames = loadRoleNames(rules);
        Map<Long, SysUser> users = loadUsers(rules);
        Map<String, List<SysDataPermColumn>> columnMap = loadColumnRules(rules);
        List<DataPermRuleVo> vos = new ArrayList<>();
        for (SysDataPermRule rule : rules) {
            vos.add(DataPermConvert.toRuleVo(rule,
                    resolveSubjectName(rule, roleNames, users),
                    isCustomScope(rule.getRowScope())
                            ? DataPermConvert.parseCustomAccounts(rule.getCustomAccounts())
                            : List.of(),
                    DataPermConvert.toColumnRuleVos(
                            columnMap.getOrDefault(columnMapKey(rule.getResource(), rule.getSubjectType(),
                                    rule.getSubjectId()), List.of()))));
        }
        return vos;
    }

    private Map<Long, String> loadRoleNames(List<SysDataPermRule> rules) {
        List<Long> roleIds = distinctSubjectIds(rules, SysDataPermRule.SubjectTypeEnum.ROLE.getCode());
        if (roleIds.isEmpty()) {
            return Map.of();
        }
        return roleMapper.listByIds(roleIds).stream()
                .collect(Collectors.toMap(SysRole::getId, SysRole::getName, (a, b) -> a));
    }

    private Map<Long, SysUser> loadUsers(List<SysDataPermRule> rules) {
        List<Long> userIds = distinctSubjectIds(rules, SysDataPermRule.SubjectTypeEnum.USER.getCode());
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return userMapper.listByIds(userIds).stream()
                .collect(Collectors.toMap(SysUser::getId, user -> user, (a, b) -> a));
    }

    /** 列规则批查：按 resource 分组，组内角色一次 IN + 用户一次 IN（契约 §3.1 行为说明） */
    private Map<String, List<SysDataPermColumn>> loadColumnRules(List<SysDataPermRule> rules) {
        Map<String, List<SysDataPermColumn>> columnMap = new HashMap<>();
        Map<String, List<SysDataPermRule>> byResource = rules.stream()
                .collect(Collectors.groupingBy(SysDataPermRule::getResource));
        for (Map.Entry<String, List<SysDataPermRule>> entry : byResource.entrySet()) {
            String resource = entry.getKey();
            List<SysDataPermColumn> fetched = new ArrayList<>();
            List<Long> roleIds = distinctSubjectIds(entry.getValue(),
                    SysDataPermRule.SubjectTypeEnum.ROLE.getCode());
            List<Long> userIds = distinctSubjectIds(entry.getValue(),
                    SysDataPermRule.SubjectTypeEnum.USER.getCode());
            if (!roleIds.isEmpty()) {
                fetched.addAll(columnMapper.listByRoleIds(resource, roleIds));
            }
            if (!userIds.isEmpty()) {
                fetched.addAll(columnMapper.listByUserIds(resource, userIds));
            }
            for (SysDataPermColumn col : fetched) {
                columnMap.computeIfAbsent(
                        columnMapKey(col.getResource(), col.getSubjectType(), col.getSubjectId()),
                        k -> new ArrayList<>()).add(col);
            }
        }
        return columnMap;
    }

    /** 主体名解析：角色→角色名；用户→昵称（缺昵称降级账号）；已删主体统一降级 "(已删除)"（契约 §6.2） */
    private String resolveSubjectName(SysDataPermRule rule, Map<Long, String> roleNames, Map<Long, SysUser> users) {
        if (Integer.valueOf(SysDataPermRule.SubjectTypeEnum.ROLE.getCode()).equals(rule.getSubjectType())) {
            return roleNames.getOrDefault(rule.getSubjectId(), "(已删除)");
        }
        SysUser user = users.get(rule.getSubjectId());
        if (user == null) {
            return "(已删除)";
        }
        return user.getNickname() == null || user.getNickname().isBlank()
                ? user.getAccount() : user.getNickname();
    }

    private List<Long> distinctSubjectIds(List<SysDataPermRule> rules, int subjectType) {
        Set<Long> ids = new HashSet<>();
        for (SysDataPermRule rule : rules) {
            if (Integer.valueOf(subjectType).equals(rule.getSubjectType())) {
                ids.add(rule.getSubjectId());
            }
        }
        return new ArrayList<>(ids);
    }

    private String columnMapKey(String resource, Integer subjectType, Long subjectId) {
        return resource + "|" + subjectType + "|" + subjectId;
    }

    // ---- 保存校验链（契约 §3.3 顺序：注册表 3034 → 枚举 1002 → 主体 3032 → CUSTOM 3033/联动 1002） ----

    private void validateRuleRequest(DataPermRuleSaveRequest req) {
        validateResourceParam(req.getResource());
        validateColumns(req.getResource(), req.getColumns());
        requireSubjectType(req.getSubjectType());
        requireRowScope(req.getRowScope());
    }

    /** 列规则校验：columnKey 注册表（3034）→ action 枚举（1002）→ 重复列标识（1002） */
    private void validateColumns(String resource, List<DataPermRuleSaveRequest.ColumnRuleItem> columns) {
        if (columns == null) {
            return;
        }
        Set<String> seen = new HashSet<>();
        for (DataPermRuleSaveRequest.ColumnRuleItem item : columns) {
            DataPermResources.assertColumn(resource, item.getColumnKey());
            if (item.getAction() == null
                    || (item.getAction() != SysDataPermColumn.ActionEnum.HIDDEN.getCode()
                    && item.getAction() != SysDataPermColumn.ActionEnum.MASKED.getCode())) {
                throw new BusinessException("列动作无效");
            }
            if (!seen.add(item.getColumnKey())) {
                throw new BusinessException("列标识重复");
            }
        }
    }

    private void validateResourceParam(String resource) {
        if (resource == null || resource.isBlank()) {
            throw new BusinessException("资源标识不能为空");
        }
        DataPermResources.assertResource(resource);
    }

    private Integer requireSubjectType(Integer subjectType) {
        if (!Integer.valueOf(SysDataPermRule.SubjectTypeEnum.ROLE.getCode()).equals(subjectType)
                && !Integer.valueOf(SysDataPermRule.SubjectTypeEnum.USER.getCode()).equals(subjectType)) {
            throw new BusinessException("主体类型无效");
        }
        return subjectType;
    }

    private Integer requireRowScope(Integer rowScope) {
        if (rowScope == null || rowScope < 0
                || rowScope > SysDataPermRule.RowScopeEnum.ALL.getCode()) {
            throw new BusinessException("行范围档位无效");
        }
        return rowScope;
    }

    /** 主体存在且启用：角色/用户分流（不存在或停用 → 3032，{name} 用主体标识） */
    private Long requireSubject(String subjectId, Integer subjectType) {
        if (subjectId == null || subjectId.isBlank()) {
            throw new BusinessException("主体标识不能为空");
        }
        Long id;
        try {
            id = Long.parseLong(subjectId.trim());
        } catch (NumberFormatException e) {
            throw new BusinessException(ERR_SUBJECT_INVALID, "规则主体不存在或已停用: " + subjectId);
        }
        boolean invalid;
        if (Integer.valueOf(SysDataPermRule.SubjectTypeEnum.ROLE.getCode()).equals(subjectType)) {
            SysRole role = roleMapper.findById(id);
            invalid = role == null || !Integer.valueOf(SysRole.StatusEnum.NORMAL.getCode()).equals(role.getStatus());
        } else {
            SysUser user = userMapper.findById(id);
            invalid = user == null || !Integer.valueOf(SysUser.StatusEnum.NORMAL.getCode()).equals(user.getStatus());
        }
        if (invalid) {
            throw new BusinessException(ERR_SUBJECT_INVALID, "规则主体不存在或已停用: " + subjectId);
        }
        return id;
    }

    /** CUSTOM 档账号校验：非 CUSTOM 档配置了集合 → 1002；CUSTOM 档空集合 → 1002；含无效账号 → 3033 */
    private void validateCustomAccounts(DataPermRuleSaveRequest req) {
        List<String> accounts = req.getCustomAccounts();
        if (!isCustomScope(req.getRowScope())) {
            if (accounts != null && !accounts.isEmpty()) {
                throw new BusinessException("仅自定义范围档可配置账号集合");
            }
            return;
        }
        if (accounts == null || accounts.isEmpty()) {
            throw new BusinessException("自定义账号集合不能为空");
        }
        List<String> invalid = accounts.stream().filter(account -> !isEnabledAccount(account)).toList();
        if (!invalid.isEmpty()) {
            throw new BusinessException(ERR_CUSTOM_INVALID, "自定义范围包含无效账号: " + String.join(",", invalid));
        }
    }

    private boolean isEnabledAccount(String account) {
        SysUser user = userMapper.findByAccount(account);
        return user != null && Integer.valueOf(SysUser.StatusEnum.NORMAL.getCode()).equals(user.getStatus());
    }

    // ---- 事务体：行规则 upsert + 列规则全删全插 ----

    private void upsertRule(DataPermRuleSaveRequest req, Long subjectId) {
        SysDataPermRule existing = ruleMapper.findBySubject(req.getResource(), req.getSubjectType(), subjectId);
        String customJson = isCustomScope(req.getRowScope())
                ? DataPermConvert.toCustomAccountsJson(req.getCustomAccounts())
                : null;
        if (existing == null) {
            SysDataPermRule rule = new SysDataPermRule();
            rule.setResource(req.getResource());
            rule.setSubjectType(req.getSubjectType());
            rule.setSubjectId(subjectId);
            rule.setRowScope(req.getRowScope());
            rule.setCustomAccounts(customJson);
            String operator = SecurityUtils.currentAccount();
            LocalDateTime now = LocalDateTime.now();
            rule.setCreateBy(operator);
            rule.setCreateTime(now);
            rule.setUpdateBy(operator);
            rule.setUpdateTime(now);
            try {
                ruleMapper.save(rule);
            } catch (DuplicateKeyException e) {
                log.error("规则唯一键冲突（并发 upsert）：{}", e.getMessage());
                throw new BusinessException("规则配置已存在，请刷新后重试");
            }
            return;
        }
        SysDataPermRule rule = new SysDataPermRule();
        rule.setId(existing.getId());
        rule.setRowScope(req.getRowScope());
        rule.setCustomAccounts(customJson);
        rule.setUpdateBy(SecurityUtils.currentAccount());
        rule.setUpdateTime(LocalDateTime.now());
        ruleMapper.update(rule);
    }

    /** 列规则全删全插（空列表仅删不插——foreach 空集合非法 SQL，Service 跳过调用） */
    private void replaceColumns(DataPermRuleSaveRequest req, Long subjectId) {
        columnMapper.deleteBySubject(req.getResource(), req.getSubjectType(), subjectId);
        List<DataPermRuleSaveRequest.ColumnRuleItem> items = req.getColumns();
        if (items == null || items.isEmpty()) {
            return;
        }
        String operator = SecurityUtils.currentAccount();
        LocalDateTime now = LocalDateTime.now();
        List<SysDataPermColumn> entities = new ArrayList<>();
        for (DataPermRuleSaveRequest.ColumnRuleItem item : items) {
            SysDataPermColumn col = new SysDataPermColumn();
            col.setResource(req.getResource());
            col.setSubjectType(req.getSubjectType());
            col.setSubjectId(subjectId);
            col.setColumnKey(item.getColumnKey());
            col.setAction(item.getAction());
            col.setCreateBy(operator);
            col.setCreateTime(now);
            col.setUpdateBy(operator);
            col.setUpdateTime(now);
            entities.add(col);
        }
        columnMapper.saveBatch(entities);
    }

    private boolean isCustomScope(Integer rowScope) {
        return Integer.valueOf(SysDataPermRule.RowScopeEnum.CUSTOM.getCode()).equals(rowScope);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private SubjectOptionVo subjectOption(Long id, String label) {
        SubjectOptionVo vo = new SubjectOptionVo();
        vo.setId(id);
        vo.setLabel(label);
        return vo;
    }
}
