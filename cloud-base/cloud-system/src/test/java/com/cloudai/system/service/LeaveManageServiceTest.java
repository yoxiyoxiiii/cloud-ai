package com.cloudai.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.vo.SysLeaveDetailVo;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.system.vo.UserOptionVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 请假读路径单测（契约 2026-10-09-approval-projection-api §4.1 读语义取代版）：
 * 纯本地零 Feign——mapper mock 返回 JOIN 派生 VO（SQL 正确性靠 B7 EXPLAIN+联调，单测覆盖 Service
 * 组装与翻译回填，设计 D11）；纠偏/分批/降级语义随读路径上移框架层整体退役（3022 读路径退役）。
 */
@ExtendWith(MockitoExtension.class)
class LeaveManageServiceTest {

    @Mock
    private SysLeaveMapper leaveMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private TranslationCacheService translationCache;
    @InjectMocks
    private LeaveManageService service;

    // ---- 分页：JOIN 派生直出 ----

    @Test
    void pageListMy_returnsDerivedVoRowsAsIs() {
        SysLeaveVo row = vo(5L, "1", 12L);
        when(leaveMapper.pageList(any(Page.class), eq("leave"), eq("userA")))
                .thenReturn(pageOf(List.of(row)));

        PageResult<SysLeaveVo> result = service.pageListMy(query(1, 10), "userA");

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getStatus()).isEqualTo("1");
        assertThat(result.getRows().get(0).getApprovalId()).isEqualTo(12L);
    }

    @Test
    void pageListMy_pendingWindowRowReadsZeroAndNullApprovalId() {
        // 发起后秒级窗口（投影无行）：JOIN 派生 status=0、approvalId=null——时序窗口属正常语义（契约 §1.5）
        SysLeaveVo row = vo(5L, "0", null);
        when(leaveMapper.pageList(any(Page.class), eq("leave"), eq("userA")))
                .thenReturn(pageOf(List.of(row)));

        PageResult<SysLeaveVo> result = service.pageListMy(query(1, 10), "userA");

        assertThat(result.getRows().get(0).getStatus()).isEqualTo("0");
        assertThat(result.getRows().get(0).getApprovalId()).isNull();
    }

    // ---- 详情：派生读 + 翻译回填 / 3018 ----

    @Test
    void findById_notFoundRejected_3018() {
        when(leaveMapper.findById("leave", 9L)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> service.findById(9L),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3018);
        assertThat(ex.getMessage()).isEqualTo("请假单不存在");
    }

    @Test
    void findById_happyPathDerivedStatus() {
        when(leaveMapper.findById("leave", 5L)).thenReturn(vo(5L, "2", 12L));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        SysLeaveDetailVo detail = service.findById(5L);

        assertThat(detail.getLeave().getStatus()).isEqualTo("2");
        assertThat(detail.getLeave().getApprovalId()).isEqualTo(12L);
    }

    @Test
    void findById_detailLabelsBackfilledFromTranslationCache() {
        // 契约 §9：详情嵌套 {leave: SysLeaveVo} 手动回填（advisor 只扫顶层，不递归包装）；
        // leaveType/status 均 String 键（VO 契约形态，派生读后无 Integer 化转换）
        SysLeaveVo row = vo(6L, "1", 12L);
        row.setLeaveType("3");
        when(leaveMapper.findById("leave", 6L)).thenReturn(row);
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
        // 缓存未命中 → 译文置 null（同 advisor 降级语义），不抛
        when(leaveMapper.findById("leave", 6L)).thenReturn(vo(6L, "0", null));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        SysLeaveDetailVo detail = service.findById(6L);

        assertThat(detail.getLeave().getStatusLabel()).isNull();
        assertThat(detail.getLeave().getApplyUserName()).isNull();
        assertThat(detail.getLeave().getApproverName()).isNull();
    }

    // ---- 审批人投影（§5.5 仅启用）----

    @Test
    void listApprovers_mapsEnabledOptions() {
        SysUser admin = user(1L, "admin", "Admin");
        SysUser other = user(2L, "ghost", "Ghost");
        when(userMapper.listEnabledOptions()).thenReturn(List.of(admin, other));

        List<UserOptionVo> approvers = service.listApprovers();

        // listEnabledOptions 已过滤停用（SQL status=0），此处验证投影映射
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
