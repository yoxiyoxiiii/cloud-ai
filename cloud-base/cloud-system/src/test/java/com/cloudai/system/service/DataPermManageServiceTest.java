package com.cloudai.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.DataPermRulePageQuery;
import com.cloudai.system.dto.DataPermRuleSaveRequest;
import com.cloudai.system.dto.MyScopeQuery;
import com.cloudai.system.dto.RuleConfigQuery;
import com.cloudai.system.entity.SysDataPermColumn;
import com.cloudai.system.entity.SysDataPermRule;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.DataPermColumnMapper;
import com.cloudai.system.mapper.DataPermLogMapper;
import com.cloudai.system.mapper.DataPermRuleMapper;
import com.cloudai.system.mapper.SysRoleMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import com.cloudai.system.vo.DataPermRuleConfigVo;
import com.cloudai.system.vo.DataPermRuleVo;
import com.cloudai.system.vo.MyScopeVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 数据权限配置域服务单测：saveRule 校验链（契约 §3.3 顺序）/ upsert 覆盖语义 /
 * deleteRule 级联 / 分页行 subjectName+columns 批量回填 / 配置回显新增态 / 自查摘要。
 */
@ExtendWith(MockitoExtension.class)
class DataPermManageServiceTest {

    private static final String RESOURCE = "leave";

    @Mock
    private DataPermRuleMapper ruleMapper;
    @Mock
    private DataPermColumnMapper columnMapper;
    @Mock
    private DataPermLogMapper logMapper;
    @Mock
    private SysRoleMapper roleMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private DataPermEvaluator evaluator;
    @InjectMocks
    private DataPermManageService manageService;

    // ---- saveRule 校验链（3034 → 1002 → 3032 → 3033/1002） ----

