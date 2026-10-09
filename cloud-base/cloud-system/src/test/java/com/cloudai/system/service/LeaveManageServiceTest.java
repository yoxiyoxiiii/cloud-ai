package com.cloudai.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.entity.SysLeave.StatusEnum;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.system.vo.UserOptionVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 请假读路径单测（契约 2026-10-08-approval-platform-api §5.2/§5.3/§5.5）：
 * 状态纠偏回写（快照≠真相才 UPDATE）/ 分批铁律（201 键 → 3 批 100+100+1，§4.2 单批 ≤100）/
 * 分页 Feign 失败降级快照不抛 / 详情 Feign 失败诚实抛 3022 / 审批人投影仅启用。
 */
@ExtendWith(MockitoExtension.class)
class LeaveManageServiceTest {

    @Mock
    private SysLeaveMapper leaveMapper;
    @Mock
    private SysUserMapper userMapper;
    @Mock
    private BpmnApprovalClient approvalClient;
    @Mock
    private TranslationCacheService translationCache;
    @InjectMocks
    private LeaveManageService service;

    // ---- 纠偏回写 ----

    @Test
    void pageListMy_mismatchWritesBackAndRefreshesVo() {
        SysLeave row = leave(5L, StatusEnum.APPROVING.getCode(), 12L);
        when(leaveMapper.pageList(any(Page.class), eq("userA"))).thenReturn(pageOf(List.of(row)));
        when(approvalClient.statusList(any())).thenReturn(R.ok(List.of(statusVo("5", "1"))));

        PageResult<SysLeaveVo> result = service.pageListMy(query(1, 10), "userA");

        verify(leaveMapper).updateStatusById(eq(5L), eq(StatusEnum.APPROVED.getCode()), eq("userA"), any());
        assertThat(result.getRows()).hasSize(1);
        // 内存行同步刷新：出参 VO 即时反映真相
        assertThat(result.getRows().get(0).getStatus()).isEqualTo("1");
    }

    @Test
    void pageListMy_consistentSnapshotZeroWrite() {
        SysLeave row = leave(5L, StatusEnum.APPROVING.getCode(), 12L);
        when(leaveMapper.pageList(any(Page.class), eq("userA"))).thenReturn(pageOf(List.of(row)));
        when(approvalClient.statusList(any())).thenReturn(R.ok(List.of(statusVo("5", "0"))));

        PageResult<SysLeaveVo> result = service.pageListMy(query(1, 10), "userA");

        // 快照=真相 → 零写
        verify(leaveMapper, never()).updateStatusById(anyLong(), anyInt(), anyString(), any());
        assertThat(result.getRows().get(0).getStatus()).isEqualTo("0");
    }

    @Test
    void pageListMy_rowWithoutApprovalIdSkipsFeign() {
        // 发起失败回滚遗留行（approvalId=null）无可纠偏 → 不发起 Feign、不写
        SysLeave row = leave(5L, StatusEnum.APPROVING.getCode(), null);
        when(leaveMapper.pageList(any(Page.class), eq("userA"))).thenReturn(pageOf(List.of(row)));

        PageResult<SysLeaveVo> result = service.pageListMy(query(1, 10), "userA");

        verify(approvalClient, never()).statusList(any());
        assertThat(result.getRows().get(0).getStatus()).isEqualTo("0");
    }

    @Test
    void pageListMy_failedTerminalRowUntouchedByReconcile() {
        // MQ 形态（契约 2026-10-09 §2.2）：status=4 发起失败终态行 approvalId=null，天然跳过纠偏键集——
        // 混排场景仅正常行发起 Feign，4 行零写不被任何真相覆盖
        SysLeave failed = leave(4L, StatusEnum.FAILED.getCode(), null);
        SysLeave approving = leave(5L, StatusEnum.APPROVING.getCode(), 12L);
        when(leaveMapper.pageList(any(Page.class), eq("userA"))).thenReturn(pageOf(List.of(failed, approving)));
        when(approvalClient.statusList(any())).thenReturn(R.ok(List.of(statusVo("5", "1"))));

        PageResult<SysLeaveVo> result = service.pageListMy(query(1, 10), "userA");

        // 仅审批中行的键进纠偏批（4 行 approvalId=null 不进键集）
        ArgumentCaptor<ApprovalStatusQueryInnerRequest> captor = ArgumentCaptor.forClass(ApprovalStatusQueryInnerRequest.class);
        verify(approvalClient).statusList(captor.capture());
        assertThat(captor.getValue().getBusinessKeys()).containsExactly("5");
        verify(leaveMapper).updateStatusById(eq(5L), eq(StatusEnum.APPROVED.getCode()), eq("userA"), any());
        verify(leaveMapper, never()).updateStatusById(eq(4L), anyInt(), anyString(), any());
        assertThat(result.getRows().get(0).getStatus()).isEqualTo("4");
    }

