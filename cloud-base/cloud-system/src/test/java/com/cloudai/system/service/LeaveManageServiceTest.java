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
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import com.cloudai.system.api.dataperm.DataScope;
import com.cloudai.system.vo.SysLeaveDetailVo;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.system.vo.UserOptionVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
 * 请假读路径单测（数据权限轮契约 2026-10-10-data-permission-api §4 取代版）：
 * 分页按求值范围（all 豁免/空集短路/白名单 IN）+ 姓名恒返 + 列级动作；详情行级判定 +
 * 3026 IDOR 收口（deny 留痕）；投影派生 status 语义随上轮不变（mapper mock，SQL 正确性靠
 * B8 EXPLAIN+联调）。
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

    // ---- 分页：求值范围三态 + 姓名回填 + 列级动作 ----

    @Test
    void pageList_allScope_returnsDerivedVoRowsAsIs() {
        SysLeaveVo row = vo(5L, "1", 12L);
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of())));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(DataScope.class)))
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

        // 展开空集（如未挂部门用户仅本部门档规则）→ 空页不查库（契约 §4.1）
        assertThat(result.getTotal()).isZero();
        assertThat(result.getRows()).isEmpty();
        verifyNoInteractions(leaveMapper);
    }

    @Test
    void pageList_whitelistScope_passesAccountsToMapper() {
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.of(Set.of("userA", "zhang3")),
                        ColumnScope.of(Set.of(), Set.of())));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(DataScope.class)))
                .thenReturn(pageOf(List.of()));

        service.pageList(query(1, 10));

        // 白名单档：mapper 收到的 scope 即求值终态（XML 展开为 apply_user IN）
        ArgumentCaptor<DataScope> scopeCaptor = ArgumentCaptor.forClass(DataScope.class);
        verify(leaveMapper).pageList(any(Page.class), eq("leave"), scopeCaptor.capture());
        assertThat(scopeCaptor.getValue().isAll()).isFalse();
        assertThat(scopeCaptor.getValue().getAccounts()).containsExactlyInAnyOrder("userA", "zhang3");
    }

    @Test
    void pageList_backfillsNamesAndAppliesColumnActions() {
        SysLeaveVo row = vo(5L, "1", 12L);
        row.setTitle("e2ecurldp-title");
        row.setReason("e2ecurldp-reason");
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of("title"), Set.of("reason"))));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(DataScope.class)))
                .thenReturn(pageOf(List.of(row)));
        when(translationCache.findUserNames(anySet()))
                .thenReturn(Map.of("userA", "UserA Name", "admin", "Admin"));

        PageResult<SysLeaveVo> result = service.pageList(query(1, 10));

        SysLeaveVo out = result.getRows().get(0);
        // 列级：title 隐藏置 null、reason 脱敏 ***；姓名列表恒返（契约 §4.1）
        assertThat(out.getTitle()).isNull();
        assertThat(out.getReason()).isEqualTo("***");
        assertThat(out.getApplyUserName()).isEqualTo("UserA Name");
        assertThat(out.getApproverName()).isEqualTo("Admin");
    }

    @Test
    void pageList_pendingWindowRowReadsZeroAndNullApprovalId() {
        SysLeaveVo row = vo(5L, "0", null);
        when(dataPermEvaluator.evaluate("leave", "list", null))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of())));
        when(leaveMapper.pageList(any(Page.class), eq("leave"), any(DataScope.class)))
                .thenReturn(pageOf(List.of(row)));
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        PageResult<SysLeaveVo> result = service.pageList(query(1, 10));

        assertThat(result.getRows().get(0).getStatus()).isEqualTo("0");
        assertThat(result.getRows().get(0).getApprovalId()).isNull();
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
    void findById_appliesColumnScopeOnVisibleRow() {
        SysLeaveVo row = vo(6L, "1", 12L);
        row.setTitle("t");
        row.setReason("r");
        when(leaveMapper.findById("leave", 6L)).thenReturn(row);
        when(dataPermEvaluator.evaluate("leave", "detail", "6"))
                .thenReturn(decision(DataScope.all(), ColumnScope.of(Set.of(), Set.of("reason"))));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        SysLeaveDetailVo detail = service.findById(6L);

        // 详情列级同列表应用（契约 §4.2）
        assertThat(detail.getLeave().getReason()).isEqualTo("***");
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
        DataPermDecision decision = new DataPermDecision();
        decision.setResource("leave");
        decision.setAccount("userA");
        decision.setOperation("list");
        decision.setDataScope(scope);
        decision.setColumnScope(columnScope);
        return decision;
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
