package com.cloudai.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataPermColumnContext;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import com.cloudai.system.api.dataperm.DataScope;
import com.cloudai.system.api.dataperm.PredicateOperatorEnum;
import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.api.dataperm.RowGroup;
import com.cloudai.system.api.dataperm.RowPredicate;
import com.cloudai.system.vo.SysLeaveDetailVo;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.system.vo.UserOptionVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 请假读路径单测（数据权限轮契约 §4 + 谓词轮 2026-10-11 增量 + 注解化轮 2026-10-12 适配）：
 * 分页按求值范围（rowAll 豁免/空集短路/组间 OR——条件组透传）+ 姓名恒返 + 列级接线声明断言
 * （D51-D58：Service 求值后仅 DataPermColumnContext.set——resource/columnScope 正确且 VO 不再
 * mutate，列改写归 advice；空集短路前已 set 为红态锚）；详情行级判定升级 allowsDetail（D34 行属性：
 * ALL+条件 owner 无关拒绝/VO String 形态归一）+ 3026 IDOR 收口（deny 留痕）；无条件用例经
 * toRowFilter 脚手架保持 v1 断言语义（mapper mock，SQL 正确性靠 B5 EXPLAIN+联调）。
 */
@ExtendWith(MockitoExtension.class)
class LeaveManageServiceTest {

    @Mock
    private SysLeaveMapper leaveMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private TranslationCacheService translationCache;
    @Mock
    private DataPermEvaluator dataPermEvaluator;
    @InjectMocks
    private LeaveManageService service;

    @AfterEach
    void clearColumnContext() {
        // 求值后即 set：全用例都可能留上下文——防跨用例污染（真 ThreadLocal）
        DataPermColumnContext.clear();
    }

    // ---- 分页：求值范围三态 + 姓名回填 + 列级动作 ----