    // ---- 分批铁律（§4.2 单批 ≤100）----

    @Test
    void pageListMy_201keysSplitInto3Batches() {
        List<SysLeave> rows = new ArrayList<>();
        for (long i = 1; i <= 201; i++) {
            rows.add(leave(i, StatusEnum.APPROVING.getCode(), 900L + i));
        }
        when(leaveMapper.pageList(any(Page.class), eq("userA"))).thenReturn(pageOf(rows));
        // 逐批回显：每键返回与快照一致的 0 → 只验证分批，不触发回写
        when(approvalClient.statusList(any())).thenAnswer(inv -> {
            ApprovalStatusQueryInnerRequest req = inv.getArgument(0);
            List<InnerApprovalStatusVo> echo = new ArrayList<>();
            for (String key : req.getBusinessKeys()) {
                echo.add(statusVo(key, "0"));
            }
            return R.ok(echo);
        });

        service.pageListMy(query(1, 200), "userA");

        ArgumentCaptor<ApprovalStatusQueryInnerRequest> captor = ArgumentCaptor.forClass(ApprovalStatusQueryInnerRequest.class);
        verify(approvalClient, times(3)).statusList(captor.capture());
        List<ApprovalStatusQueryInnerRequest> batches = captor.getAllValues();
        assertThat(batches).extracting(req -> req.getBusinessKeys().size()).containsExactly(100, 100, 1);
        verify(leaveMapper, never()).updateStatusById(anyLong(), anyInt(), anyString(), any());
    }

    // ---- 降级 / 诚实失败 ----

