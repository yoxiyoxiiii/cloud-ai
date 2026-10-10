package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.service.dataperm.DataPermColumnApplier;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import com.cloudai.system.service.dataperm.DataPermOperation;
import com.cloudai.system.service.dataperm.DataPermResources;
import com.cloudai.system.service.dataperm.DataScope;
import com.cloudai.system.vo.SysLeaveDetailVo;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.system.vo.UserOptionVo;
import com.cloudai.common.translate.core.TranslationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请假读路径（数据权限轮契约 2026-10-10-data-permission-api §4 取代版）：
 * status/approvalId 仍 JOIN approval_projection 派生（投影轮语义不变）；
 * 行集与列出参改由数据权限求值决定（D5 显式编程式：Service 求值 → mapper 收 DataScope 参数）——
 * 列表恒回填 applyUserName/approverName（管理员视角辨识申请人），title/reason 可能 null 或 ***
 * （列级隐藏/脱敏）；详情行级判定 + IDOR 收口（3026 + deny 留痕，D13）。
 * 列应用经通用反射工具 DataPermColumnApplier（重构轮 D16），行为与手写版逐字段等价。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveManageService {

    private static final int ERR_LEAVE_NOT_FOUND = 3018;

    /** 无权访问该数据（契约 §8，详情行级拒绝） */
    private static final int ERR_LEAVE_NO_ACCESS = 3026;

    private static final String BUSINESS_TYPE_LEAVE = "leave";

    private static final String DICT_LEAVE_TYPE = "system_leave_type";
    private static final String DICT_APPROVAL_STATUS = "bpmn_approval_status";

    private final SysLeaveMapper leaveMapper;
    private final SysUserMapper userMapper;
    private final TranslationCacheService translationCacheService;
    private final DataPermEvaluator dataPermEvaluator;

    /**
     * 请假分页（契约 §4.1）：按当前登录人数据权限求值——全部档全量、空集档短路空页（不查库）、
     * 白名单档按账号 IN；行内姓名批量回填 + 列级动作应用（title/reason）。
     */
    public PageResult<SysLeaveVo> pageList(PageQuery query) {
        DataPermDecision decision = dataPermEvaluator.evaluate(
                DataPermResources.LEAVE, DataPermOperation.LIST, null);
        DataScope scope = decision.getDataScope();
        if (scope.isEmptyScope()) {
            return PageResult.of(0, List.of());
        }
        IPage<SysLeaveVo> page = leaveMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), BUSINESS_TYPE_LEAVE, scope);
        List<SysLeaveVo> rows = page.getRecords();
        fillListNames(rows);
        rows.forEach(row -> DataPermColumnApplier.apply(row, decision.getColumnScope()));
        return PageResult.of(page.getTotal(), rows);
    }

    /**
     * 请假单详情（契约 §4.2）：行不存在 3018 → 行级判定（求值 DETAIL，留痕 businessKey=单据 id）——
     * 归属账号不在范围 3026 + deny 留痕（IDOR 收口）；可见则列级同列表应用 + 译文回填。
     */
    public SysLeaveDetailVo findById(Long id) {
        SysLeaveVo vo = leaveMapper.findById(BUSINESS_TYPE_LEAVE, id);
        if (vo == null) {
            throw new BusinessException(ERR_LEAVE_NOT_FOUND, "请假单不存在");
        }
        DataPermDecision decision = dataPermEvaluator.evaluate(
                DataPermResources.LEAVE, DataPermOperation.DETAIL, String.valueOf(id));
        if (!decision.getDataScope().allows(vo.getApplyUser())) {
            dataPermEvaluator.logDeny(DataPermResources.LEAVE, String.valueOf(id));
            throw new BusinessException(ERR_LEAVE_NO_ACCESS, "无权访问该数据");
        }
        fillDetailLabels(vo);
        DataPermColumnApplier.apply(vo, decision.getColumnScope());
        SysLeaveDetailVo detail = new SysLeaveDetailVo();
        detail.setLeave(vo);
        return detail;
    }

    /** 列表姓名批量回填（契约 §4.1 行为变更）：applyUserName/approverName 列表恒返 */
    private void fillListNames(List<SysLeaveVo> rows) {
        Set<String> users = new HashSet<>();
        for (SysLeaveVo row : rows) {
            if (row.getApplyUser() != null) {
                users.add(row.getApplyUser());
            }
            if (row.getApprover() != null) {
                users.add(row.getApprover());
            }
        }
        if (users.isEmpty()) {
            return;
        }
        Map<String, String> names = translationCacheService.findUserNames(users);
        for (SysLeaveVo row : rows) {
            row.setApplyUserName(names.get(row.getApplyUser()));
            row.setApproverName(names.get(row.getApprover()));
        }
    }

    /** 详情嵌套 VO 手动回填译文（契约 §9：沿 ApprovalQueryService 先例——翻译 advisor 只扫顶层
     *  @TranslateVO，不递归 {leave: SysLeaveVo} 包装）；缓存未命中置 null 同降级语义 */
    private void fillDetailLabels(SysLeaveVo vo) {
        if (vo.getLeaveType() != null) {
            Map<String, String> typeLabels = translationCacheService.findDictLabels(
                    DICT_LEAVE_TYPE, Set.of(vo.getLeaveType()));
            vo.setLeaveTypeLabel(typeLabels.get(vo.getLeaveType()));
        }
        if (vo.getStatus() != null) {
            Map<String, String> statusLabels = translationCacheService.findDictLabels(
                    DICT_APPROVAL_STATUS, Set.of(vo.getStatus()));
            vo.setStatusLabel(statusLabels.get(vo.getStatus()));
        }
        Set<String> users = new HashSet<>();
        if (vo.getApplyUser() != null) {
            users.add(vo.getApplyUser());
        }
        if (vo.getApprover() != null) {
            users.add(vo.getApprover());
        }
        if (users.isEmpty()) {
            return;
        }
        Map<String, String> names = translationCacheService.findUserNames(users);
        vo.setApplyUserName(names.get(vo.getApplyUser()));
        vo.setApproverName(names.get(vo.getApprover()));
    }

    /** 审批人投影（§5.5）：本库直查仅启用账号（status=0）——v1 含停用宽松语义随迁移收紧 */
    public List<UserOptionVo> listApprovers() {
        return userMapper.listEnabledOptions().stream()
                .map(LeaveManageService::toOptionVo)
                .toList();
    }

    private static UserOptionVo toOptionVo(SysUser user) {
        UserOptionVo vo = new UserOptionVo();
        vo.setId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setNickname(user.getNickname());
        return vo;
    }
}
