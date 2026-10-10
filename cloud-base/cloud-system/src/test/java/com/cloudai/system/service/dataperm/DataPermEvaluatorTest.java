package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.domain.LoginUser;
import com.cloudai.common.core.exception.BusinessException;
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

    // ---- 脚手架 ----

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
        SysDataPermRule r = new SysDataPermRule();
        r.setId(id);
        r.setSubjectType(subjectType);
        r.setSubjectId(subjectId);
        r.setRowScope(rowScope);
        r.setCustomAccounts(customAccounts);
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