    @Test
    void pageListMy_feignFailureDegradesToSnapshot() {
        SysLeave row = leave(5L, StatusEnum.APPROVING.getCode(), 12L);
        when(leaveMapper.pageList(any(Page.class), eq("userA"))).thenReturn(pageOf(List.of(row)));
        when(approvalClient.statusList(any())).thenThrow(new RuntimeException("connection refused"));

        PageResult<SysLeaveVo> result = service.pageListMy(query(1, 10), "userA");

        // 分页降级（契约 §5.2）：不抛，返回本地快照（状态可能滞后）
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getStatus()).isEqualTo("0");
        verify(leaveMapper, never()).updateStatusById(anyLong(), anyInt(), anyString(), any());
    }

    @Test
    void findById_feignFailureThrows3022() {
        SysLeave row = leave(5L, StatusEnum.APPROVING.getCode(), 12L);
        when(leaveMapper.findById(5L)).thenReturn(row);
        when(approvalClient.statusList(any())).thenThrow(new RuntimeException("connection refused"));

        BusinessException ex = catchThrowableOfType(() -> service.findById(5L, "userA"),
                BusinessException.class);

        // 详情不降级（契约 §5.3）：诚实抛 3022
        assertThat(ex.getCode()).isEqualTo(3022);
        assertThat(ex.getMessage()).isEqualTo("审批服务不可用");
    }

    @Test
    void findById_notFoundRejected_3018() {
        when(leaveMapper.findById(9L)).thenReturn(null);

        BusinessException ex = catchThrowableOfType(() -> service.findById(9L, "userA"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3018);
        assertThat(ex.getMessage()).isEqualTo("请假单不存在");
    }

    @Test
    void findById_happyPathReconciled() {
        SysLeave row = leave(5L, StatusEnum.APPROVING.getCode(), 12L);
        when(leaveMapper.findById(5L)).thenReturn(row);
        when(approvalClient.statusList(any())).thenReturn(R.ok(List.of(statusVo("5", "2"))));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        com.cloudai.system.vo.SysLeaveDetailVo detail = service.findById(5L, "userA");

        verify(leaveMapper).updateStatusById(eq(5L), eq(StatusEnum.REJECTED.getCode()), eq("userA"), any());
        assertThat(detail.getLeave().getStatus()).isEqualTo("2");
    }

    @Test
    void findById_detailLabelsBackfilledFromTranslationCache() {
        // 契约 §9：详情嵌套 {leave: SysLeaveVo} 手动回填（advisor 只扫顶层，不递归包装）
        SysLeave row = leave(6L, StatusEnum.APPROVED.getCode(), 12L);
        row.setLeaveType(com.cloudai.system.entity.SysLeave.TypeEnum.ANNUAL.getCode());
        when(leaveMapper.findById(6L)).thenReturn(row);
        when(approvalClient.statusList(any())).thenReturn(R.ok(List.of(statusVo("6", "1"))));
        when(translationCache.findDictLabels(eq("system_leave_type"), anySet()))
                .thenReturn(Map.of("3", "Annual"));
        when(translationCache.findDictLabels(eq("bpmn_approval_status"), anySet()))
                .thenReturn(Map.of("1", "Approved"));
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of("userA", "UserA Name", "admin", "Admin"));

        com.cloudai.system.vo.SysLeaveDetailVo detail = service.findById(6L, "userA");

        assertThat(detail.getLeave().getLeaveTypeLabel()).isEqualTo("Annual");
        assertThat(detail.getLeave().getStatusLabel()).isEqualTo("Approved");
        assertThat(detail.getLeave().getApplyUserName()).isEqualTo("UserA Name");
        assertThat(detail.getLeave().getApproverName()).isEqualTo("Admin");
    }

    @Test
    void findById_cacheMissLeavesLabelsNull() {
        // 缓存未命中 → 译文置 null（同 advisor 降级语义），不抛
        SysLeave row = leave(6L, StatusEnum.APPROVING.getCode(), 12L);
        when(leaveMapper.findById(6L)).thenReturn(row);
        when(approvalClient.statusList(any())).thenReturn(R.ok(List.of(statusVo("6", "0"))));
        when(translationCache.findDictLabels(anyString(), anySet())).thenReturn(Map.of());
        when(translationCache.findUserNames(anySet())).thenReturn(Map.of());

        com.cloudai.system.vo.SysLeaveDetailVo detail = service.findById(6L, "userA");

        assertThat(detail.getLeave().getStatusLabel()).isNull();
        assertThat(detail.getLeave().getApplyUserName()).isNull();
        assertThat(detail.getLeave().getApproverName()).isNull();
    }

    // ---- 审批人投影（§5.5 仅启用）----

    @Test
    void listApprovers_mapsEnabledOptions() {
        SysUser admin = user(1L, "admin", "Admin");
        SysUser disabled = user(2L, "ghost", "Ghost");
        when(userMapper.listEnabledOptions()).thenReturn(List.of(admin, disabled));

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
    }

    // ---- 脚手架 ----

    private PageQuery query(int pageNum, int pageSize) {
        PageQuery query = new PageQuery();
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return query;
    }

    private Page<SysLeave> pageOf(List<SysLeave> rows) {
        Page<SysLeave> page = new Page<>(1, rows.size());
        page.setRecords(rows);
        page.setTotal(rows.size());
        return page;
    }

    private SysLeave leave(long id, int status, Long approvalId) {
        SysLeave leave = new SysLeave();
        leave.setId(id);
        leave.setStatus(status);
        leave.setApprovalId(approvalId);
        leave.setApplyUser("userA");
        leave.setApprover("admin");
        return leave;
    }

    private InnerApprovalStatusVo statusVo(String businessKey, String status) {
        InnerApprovalStatusVo vo = new InnerApprovalStatusVo();
        vo.setBusinessKey(businessKey);
        vo.setStatus(status);
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
