package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.api.dataperm.DataPermOperation;
import com.cloudai.system.entity.SysDataPermColumn;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 求值矩阵单测（设计 §10.1 全清单）：多规则并集收敛（D3）/ 短路 / 部门展开（D1/D4）/
 * CUSTOM 坏配置降级 / 列宽松者胜 / 留痕不阻断（D7）——mapper 全 mock，SQL 正确性靠 B8 EXPLAIN+联调。
 */
@ExtendWith(MockitoExtension.class)
class DataPermEvaluatorTest {

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
    private SysDeptMapper deptMapper;
    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();
    @Mock
    private com.cloudai.common.translate.core.TranslationCacheService translationCacheService;
    @InjectMocks
    private DataPermEvaluator evaluator;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void loginAs(String account, long userId) {
        LoginUser loginUser = new LoginUser();
        loginUser.setAccount(account);
        loginUser.setUserId(userId);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null));
    }

    // ---- 行级：默认档 / 短路 / 并集 ----

    @Test
    void evaluate_noRules_defaultsToSelfScope() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        assertThat(decision.getDataScope().isAll()).isFalse();
        assertThat(decision.getDataScope().getAccounts()).containsExactly("userA");
        assertThat(decision.getHitRules()).isEmpty();
        assertThat(decision.getNarratives()).contains("无规则命中，默认仅自己");
        // 真实决策恒留痕：无规则 → ruleIds/ruleDigest null、scopeSummary=accounts=1
        verify(logMapper).save(any(com.cloudai.system.entity.SysDataPermLog.class));
    }

    @Test
    void evaluate_roleAll_shortCircuitsToAllScope() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        when(ruleMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(rule(3L, 0, 2L, SysDataPermRule.RowScopeEnum.ALL.getCode(), null)));
        when(roleMapper.findById(2L)).thenReturn(role(2L, "主管"));
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        assertThat(decision.getDataScope().isAll()).isTrue();
        assertThat(decision.getHitRules()).hasSize(1);
        assertThat(decision.getHitRules().get(0).getSubjectName()).isEqualTo("主管");
    }

    @Test
    void evaluate_roleSelfPlusUserCustom_unionsAccounts() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        when(ruleMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(rule(3L, 0, 2L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null)));
        when(roleMapper.findById(2L)).thenReturn(role(2L, "主管"));
        when(ruleMapper.listByUserId(RESOURCE, 5L))
                .thenReturn(List.of(rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.CUSTOM.getCode(),
                        "[\"zhang3\",\"lisi4\"]")));

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 并集（D3 宽松者胜）：SELF {userA} ∪ CUSTOM {zhang3,lisi4}
        assertThat(decision.getDataScope().getAccounts()).containsExactlyInAnyOrder("userA", "zhang3", "lisi4");
        assertThat(decision.getHitRules()).hasSize(2);
    }

    // ---- 行级：部门档展开 ----

    @Test
    void evaluate_deptScope_withDept_expandsMembers() {
        loginAs("userA", 5L);
        stubSelfUser(5L, 10L);
        stubSingleRule(SysDataPermRule.RowScopeEnum.DEPT.getCode(), null);
        when(userMapper.listEnabledAccountsByDeptIds(List.of(10L))).thenReturn(List.of("userA", "peerB"));

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        assertThat(decision.getDataScope().getAccounts()).containsExactlyInAnyOrder("userA", "peerB");
        assertThat(decision.getHitRules().get(0).getExpandedCount()).isEqualTo(2);
    }

    @Test
    void evaluate_deptScope_withoutDept_expandsEmptyAndSkipsMemberQuery() {
        loginAs("userA", 5L);
        stubSelfUser(5L, null);
        stubSingleRule(SysDataPermRule.RowScopeEnum.DEPT.getCode(), null);

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 无部门 → 空集（D3）不炸读路径；且不触发成员查询
        assertThat(decision.getDataScope().isEmptyScope()).isTrue();
        verify(userMapper, never()).listEnabledAccountsByDeptIds(anyList());
    }

    @Test
    void evaluate_deptAndChild_includesWholeSubtree() {
        loginAs("userA", 5L);
        stubSelfUser(5L, 10L);
        stubSingleRule(SysDataPermRule.RowScopeEnum.DEPT_AND_CHILD.getCode(), null);
        when(deptMapper.listAll()).thenReturn(List.of(
                dept(10L, 0L), dept(11L, 10L), dept(12L, 11L), dept(13L, 99L)));
        when(userMapper.listEnabledAccountsByDeptIds(List.of(10L, 11L, 12L))).thenReturn(List.of("userA", "subC"));

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 子树 = 本部门 + 全部子孙（13 的父 99 不在树内不含）；成员查询 deptIds=[10,11,12]
        assertThat(decision.getDataScope().getAccounts()).containsExactlyInAnyOrder("userA", "subC");
        verify(userMapper).listEnabledAccountsByDeptIds(List.of(10L, 11L, 12L));
    }

    @Test
    void evaluate_customBadJson_degradesToEmptySet() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of(
                rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.CUSTOM.getCode(), "not-a-json")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 坏配置降级空集（D3），不抛
        assertThat(decision.getDataScope().isEmptyScope()).isTrue();
    }

    // ---- 列级：宽松者胜 ----

    @Test
    void evaluate_columnMerge_roleHiddenUserMasked_maskedWins() {
        loginAs("userA", 5L);
        stubNoRowRules();
        when(columnMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(column(0, 2L, "reason", SysDataPermColumn.ActionEnum.HIDDEN.getCode())));
        when(columnMapper.listByUserId(RESOURCE, 5L))
                .thenReturn(List.of(column(1, 5L, "reason", SysDataPermColumn.ActionEnum.MASKED.getCode())));

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 同列冲突取最宽松（可视 > 脱敏 > 隐藏，D3）：HIDDEN+MASKED → MASKED
        assertThat(decision.getColumnScope().isMasked("reason")).isTrue();
        assertThat(decision.getColumnScope().isHidden("reason")).isFalse();
        assertThat(decision.getColumnScope().mask("真实事由")).isEqualTo("***");
    }

    @Test
    void evaluate_columnHiddenOnly_staysHidden() {
        loginAs("userA", 5L);
        stubNoRowRules();
        when(columnMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(column(0, 2L, "title", SysDataPermColumn.ActionEnum.HIDDEN.getCode())));

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        assertThat(decision.getColumnScope().isHidden("title")).isTrue();
        assertThat(decision.getColumnScope().isEmpty()).isFalse();
    }

    // ---- 校验与可观察 ----

    @Test
    void evaluate_unknownResource_rejected3034() {
        loginAs("userA", 5L);

        BusinessException ex = catchThrowableOfType(
                () -> evaluator.evaluate("not-registered", DataPermOperation.LIST, null),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3034);
        // 注册表校验前置：未触任何 mapper（含留痕）
        verifyNoInteractions(logMapper);
    }

    @Test
    void evaluate_logInsertFailure_doesNotAffectResult() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
        doThrow(new RuntimeException("db down")).when(logMapper).save(any());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 留痕失败 catch log.error 不抛（D7：可观察组件不得成为读路径故障源）
        assertThat(decision.getDataScope().getAccounts()).containsExactly("userA");
    }

    @Test
    void explain_neverPersistsLog() {
        SysUser target = new SysUser();
        target.setId(5L);
        target.setAccount("userA");
        target.setStatus(0);
        when(userMapper.findByAccount("userA")).thenReturn(target);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.explain("userA", RESOURCE);

        // 模拟非真实决策：不留痕（D7）、operation 置空
        assertThat(decision.getOperation()).isNull();
        assertThat(decision.getAccount()).isEqualTo("userA");
        verifyNoInteractions(logMapper);
    }

    @Test
    void explain_targetUserInvalid_rejected3032() {
        when(userMapper.findByAccount("ghost")).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> evaluator.explain("ghost", RESOURCE),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3032);
    }

    // ---- evaluateFor：账号显式真实决策（组件化 D20/D23，/inner/data-perm 薄壳底层） ----

    @Test
    void evaluateFor_blankAccount_rejected1002() {
        BusinessException ex = catchThrowableOfType(
                () -> evaluator.evaluateFor(" ", RESOURCE, DataPermOperation.LIST, null),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(1002);
        verifyNoInteractions(userMapper, ruleMapper, logMapper);
    }

    @Test
    void evaluateFor_unknownAccount_convergesToSelfScopeAndLogs() {
        when(userMapper.findByAccount("ghost")).thenReturn(null);

        DataPermDecision decision = evaluator.evaluateFor("ghost", RESOURCE, DataPermOperation.DETAIL, "5");

        // 不存在账号不报错：userId=null → 无规则默认 SELF={account}（方向安全不越权，D20）+ 恒留痕（D7）
        assertThat(decision.getDataScope().getAccounts()).containsExactly("ghost");
        assertThat(decision.getAccount()).isEqualTo("ghost");
        assertThat(decision.getOperation()).isEqualTo("detail");
        verify(logMapper).save(any(com.cloudai.system.entity.SysDataPermLog.class));
    }

    @Test
    void evaluateFor_disabledAccount_convergesSameAsUnknown() {
        SysUser disabled = new SysUser();
        disabled.setId(9L);
        disabled.setAccount("off-user");
        disabled.setStatus(1);
        when(userMapper.findByAccount("off-user")).thenReturn(disabled);

        DataPermDecision decision = evaluator.evaluateFor("off-user", RESOURCE, DataPermOperation.LIST, null);

        // 停用账号同收敛：userId=null 不触角色/用户规则查询（fail-safe 方向）
        assertThat(decision.getDataScope().getAccounts()).containsExactly("off-user");
        verifyNoInteractions(ruleMapper);
    }

    @Test
    void evaluateFor_validAccount_resolvesUserIdAndPersistsLog() {
        SysUser target = new SysUser();
        target.setId(5L);
        target.setAccount("userA");
        target.setStatus(0);
        when(userMapper.findByAccount("userA")).thenReturn(target);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        when(ruleMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(rule(3L, 0, 2L, SysDataPermRule.RowScopeEnum.ALL.getCode(), null)));
        when(roleMapper.findById(2L)).thenReturn(role(2L, "主管"));
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluateFor("userA", RESOURCE, DataPermOperation.LIST, null);

        // 账号显式路径与登录态路径同收敛：userId 解析后正常命中角色规则
        assertThat(decision.getDataScope().isAll()).isTrue();
        verify(logMapper).save(any(com.cloudai.system.entity.SysDataPermLog.class));
    }

    // ---- logDeny 三参（组件化 D23 账号显式化，行为等价） ----

    @Test
    void logDeny_threeArgs_persistsDenyRowWithExplicitAccount() {
        evaluator.logDeny("userB", RESOURCE, "5");

        // deny 行要素：operation=deny、scopeSummary 固定 deny、账号=显式入参（不再内部取登录态）
        verify(logMapper).save(org.mockito.ArgumentMatchers.argThat(row ->
                "userB".equals(row.getAccount()) && "deny".equals(row.getOperation())
                        && "deny".equals(row.getScopeSummary()) && "5".equals(row.getBusinessKey())));
    }

    // ---- 谓词轮代数（D31/D33/D36/D39，2026-10-11）：无条件零 diff + 条件建组 + 降级整规则不授权 ----

    @Test
    void evaluate_unconditionalRule_rowFilterSingleGroupEquivalence() {
        loginAs("userA", 5L);
        stubSingleRule(SysDataPermRule.RowScopeEnum.SELF.getCode(), null);

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 零 diff 证明：无条件 SELF → 单组 (apply_user IN (userA))，与 v1 dataScope 语义逐字等价
        assertThat(decision.getRowFilter().isRowAll()).isFalse();
        assertThat(decision.getRowFilter().getGroups()).hasSize(1);
        assertThat(decision.getRowFilter().getGroups().get(0).isAllAccounts()).isFalse();
        assertThat(decision.getRowFilter().getGroups().get(0).getAccounts()).containsExactly("userA");
        assertThat(decision.getRowFilter().getGroups().get(0).getPredicates()).isEmpty();
        assertThat(decision.getRowFilter().isEmptyScope()).isFalse();
    }

    @Test
    void evaluate_allUnconditional_rowFilterRowAll() {
        loginAs("userA", 5L);
        stubSingleRule(SysDataPermRule.RowScopeEnum.ALL.getCode(), null);

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 豁免态：rowAll 短路 + dataScope 保持 all() 既有取值（零 diff 红线记档）
        assertThat(decision.getRowFilter().isRowAll()).isTrue();
        assertThat(decision.getRowFilter().getGroups()).isEmpty();
        assertThat(decision.getDataScope().isAll()).isTrue();
    }

    @Test
    void evaluate_twoUnconditionalRules_mergedIntoSingleGroup() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        when(ruleMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(rule(3L, 0, 2L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null)));
        when(roleMapper.findById(2L)).thenReturn(role(2L, "主管"));
        when(ruleMapper.listByUserId(RESOURCE, 5L))
                .thenReturn(List.of(rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.CUSTOM.getCode(),
                        "[\"zhang3\",\"lisi4\"]")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // D31 ②：两条无条件规则并成单组（union 账号、无谓词）——多规则不产生多组
        assertThat(decision.getRowFilter().getGroups()).hasSize(1);
        assertThat(decision.getRowFilter().getGroups().get(0).getAccounts())
                .containsExactlyInAnyOrder("userA", "zhang3", "lisi4");
        assertThat(decision.getRowFilter().getGroups().get(0).getPredicates()).isEmpty();
    }

    @Test
    void evaluate_allPlusConditional_notExemptDataScopeEmptyButRowFilterAlive() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        when(ruleMapper.listByRoleIds(RESOURCE, List.of(2L))).thenReturn(List.of(
                rule(3L, 0, 2L, SysDataPermRule.RowScopeEnum.ALL.getCode(), null,
                        "[{\"field\":\"id\",\"operator\":\"BETWEEN\",\"values\":[\"1\",\"100\"]}]")));
        when(roleMapper.findById(2L)).thenReturn(role(2L, "主管"));
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
        when(columnMapper.listByRoleIds(RESOURCE, List.of(2L))).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // ALL+条件 ≠ 豁免：dataScope 账号维并集空（ALL 档展开恒空）但行集非空（allAccounts 组）
        assertThat(decision.getDataScope().isAll()).isFalse();
        assertThat(decision.getDataScope().isEmptyScope()).isTrue();
        assertThat(decision.getRowFilter().isRowAll()).isFalse();
        assertThat(decision.getRowFilter().isEmptyScope()).isFalse();
        assertThat(decision.getRowFilter().getGroups()).hasSize(1);
        assertThat(decision.getRowFilter().getGroups().get(0).isAllAccounts()).isTrue();
        assertThat(decision.getRowFilter().getGroups().get(0).getPredicates()).hasSize(1);
        assertThat(decision.getNarratives()).contains(
                "行范围收敛：并集含条件过滤（1 条规则，1 条含条件）");
    }

    @Test
    void evaluate_selfPlusConditional_separateGroupsWithUnion() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        when(ruleMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(rule(3L, 0, 2L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null)));
        when(roleMapper.findById(2L)).thenReturn(role(2L, "主管"));
        when(ruleMapper.listByUserId(RESOURCE, 5L))
                .thenReturn(List.of(rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.CUSTOM.getCode(),
                        "[\"zhang3\"]",
                        "[{\"field\":\"leaveType\",\"operator\":\"EQ\",\"values\":[\"1\"]}]")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 混合：无条件并组在首（{userA}）+ 条件组次（{zhang3}∧leaveType=1）；账号维并集=2
        assertThat(decision.getRowFilter().getGroups()).hasSize(2);
        assertThat(decision.getRowFilter().getGroups().get(0).getAccounts()).containsExactly("userA");
        assertThat(decision.getRowFilter().getGroups().get(0).getPredicates()).isEmpty();
        assertThat(decision.getRowFilter().getGroups().get(1).getAccounts()).containsExactly("zhang3");
        assertThat(decision.getRowFilter().getGroups().get(1).getPredicates()).hasSize(1);
        assertThat(decision.getDataScope().getAccounts()).containsExactlyInAnyOrder("userA", "zhang3");
    }

    @Test
    void evaluate_conditionalDeadGroup_dropped() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of(
                // CUSTOM 档 custom_accounts 坏数据（历史脏数据）→ 展开空 + 条件 → 死组丢弃（D31 ③）
                rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.CUSTOM.getCode(), "not-a-json",
                        "[{\"field\":\"leaveType\",\"operator\":\"EQ\",\"values\":[\"1\"]}]")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        assertThat(decision.getRowFilter().isEmptyScope()).isTrue();
        assertThat(decision.getRowFilter().getGroups()).isEmpty();
    }

    @Test
    void evaluate_predicateBadJson_ruleNotAuthorizing() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of(
                rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null, "not-a-json")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // D39 fail-closed：条件组 JSON 坏 → 该规则整体不授权（即使档位本是 SELF 也不放行自己）
        assertThat(decision.getRowFilter().isEmptyScope()).isTrue();
        assertThat(decision.getHitRules()).hasSize(1);
        assertThat(decision.getHitRules().get(0).getPredicates()).isEmpty();
        verify(logMapper).save(org.mockito.ArgumentMatchers.argThat(row ->
                "empty".equals(row.getScopeSummary())));
    }

    @Test
    void evaluate_predicateUnknownField_ruleNotAuthorizing() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of(
                rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null,
                        "[{\"field\":\"ghostField\",\"operator\":\"EQ\",\"values\":[\"1\"]}]")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // 越界字段（注册表收缩遗留）→ 整规则不授权（fail-closed，无放行分支）
        assertThat(decision.getRowFilter().isEmptyScope()).isTrue();
        // HitRule 仍原样回显（explain 可排查）
        assertThat(decision.getHitRules().get(0).getPredicates()).hasSize(1);
        assertThat(decision.getHitRules().get(0).getPredicates().get(0).getField()).isEqualTo("ghostField");
    }

    @Test
    void evaluate_predicateBadValue_ruleNotAuthorizing() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of(
                rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null,
                        "[{\"field\":\"leaveType\",\"operator\":\"EQ\",\"values\":[\"abc\"]}]")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // NUMBER 列值非纯十进制 → normalize 失败 → 整规则不授权（不部分采用：组内 AND 部分采用即放大）
        assertThat(decision.getRowFilter().isEmptyScope()).isTrue();
    }

    @Test
    void evaluate_predicates_typedDuringEnrichment() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of(
                rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null,
                        "[{\"field\":\"startDate\",\"operator\":\"GTE\",\"values\":[\"2026-01-01\"]},"
                                + "{\"field\":\"id\",\"operator\":\"IN\",\"values\":[\"1\",\"2\"]}]")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // enrichment 类型化：DATE→LocalDate、NUMBER→BigDecimal（SQL 绑定与内存判定同源 D33）
        List<com.cloudai.system.api.dataperm.RowPredicate> predicates =
                decision.getRowFilter().getGroups().get(0).getPredicates();
        assertThat(predicates).hasSize(2);
        assertThat(predicates.get(0).getColumn()).isEqualTo("start_date");
        assertThat(predicates.get(0).getValues().get(0))
                .isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(predicates.get(1).getColumn()).isEqualTo("id");
        assertThat(predicates.get(1).getValues())
                .containsExactly(new java.math.BigDecimal("1"), new java.math.BigDecimal("2"));
    }

    @Test
    void evaluate_observability_digestAndNarrativesAndSummary() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of(
                rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null,
                        "[{\"field\":\"id\",\"operator\":\"BETWEEN\",\"values\":[\"1\",\"100\"]},"
                                + "{\"field\":\"leaveType\",\"operator\":\"EQ\",\"values\":[\"1\"]}]")));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // D36：digest ∧ 段（BETWEEN id:1~100；EQ leave_type=1）+ cond=K + 每条件一句 narrative
        verify(logMapper).save(org.mockito.ArgumentMatchers.argThat(row ->
                row.getRuleDigest().contains("∧id:1~100;leave_type=1")
                        && "accounts=1;cond=1".equals(row.getScopeSummary())));
        assertThat(decision.getNarratives()).contains("命中条件：单号 介于 1~100", "命中条件：请假类型 等于 1");
    }

    // ---- 操作符扩展轮（D41/D43/D44/D45，2026-10-11）：新操作符 enrichment/digest 符号/narratives 译文 ----

    @Test
    void evaluate_newOperators_enrichedTyped() {
        loginAs("userA", 5L);
        stubConditionalRule("[{\"field\":\"id\",\"operator\":\"GT\",\"values\":[\"100\"]},"
                + "{\"field\":\"endDate\",\"operator\":\"LT\",\"values\":[\"2026-12-31\"]},"
                + "{\"field\":\"leaveType\",\"operator\":\"NE\",\"values\":[\"2\"]},"
                + "{\"field\":\"title\",\"operator\":\"PREFIX\",\"values\":[\"e2e\"]}]");

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // enrichment 类型化对新操作符零分叉：GT/LT/NE/PREFIX 值同走列类型归一器（NUMBER→BigDecimal、
        // DATE→LocalDate、STRING 原串）；title（D42 新列）注册表定位正常
        List<com.cloudai.system.api.dataperm.RowPredicate> predicates =
                decision.getRowFilter().getGroups().get(0).getPredicates();
        assertThat(predicates).hasSize(4);
        assertThat(predicates.get(0).getValues().get(0)).isEqualTo(new java.math.BigDecimal("100"));
        assertThat(predicates.get(1).getValues().get(0)).isEqualTo(java.time.LocalDate.of(2026, 12, 31));
        assertThat(predicates.get(2).getValues().get(0)).isEqualTo(new java.math.BigDecimal("2"));
        assertThat(predicates.get(3).getColumn()).isEqualTo("title");
        assertThat(predicates.get(3).getValues().get(0)).isEqualTo("e2e");
    }

    @Test
    void evaluate_observability_digestNewSymbols() {
        loginAs("userA", 5L);
        stubConditionalRule("[{\"field\":\"id\",\"operator\":\"GT\",\"values\":[\"100\"]},"
                + "{\"field\":\"endDate\",\"operator\":\"LT\",\"values\":[\"2026-12-31\"]},"
                + "{\"field\":\"leaveType\",\"operator\":\"NE\",\"values\":[\"2\"]},"
                + "{\"field\":\"title\",\"operator\":\"PREFIX\",\"values\":[\"e2e\"]}]");

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // D41 新四符号（契约 §1.6 C5）：> < != ^——值一律原值不翻译（digest 机器面向）；
        // ∧ 前缀单次，多谓词分号连接（既有形态）
        verify(logMapper).save(org.mockito.ArgumentMatchers.argThat(row ->
                row.getRuleDigest().contains(
                        "∧id>100;end_date<2026-12-31;leave_type!=2;title^e2e")));
        assertThat(decision.getHitRules().get(0).getPredicates()).hasSize(4);
    }

    @Test
    void evaluate_narratives_dictTranslated_digestOriginal() {
        loginAs("userA", 5L);
        stubConditionalRule("[{\"field\":\"leaveType\",\"operator\":\"EQ\",\"values\":[\"1\"]}]");
        when(translationCacheService.findDictLabels("system_leave_type", List.of("1")))
                .thenReturn(java.util.Map.of("1", "事假"));

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // D45 双面：narratives 值译标签（请假类型 等于 事假）；digest 原值（leave_type=1）
        assertThat(decision.getNarratives()).contains("命中条件：请假类型 等于 事假");
        verify(logMapper).save(org.mockito.ArgumentMatchers.argThat(row ->
                row.getRuleDigest().contains("∧leave_type=1")));
    }

    @Test
    void evaluate_narratives_missingTranslation_fallsBackOriginal() {
        loginAs("userA", 5L);
        stubConditionalRule("[{\"field\":\"leaveType\",\"operator\":\"EQ\",\"values\":[\"1\"]}]");
        // mock 未打桩 → findDictLabels 返回空 map → 回退原值（译文缺失路径）

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        assertThat(decision.getNarratives()).contains("命中条件：请假类型 等于 1");
    }

    @Test
    void evaluate_narratives_nonDictColumn_neverCallsResolver() {
        loginAs("userA", 5L);
        stubConditionalRule("[{\"field\":\"title\",\"operator\":\"PREFIX\",\"values\":[\"e2e\"]}]");

        DataPermDecision decision = evaluator.evaluate(RESOURCE, DataPermOperation.LIST, null);

        // dictKey=null 列不触 resolver（businessType/title 原值守恒的机制面）
        verify(translationCacheService, never()).findDictLabels(any(), any());
        assertThat(decision.getNarratives()).contains("命中条件：标题 前缀 e2e");
    }

    // ---- 列级-only 资源（2026-10-22 列级数据权限轮 D60）：行段短路 all + 列规则正常收敛 ----

    @Test
    void evaluate_columnOnly_rowRulesIgnored_rowSegmentAll() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        // 列规则正常收敛：角色脱敏 + 用户隐藏 → 宽松者胜（脱敏 > 隐藏）
        when(columnMapper.listByRoleIds(DataPermResources.USER, List.of(2L)))
                .thenReturn(List.of(column(0, 2L, "mobile", SysDataPermColumn.ActionEnum.MASKED.getCode())));
        when(columnMapper.listByUserId(DataPermResources.USER, 5L))
                .thenReturn(List.of(column(1, 5L, "mobile", SysDataPermColumn.ActionEnum.HIDDEN.getCode())));

        DataPermDecision decision = evaluator.evaluate(DataPermResources.USER, DataPermOperation.LIST, null);

        // 行段：配了行规则也不参与（ruleMapper 零交互）——dataScope 恒 all、rowFilter 恒 rowAll、行规则不收集
        assertThat(decision.getDataScope().isAll()).isTrue();
        assertThat(decision.getRowFilter().isRowAll()).isTrue();
        assertThat(decision.getHitRules()).isEmpty();
        verifyNoInteractions(ruleMapper);
        // 列段：并集宽松者胜
        assertThat(decision.getColumnScope().isMasked("mobile")).isTrue();
        assertThat(decision.getColumnScope().isHidden("mobile")).isFalse();
        // narrative 含「仅列级生效」句
        assertThat(decision.getNarratives()).contains("该资源仅列级权限生效，行范围不适用");
        // 留痕：scope_summary 恒 all、行规则摘要空（rule_digest 不出现行规则）
        verify(logMapper).save(org.mockito.ArgumentMatchers.argThat(row ->
                "all".equals(row.getScopeSummary()) && row.getRuleDigest().isEmpty()
                        && "mobile:脱敏".equals(row.getColumnSummary())));
    }

    @Test
    void evaluate_columnOnly_withoutRules_rowSegmentStillAll() {
        loginAs("userA", 5L);
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(columnMapper.listByUserId(DataPermResources.USER, 5L)).thenReturn(List.of());

        DataPermDecision decision = evaluator.evaluate(DataPermResources.USER, DataPermOperation.DETAIL, "1");

        // 无规则无列动作：行段仍恒 all（不落「默认仅自己」——行级不适用），列空 scope
        assertThat(decision.getDataScope().isAll()).isTrue();
        assertThat(decision.getRowFilter().isRowAll()).isTrue();
        assertThat(decision.getColumnScope().isEmpty()).isTrue();
        assertThat(decision.getNarratives()).contains("该资源仅列级权限生效，行范围不适用")
                .doesNotContain("无规则命中，默认仅自己");
    }

    // ---- 脚手架 ----

    /** 扩展轮脚手架：单条用户直绑 SELF+条件规则（条件 JSON 直传落库形态） */
    private void stubConditionalRule(String predicates) {
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of(
                rule(7L, 1, 5L, SysDataPermRule.RowScopeEnum.SELF.getCode(), null, predicates)));
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
    }

    private void stubSelfUser(long userId, Long deptId) {
        SysUser self = new SysUser();
        self.setId(userId);
        self.setAccount("userA");
        self.setDeptId(deptId);
        self.setStatus(0);
        when(userMapper.findById(userId)).thenReturn(self);
    }

    /** 单条角色规则命中（roleId=2「主管」），无用户直绑规则与列规则 */
    private void stubSingleRule(int rowScope, String customAccounts) {
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        when(ruleMapper.listByRoleIds(RESOURCE, List.of(2L)))
                .thenReturn(List.of(rule(3L, 0, 2L, rowScope, customAccounts)));
        when(roleMapper.findById(2L)).thenReturn(role(2L, "主管"));
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
        when(columnMapper.listByRoleIds(RESOURCE, List.of(2L))).thenReturn(List.of());
        when(columnMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
    }

    /** 无行规则（列规则用例脚手架）：角色 2 命中但无行规则 */
    private void stubNoRowRules() {
        when(roleMapper.listEnabledRoleIdsByUserId(5L)).thenReturn(List.of(2L));
        when(ruleMapper.listByRoleIds(RESOURCE, List.of(2L))).thenReturn(List.of());
        when(ruleMapper.listByUserId(RESOURCE, 5L)).thenReturn(List.of());
    }

    private SysDataPermRule rule(long id, int subjectType, long subjectId, int rowScope, String customAccounts) {
        return rule(id, subjectType, subjectId, rowScope, customAccounts, null);
    }

    /** 谓词轮脚手架：条件组 JSON 直传（落库形态——坏 JSON/越界字段即 D39 降级用例入参） */
    private SysDataPermRule rule(long id, int subjectType, long subjectId, int rowScope,
            String customAccounts, String predicates) {
        SysDataPermRule r = new SysDataPermRule();
        r.setId(id);
        r.setSubjectType(subjectType);
        r.setSubjectId(subjectId);
        r.setRowScope(rowScope);
        r.setCustomAccounts(customAccounts);
        r.setPredicates(predicates);
        return r;
    }

    private SysDataPermColumn column(int subjectType, long subjectId, String columnKey, int action) {
        SysDataPermColumn c = new SysDataPermColumn();
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
        return r;
    }

    private SysDept dept(long id, long parentId) {
        SysDept d = new SysDept();
        d.setId(id);
        d.setParentId(parentId);
        return d;
    }
}