    @Test
    void saveRule_unregisteredResource_rejected3034() {
        DataPermRuleSaveRequest req = validRequest("nope");

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3034);
        verifyNoInteractions(ruleMapper, columnMapper);
    }

    @Test
    void saveRule_badSubjectType_rejected1002() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setSubjectType(9);

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("主体类型无效");
    }

    @Test
    void saveRule_badRowScope_rejected1002() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setRowScope(7);

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("行范围档位无效");
    }

    @Test
    void saveRule_invalidColumnKey_rejected3034() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setColumns(List.of(columnItem("not-a-column", 1)));

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3034);
        verifyNoInteractions(ruleMapper);
    }

    @Test
    void saveRule_badColumnAction_rejected1002() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setColumns(List.of(columnItem("reason", 5)));

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("列动作无效");
    }

    @Test
    void saveRule_duplicateColumnKey_rejected1002() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setColumns(List.of(columnItem("reason", 0), columnItem("reason", 1)));

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("列标识重复");
    }

    @Test
    void saveRule_subjectInvalid_rejected3032() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        when(roleMapper.findById(2L)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3032);
        verifyNoInteractions(columnMapper);
    }

    @Test
    void saveRule_customScopeEmptyAccounts_rejected1002() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setRowScope(3);
        req.setCustomAccounts(null);
        stubRoleSubject();

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("自定义账号集合不能为空");
    }

    @Test
    void saveRule_customScopeInvalidAccounts_rejected3033() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setRowScope(3);
        req.setCustomAccounts(List.of("zhang3", "ghost"));
        stubRoleSubject();
        when(userMapper.findByAccount("zhang3")).thenReturn(enabledUser());
        when(userMapper.findByAccount("ghost")).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3033);
        assertThat(ex.getMessage()).contains("ghost");
    }

    @Test
    void saveRule_nonCustomScopeWithAccounts_rejected1002() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setRowScope(2);
        req.setCustomAccounts(List.of("zhang3"));
        stubRoleSubject();

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        assertThat(ex.getMessage()).isEqualTo("仅自定义范围档可配置账号集合");
    }

    // ---- saveRule 事务体：insert / update 覆盖 / 并发兜底 ----

    @Test
    void saveRule_insertPath_savesRuleAndReplacesColumns() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setColumns(List.of(columnItem("reason", 1), columnItem("title", 0)));
        stubRoleSubject();
        when(ruleMapper.findBySubject(RESOURCE, 0, 2L)).thenReturn(null);

        manageService.saveRule(req);

        // 行规则 insert（档位 + 审计四值）；列规则全删后批插两条
        ArgumentCaptor<SysDataPermRule> ruleCaptor = ArgumentCaptor.forClass(SysDataPermRule.class);
        verify(ruleMapper).save(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue().getRowScope()).isEqualTo(2);
        assertThat(ruleCaptor.getValue().getCustomAccounts()).isNull();
        assertThat(ruleCaptor.getValue().getCreateTime()).isNotNull();
        verify(columnMapper).deleteBySubject(RESOURCE, 0, 2L);
        ArgumentCaptor<List<SysDataPermColumn>> colCaptor = ArgumentCaptor.captor();
        verify(columnMapper).saveBatch(colCaptor.capture());
        assertThat(colCaptor.getValue()).hasSize(2);
        assertThat(colCaptor.getValue().get(0).getColumnKey()).isEqualTo("reason");
        assertThat(colCaptor.getValue().get(0).getAction()).isEqualTo(1);
    }

    @Test
    void saveRule_upsertPath_overridesExistingRule() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        req.setRowScope(4);
        stubRoleSubject();
        SysDataPermRule existing = new SysDataPermRule();
        existing.setId(11L);
        when(ruleMapper.findBySubject(RESOURCE, 0, 2L)).thenReturn(existing);

        manageService.saveRule(req);

        // 覆盖语义：按既有 id update，不再 insert；customAccounts 清空（非 CUSTOM 档）
        ArgumentCaptor<SysDataPermRule> ruleCaptor = ArgumentCaptor.forClass(SysDataPermRule.class);
        verify(ruleMapper).update(ruleCaptor.capture());
        assertThat(ruleCaptor.getValue().getId()).isEqualTo(11L);
        assertThat(ruleCaptor.getValue().getRowScope()).isEqualTo(4);
        assertThat(ruleCaptor.getValue().getCustomAccounts()).isNull();
        verify(ruleMapper, never()).save(any());
        verify(columnMapper).deleteBySubject(RESOURCE, 0, 2L);
        verify(columnMapper, never()).saveBatch(anyList());
    }

    @Test
    void saveRule_duplicateKeyRace_rejected1002() {
        DataPermRuleSaveRequest req = validRequest(RESOURCE);
        stubRoleSubject();
        when(ruleMapper.findBySubject(RESOURCE, 0, 2L)).thenReturn(null);
        when(ruleMapper.save(any())).thenThrow(new DuplicateKeyException("uk_resource_subject"));

        BusinessException ex = catchThrowableOfType(() -> manageService.saveRule(req), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
    }

    // ---- deleteRule：存在性 + 级联 ----

    @Test
    void deleteRule_notFound_rejected3031() {
        when(ruleMapper.findById(9L)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> manageService.deleteRule(9L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3031);
        verifyNoInteractions(columnMapper);
    }

    @Test
    void deleteRule_cascadesColumnRules() {
        SysDataPermRule rule = new SysDataPermRule();
        rule.setId(9L);
        rule.setResource(RESOURCE);
        rule.setSubjectType(0);
        rule.setSubjectId(2L);
        when(ruleMapper.findById(9L)).thenReturn(rule);

        manageService.deleteRule(9L);

        verify(ruleMapper).deleteById(9L);
        verify(columnMapper).deleteBySubject(RESOURCE, 0, 2L);
    }

    // ---- 分页行回填：subjectName 批量二查 + columns 批查 ----

    @Test
    void pageListRules_backfillsNamesAndColumns() {
        SysDataPermRule roleRule = rule(3L, 0, 2L, 2);
        SysDataPermRule userRule = rule(7L, 1, 5L, 0);
        when(ruleMapper.pageList(any(), eq(RESOURCE), any(), any()))
                .thenReturn(pageOf(List.of(roleRule, userRule)));
        when(roleMapper.listByIds(List.of(2L))).thenReturn(List.of(role(2L, "主管")));
        SysUser user5 = new SysUser();
        user5.setId(5L);
        user5.setAccount("zhang3");
        user5.setNickname("张三");
        when(userMapper.listByIds(List.of(5L))).thenReturn(List.of(user5));
        when(columnMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(column(0, 2L, "reason", 1)));
        when(columnMapper.listByUserIds(RESOURCE, List.of(5L))).thenReturn(List.of());

        DataPermRulePageQuery query = new DataPermRulePageQuery();
        query.setResource(RESOURCE);
        List<DataPermRuleVo> rows = manageService.pageListRules(query).getRows();

        assertThat(rows).hasSize(2);
        DataPermRuleVo roleVo = rows.get(0);
        assertThat(roleVo.getSubjectName()).isEqualTo("主管");
        assertThat(roleVo.getColumns()).hasSize(1);
        assertThat(roleVo.getColumns().get(0).getColumnKey()).isEqualTo("reason");
        assertThat(roleVo.getColumns().get(0).getAction()).isEqualTo(1);
        assertThat(roleVo.getCustomAccounts()).isEmpty();
        DataPermRuleVo userVo = rows.get(1);
        assertThat(userVo.getSubjectName()).isEqualTo("张三");
        assertThat(userVo.getColumns()).isEmpty();
    }

    @Test
    void pageListRules_deletedSubject_degradesName() {
        SysDataPermRule roleRule = rule(3L, 0, 2L, 2);
        when(ruleMapper.pageList(any(), eq(null), any(), any()))
                .thenReturn(pageOf(List.of(roleRule)));
        when(roleMapper.listByIds(List.of(2L))).thenReturn(List.of());
        when(columnMapper.listByRoleIds(RESOURCE, List.of(2L))).thenReturn(List.of());

        List<DataPermRuleVo> rows = manageService.pageListRules(new DataPermRulePageQuery()).getRows();

        assertThat(rows.get(0).getSubjectName()).isEqualTo("(已删除)");
        assertThat(rows.get(0).getColumns()).isEmpty();
    }

    @Test
    void pageListRules_unregisteredResource_returnsEmptyWithoutQuery() {
        DataPermRulePageQuery query = new DataPermRulePageQuery();
        query.setResource("nope");

        var result = manageService.pageListRules(query);

        // §3.1 宽松语义：非法资源不报错、不触库，直接空集
        assertThat(result.getTotal()).isZero();
        assertThat(result.getRows()).isEmpty();
        verifyNoInteractions(ruleMapper, roleMapper, userMapper, columnMapper);
    }

    // ---- 配置回显：严格校验 + 新增态默认 ----

    @Test
    void findRuleConfig_subjectInvalid_rejected3032() {
        RuleConfigQuery query = configQuery();
        when(roleMapper.findById(2L)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> manageService.findRuleConfig(query), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3032);
    }

    @Test
    void findRuleConfig_notConfigured_returnsDefaults() {
        RuleConfigQuery query = configQuery();
        stubRoleSubject();
        when(ruleMapper.findBySubject(RESOURCE, 0, 2L)).thenReturn(null);
        when(columnMapper.listBySubject(RESOURCE, 0, 2L)).thenReturn(List.of());

        DataPermRuleConfigVo vo = manageService.findRuleConfig(query);

        assertThat(vo.isConfigured()).isFalse();
        assertThat(vo.getRowScope()).isNull();
        assertThat(vo.getCustomAccounts()).isEmpty();
        assertThat(vo.getColumns()).isEmpty();
        assertThat(vo.getSubjectId()).isEqualTo(2L);
    }

    // ---- 自查摘要 ----

    @Test
    void myScope_selfOnlyScope_labelAndColumnSummary() {
        MyScopeQuery query = new MyScopeQuery();
        query.setResource(RESOURCE);
        DataPermDecision decision = new DataPermDecision();
        decision.setAccount("userA");
        decision.setDataScope(com.cloudai.system.api.dataperm.DataScope.of(java.util.Set.of("userA")));
        decision.setColumnScope(com.cloudai.system.api.dataperm.ColumnScope.of(
                java.util.Set.of(), java.util.Set.of("reason")));
        when(evaluator.evaluateForSelf(RESOURCE)).thenReturn(decision);

        MyScopeVo vo = manageService.myScope(query);

        // 无规则命中 → 终裁序兜底位「仅自己」
        assertThat(vo.getScopeLabel()).isEqualTo("仅自己");
        assertThat(vo.getColumnSummary()).isEqualTo("reason:脱敏");
    }

    @Test
    void myScope_deptTier_labeledDesignatedRange() {
        MyScopeQuery query = new MyScopeQuery();
        query.setResource(RESOURCE);
        DataPermDecision decision = new DataPermDecision();
        decision.setAccount("userA");
        decision.setDataScope(com.cloudai.system.api.dataperm.DataScope.of(java.util.Set.of("userA", "userB")));
        decision.setColumnScope(com.cloudai.system.api.dataperm.ColumnScope.of(
                java.util.Set.of(), java.util.Set.of()));
        decision.getHitRules().add(hitRule(SysDataPermRule.RowScopeEnum.DEPT_AND_CHILD.getCode()));
        when(evaluator.evaluateForSelf(RESOURCE)).thenReturn(decision);

        MyScopeVo vo = manageService.myScope(query);

        // 主控终裁序第 3 位：部门档展开集（含自己）落「指定范围」，不落「自定义范围」防排查误导
        assertThat(vo.getScopeLabel()).isEqualTo("指定范围（2 人）");
    }

    @Test
    void myScope_deptTierExpandedExactlySelf_labeledDesignatedRange() {
        MyScopeQuery query = new MyScopeQuery();
        query.setResource(RESOURCE);
        DataPermDecision decision = new DataPermDecision();
        decision.setAccount("userA");
        decision.setDataScope(com.cloudai.system.api.dataperm.DataScope.of(java.util.Set.of("userA")));
        decision.setColumnScope(com.cloudai.system.api.dataperm.ColumnScope.of(
                java.util.Set.of(), java.util.Set.of()));
        decision.getHitRules().add(hitRule(SysDataPermRule.RowScopeEnum.DEPT.getCode()));
        when(evaluator.evaluateForSelf(RESOURCE)).thenReturn(decision);

        MyScopeVo vo = manageService.myScope(query);

        // 主控终裁：部门档语义优先于展开形状——展开恰={自己}仍标「指定范围（N=1 亦然）」，不落「仅自己」
        assertThat(vo.getScopeLabel()).isEqualTo("指定范围（1 人）");
    }

    @Test
    void myScope_selfTierHit_labeledSelfOnly() {
        MyScopeQuery query = new MyScopeQuery();
        query.setResource(RESOURCE);
        DataPermDecision decision = new DataPermDecision();
        decision.setAccount("userA");
        decision.setDataScope(com.cloudai.system.api.dataperm.DataScope.of(java.util.Set.of("userA")));
        decision.setColumnScope(com.cloudai.system.api.dataperm.ColumnScope.of(
                java.util.Set.of(), java.util.Set.of()));
        decision.getHitRules().add(hitRule(SysDataPermRule.RowScopeEnum.SELF.getCode()));
        when(evaluator.evaluateForSelf(RESOURCE)).thenReturn(decision);

        MyScopeVo vo = manageService.myScope(query);

        // 「仅自己」专属 SELF 档/无规则命中（终裁序兜底位）
        assertThat(vo.getScopeLabel()).isEqualTo("仅自己");
    }

    @Test
    void myScope_customWithSelf_labeledCustomRange() {
        MyScopeQuery query = new MyScopeQuery();
        query.setResource(RESOURCE);
        DataPermDecision decision = new DataPermDecision();
        decision.setAccount("userA");
        decision.setDataScope(com.cloudai.system.api.dataperm.DataScope.of(java.util.Set.of("userA", "userB")));
        decision.setColumnScope(com.cloudai.system.api.dataperm.ColumnScope.of(
                java.util.Set.of(), java.util.Set.of()));
        decision.getHitRules().add(hitRule(SysDataPermRule.RowScopeEnum.CUSTOM.getCode()));
        when(evaluator.evaluateForSelf(RESOURCE)).thenReturn(decision);

        MyScopeVo vo = manageService.myScope(query);

        // 终裁序第 4 位：CUSTOM 档且集合含自己
        assertThat(vo.getScopeLabel()).isEqualTo("自定义范围（含自己，共 2 人）");
    }

    @Test
    void myScope_customWithoutSelf_labeledDesignatedRange() {
        MyScopeQuery query = new MyScopeQuery();
        query.setResource(RESOURCE);
        DataPermDecision decision = new DataPermDecision();
        decision.setAccount("userA");
        decision.setDataScope(com.cloudai.system.api.dataperm.DataScope.of(java.util.Set.of("userB")));
        decision.setColumnScope(com.cloudai.system.api.dataperm.ColumnScope.of(
                java.util.Set.of(), java.util.Set.of()));
        decision.getHitRules().add(hitRule(SysDataPermRule.RowScopeEnum.CUSTOM.getCode()));
        when(evaluator.evaluateForSelf(RESOURCE)).thenReturn(decision);

        MyScopeVo vo = manageService.myScope(query);

        // 终裁序第 4 位后半：CUSTOM 档展开集不含自己 →「指定范围」
        assertThat(vo.getScopeLabel()).isEqualTo("指定范围（1 人）");
    }

    /** 构造命中规则明细（rowScope 档位码） */
    private DataPermDecision.HitRule hitRule(int rowScope) {
        DataPermDecision.HitRule hit = new DataPermDecision.HitRule();
        hit.setRuleId(9L);
        hit.setSubjectType(0);
        hit.setSubjectName("roleX");
        hit.setRowScope(rowScope);
        return hit;
    }

    @Test
    void myScope_unregisteredResource_rejected3034() {
        MyScopeQuery query = new MyScopeQuery();
        query.setResource("nope");

        BusinessException ex = catchThrowableOfType(() -> manageService.myScope(query), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3034);
        verifyNoInteractions(evaluator);
    }

    // ---- 脚手架 ----

    private DataPermRuleSaveRequest validRequest(String resource) {
        DataPermRuleSaveRequest req = new DataPermRuleSaveRequest();
        req.setResource(resource);
        req.setSubjectType(0);
        req.setSubjectId("2");
        req.setRowScope(2);
        return req;
    }

    private DataPermRuleSaveRequest.ColumnRuleItem columnItem(String columnKey, int action) {
        DataPermRuleSaveRequest.ColumnRuleItem item = new DataPermRuleSaveRequest.ColumnRuleItem();
        item.setColumnKey(columnKey);
        item.setAction(action);
        return item;
    }

    /** 主体=角色 2「主管」启用（subjectType=0 场景公共桩） */
    private void stubRoleSubject() {
        when(roleMapper.findById(2L)).thenReturn(role(2L, "主管"));
    }

    private RuleConfigQuery configQuery() {
        RuleConfigQuery query = new RuleConfigQuery();
        query.setResource(RESOURCE);
        query.setSubjectType(0);
        query.setSubjectId("2");
        return query;
    }

    private Page<SysDataPermRule> pageOf(List<SysDataPermRule> records) {
        Page<SysDataPermRule> page = new Page<>(1, 10);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    private SysDataPermRule rule(long id, int subjectType, long subjectId, int rowScope) {
        SysDataPermRule r = new SysDataPermRule();
        r.setId(id);
        r.setResource(RESOURCE);
        r.setSubjectType(subjectType);
        r.setSubjectId(subjectId);
        r.setRowScope(rowScope);
        return r;
    }

    private SysDataPermColumn column(int subjectType, long subjectId, String columnKey, int action) {
        SysDataPermColumn c = new SysDataPermColumn();
        c.setResource(RESOURCE);
        c.setSubjectType(subjectType);
        c.setSubjectId(subjectId);
        c.setColumnKey(columnKey);
        c.setAction(action);
        return c;
    }

    private SysRole role(long id, String name) {
        SysRole r = new SysRole();
        r.setId(id);
        r.setName(name);
        r.setStatus(SysRole.StatusEnum.NORMAL.getCode());
        return r;
    }

    private SysUser enabledUser() {
        SysUser user = new SysUser();
        user.setAccount("zhang3");
        user.setStatus(SysUser.StatusEnum.NORMAL.getCode());
        return user;
    }
}