    @Test
    void pageList_allScope_returnsDerivedVoRowsAsIs() {
        SysLeaveVo row = vo(5L, "1", 12L);
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of())));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(RowFilter.class)))
                .thenReturn(pageOf(List.of(row)));
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        PageResult<SysLeaveVo> result = service.pageList(query(1, 10));

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRows().get(0).getStatus()).isEqualTo("1");
        assertThat(result.getRows().get(0).getApprovalId()).isEqualTo(12L);
    }

    @Test
    void pageList_emptyScope_shortCircuitsWithoutQuery() {
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.of(Set.of()), ColumnScope.of(Set.of(), Set.of())));

        PageResult<SysLeaveVo> result = service.pageList(query(1, 10));

        // 展开空集（如未挂部门用户仅本部门档规则）→ 空页不查库（契约 §4.1）；
        // 空集短路前已接线（set 位置红态锚——空响应无白名单实例不触发 3042，但语义上已声明）
        assertThat(result.getTotal()).isZero();
        assertThat(result.getRows()).isEmpty();
        verifyNoInteractions(leaveMapper);
        assertThat(DataPermColumnContext.get()).isNotNull();
        assertThat(DataPermColumnContext.get().resource()).isEqualTo("leave");
        assertThat(DataPermColumnContext.get().columnScope().isEmpty()).isTrue();
    }

    @Test
    void pageList_whitelistScope_passesAccountsToMapper() {
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.of(Set.of("userA", "zhang3")),
                        ColumnScope.of(Set.of(), Set.of())));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(RowFilter.class)))
                .thenReturn(pageOf(List.of()));

        service.pageList(query(1, 10));

        // 白名单档：mapper 收到的 filter 即求值终态（XML 展开为组骨架 apply_user IN——机械切换，断言语义不变）
        ArgumentCaptor<RowFilter> filterCaptor = ArgumentCaptor.forClass(RowFilter.class);
        verify(leaveMapper).pageList(any(Page.class), eq("leave"), filterCaptor.capture());
        assertThat(filterCaptor.getValue().isRowAll()).isFalse();
        assertThat(filterCaptor.getValue().getGroups().get(0).getAccounts())
                .containsExactlyInAnyOrder("userA", "zhang3");
    }

    @Test
    void pageList_backfillsNamesAndDeclaresColumnScope() {
        SysLeaveVo row = vo(5L, "1", 12L);
        row.setTitle("e2ecurldp-title");
        row.setReason("e2ecurldp-reason");
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of("title"), Set.of("reason"))));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(RowFilter.class)))
                .thenReturn(pageOf(List.of(row)));
        when(translationCache.findUserNames(anySet()))
                .thenReturn(Map.of("userA", "UserA Name", "admin", "Admin"));

        PageResult<SysLeaveVo> result = service.pageList(query(1, 10));

        SysLeaveVo out = result.getRows().get(0);
        // 列级接线声明（D52）：上下文 resource/hidden/masked 正确；VO 在 Service 返回时是原始值
        // （title/reason 不再被 mutate——列改写归 advice 序列化前统一应用）
        assertThat(DataPermColumnContext.get()).isNotNull();
        assertThat(DataPermColumnContext.get().resource()).isEqualTo("leave");
        assertThat(DataPermColumnContext.get().columnScope().getHiddenColumns()).containsExactly("title");
        assertThat(DataPermColumnContext.get().columnScope().getMaskedColumns()).containsExactly("reason");
        assertThat(out.getTitle()).isEqualTo("e2ecurldp-title");
        assertThat(out.getReason()).isEqualTo("e2ecurldp-reason");
        assertThat(out.getApplyUserName()).isEqualTo("UserA Name");
        assertThat(out.getApproverName()).isEqualTo("Admin");
    }

    @Test
    void pageList_pendingWindowRowReadsZeroAndNullApprovalId() {
        SysLeaveVo row = vo(5L, "0", null);
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of())));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(RowFilter.class)))
                .thenReturn(pageOf(List.of(row)));
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        PageResult<SysLeaveVo> result = service.pageList(query(1, 10));

        assertThat(result.getRows().get(0).getStatus()).isEqualTo("0");
        assertThat(result.getRows().get(0).getApprovalId()).isNull();
    }

    // ---- 谓词轮（D33/D34）：条件组透传 + detail 行属性判定 ----

    @Test
    void pageList_conditionalFilter_groupsWithPredicatesPassedToMapper() {
        RowFilter filter = RowFilter.of(List.of(
                RowGroup.of(false, Set.of("userA"), List.of()),
                RowGroup.of(true, Set.of(), List.of(idBetween(1, 100)))));
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decisionOf(filter, ColumnScope.of(Set.of(), Set.of())));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(RowFilter.class)))
                .thenReturn(pageOf(List.of()));

        service.pageList(query(1, 10));

        // 条件组透传：无条件并组在首 + ALL 条件组（含谓词）原样达 mapper（XML 组间 OR 渲染输入）
        ArgumentCaptor<RowFilter> filterCaptor = ArgumentCaptor.forClass(RowFilter.class);
        verify(leaveMapper).pageList(any(Page.class), eq("leave"), filterCaptor.capture());
        assertThat(filterCaptor.getValue().getGroups()).hasSize(2);
        assertThat(filterCaptor.getValue().getGroups().get(0).getPredicates()).isEmpty();
        assertThat(filterCaptor.getValue().getGroups().get(1).isAllAccounts()).isTrue();
        assertThat(filterCaptor.getValue().getGroups().get(1).getPredicates()).hasSize(1);
    }

    // ---- 详情：行级判定 + IDOR 收口 + 翻译回填 ----

    @Test
    void findById_notFoundRejected_3018() {
        when(leaveMapper.findById("leave", 9L)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> service.findById(9L), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3018);
        assertThat(ex.getMessage()).isEqualTo("请假单不存在");
        verifyNoInteractions(dataPermEvaluator);
    }

    @Test
    void findById_outOfScope_rejected3026WithDenyLog() {
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo(5L, "1", 12L));
        when(dataPermEvaluator.evaluate("leave", "detail", "5"))
                .thenReturn(decision(DataScope.of(Set.of("someone-else")), ColumnScope.of(Set.of(), Set.of())));

        BusinessException ex = catchThrowableOfType(() -> service.findById(5L), BusinessException.class);

        // 归属账号不在范围 → 3026 + deny 留痕（IDOR 收口，D13；组件化 D23 三参——测试无登录上下文 account=null）
        assertThat(ex.getCode()).isEqualTo(3026);
        verify(dataPermEvaluator).logDeny(null, "leave", "5");
    }

    @Test
    void findById_whitelistScopeContainingOwner_visible() {
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo(5L, "2", 12L));
        when(dataPermEvaluator.evaluate("leave", "detail", "5"))
                .thenReturn(decision(DataScope.of(Set.of("userA")), ColumnScope.of(Set.of(), Set.of())));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        SysLeaveDetailVo detail = service.findById(5L);

        assertThat(detail.getLeave().getStatus()).isEqualTo("2");
        verify(dataPermEvaluator, org.mockito.Mockito.never()).logDeny(any(), anyString(), anyString());
    }

    @Test
    void findById_allPlusIdPredicate_rowOutOfCondition_rejected3026() {
        SysLeaveVo row = vo(150L, "1", 12L);
        when(leaveMapper.findById("leave", 150L)).thenReturn(row);
        RowFilter filter = RowFilter.of(List.of(
                RowGroup.of(true, Set.of(), List.of(idBetween(1, 100)))));
        when(dataPermEvaluator.evaluate("leave", "detail", "150"))
                .thenReturn(decisionOf(filter, ColumnScope.of(Set.of(), Set.of())));

        BusinessException ex = catchThrowableOfType(() -> service.findById(150L), BusinessException.class);

        // ALL+条件 owner 无关：本人单 id=150 ∉ [1,100] → 谓词不满足 → 组失败 → 3026 + deny 留痕（D34）
        assertThat(ex.getCode()).isEqualTo(3026);
        verify(dataPermEvaluator).logDeny(null, "leave", "150");
    }

    @Test
    void findById_predicateOnVoStringField_coercedAndVisible() {
        SysLeaveVo row = vo(5L, "1", 12L);
        row.setLeaveType("1");
        when(leaveMapper.findById("leave", 5L)).thenReturn(row);
        RowFilter filter = RowFilter.of(List.of(RowGroup.of(true, Set.of(), List.of(
                RowPredicate.of("leaveType", "leave_type", PredicateOperatorEnum.EQ,
                        List.of(new BigDecimal("1")))))));
        when(dataPermEvaluator.evaluate("leave", "detail", "5"))
                .thenReturn(decisionOf(filter, ColumnScope.of(Set.of(), Set.of())));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        SysLeaveDetailVo detail = service.findById(5L);

        // D34 双形态：VO leaveType "1"（投影 CAST 直出 String）与类型化 BigDecimal("1") 归一相等 → 可见
        assertThat(detail.getLeave().getId()).isEqualTo(5L);
        verify(dataPermEvaluator, org.mockito.Mockito.never()).logDeny(any(), anyString(), anyString());
    }

    @Test
    void findById_detailLabelsBackfilledFromTranslationCache() {
        SysLeaveVo row = vo(6L, "1", 12L);
        row.setLeaveType("3");
        when(leaveMapper.findById("leave", 6L)).thenReturn(row);
        when(dataPermEvaluator.evaluate("leave", "detail", "6"))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of())));
        when(translationCache.findDictLabels(eq("system_leave_type"), anySet()))
                .thenReturn(Map.of("3", "Annual"));
        when(translationCache.findDictLabels(eq("bpmn_approval_status"), anySet()))
                .thenReturn(Map.of("1", "Approved"));
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of("userA", "UserA Name", "admin", "Admin"));

        SysLeaveDetailVo detail = service.findById(6L);

        assertThat(detail.getLeave().getLeaveTypeLabel()).isEqualTo("Annual");
        assertThat(detail.getLeave().getStatusLabel()).isEqualTo("Approved");
        assertThat(detail.getLeave().getApplyUserName()).isEqualTo("UserA Name");
        assertThat(detail.getLeave().getApproverName()).isEqualTo("Admin");
    }

    @Test
    void findById_cacheMissLeavesLabelsNull() {
        when(leaveMapper.findById("leave", 6L)).thenReturn(vo(6L, "0", null));
        when(dataPermEvaluator.evaluate("leave", "detail", "6"))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of())));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        SysLeaveDetailVo detail = service.findById(6L);

        assertThat(detail.getLeave().getStatusLabel()).isNull();
        assertThat(detail.getLeave().getApplyUserName()).isNull();
        assertThat(detail.getLeave().getApproverName()).isNull();
    }

    @Test
    void findById_declaresColumnScopeOnVisibleRow() {
        SysLeaveVo row = vo(6L, "1", 12L);
        row.setTitle("t");
        row.setReason("r");
        when(leaveMapper.findById("leave", 6L)).thenReturn(row);
        when(dataPermEvaluator.evaluate("leave", "detail", "6"))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of("reason"))));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        SysLeaveDetailVo detail = service.findById(6L);

        // 详情列级接线声明同列表（契约 §4.2）：上下文 masked=reason；嵌套 leave 原始值（advice 下钻改写）
        assertThat(DataPermColumnContext.get()).isNotNull();
        assertThat(DataPermColumnContext.get().resource()).isEqualTo("leave");
        assertThat(DataPermColumnContext.get().columnScope().getHiddenColumns()).isEmpty();
        assertThat(DataPermColumnContext.get().columnScope().getMaskedColumns()).containsExactly("reason");
        assertThat(detail.getLeave().getReason()).isEqualTo("r");
        assertThat(detail.getLeave().getTitle()).isEqualTo("t");
    }

    // ---- 审批人投影（§5.5 仅启用）----

    @Test
    void listApprovers_mapsEnabledOptions() {
        SysUser admin = user(1L, "admin", "Admin");
        SysUser other = user(2L, "ghost", "Ghost");
        when(userMapper.listEnabledOptions()).thenReturn(List.of(admin, other));

        List<UserOptionVo> approvers = service.listApprovers();

        assertThat(approvers).hasSize(2);
        assertThat(approvers.get(0).getId()).isEqualTo(1L);
        assertThat(approvers.get(0).getAccount()).isEqualTo("admin");
        assertThat(approvers.get(0).getNickname()).isEqualTo("Admin");
    }

    @Test
    void listApprovers_emptyReturnsEmpty() {
        when(userMapper.listEnabledOptions()).thenReturn(List.of());

        assertThat(service.listApprovers()).isEmpty();
        verifyNoInteractions(translationCache);
    }

    // ---- 脚手架 ----

    private DataPermDecision decision(DataScope scope, ColumnScope columnScope) {
        // 无条件 v1 形态经 toRowFilter 等价构造（与求值器 mergeGroups 无条件路径同构）——既有断言语义不变
        DataPermDecision decision = decisionOf(toRowFilter(scope), columnScope);
        decision.setDataScope(scope);
        return decision;
    }

    /** 谓词轮脚手架：rowFilter 显式构造（dataScope 不设——Service 行集判定不再读它） */
    private DataPermDecision decisionOf(RowFilter filter, ColumnScope columnScope) {
        DataPermDecision decision = new DataPermDecision();
        decision.setResource("leave");
        decision.setAccount("userA");
        decision.setOperation("list");
        decision.setRowFilter(filter);
        decision.setColumnScope(columnScope);
        return decision;
    }

    private RowFilter toRowFilter(DataScope scope) {
        if (scope.isAll()) {
            return RowFilter.rowAll();
        }
        if (scope.getAccounts().isEmpty()) {
            return RowFilter.of(List.of());
        }
        return RowFilter.of(List.of(RowGroup.of(false, scope.getAccounts(), List.of())));
    }

    /** id BETWEEN 谓词（NUMBER 类型化值，求值器 enrichment 同形态） */
    private RowPredicate idBetween(long low, long high) {
        return RowPredicate.of("id", "id", PredicateOperatorEnum.BETWEEN,
                List.of(new BigDecimal(low), new BigDecimal(high)));
    }

    private PageQuery query(int pageNum, int pageSize) {
        PageQuery query = new PageQuery();
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return query;
    }

    private Page<SysLeaveVo> pageOf(List<SysLeaveVo> rows) {
        Page<SysLeaveVo> page = new Page<>(1, rows.size());
        page.setRecords(rows);
        page.setTotal(rows.size());
        return page;
    }

    private SysLeaveVo vo(long id, String status, Long approvalId) {
        SysLeaveVo vo = new SysLeaveVo();
        vo.setId(id);
        vo.setStatus(status);
        vo.setApprovalId(approvalId);
        vo.setApplyUser("userA");
        vo.setApprover("admin");
        return vo;
    }

    private SysUser user(Long id, String account, String nickname) {
        SysUser user = new SysUser();
        user.setId(id);
        user.setAccount(account);
        user.setNickname(nickname);
        return user;
    }
}
