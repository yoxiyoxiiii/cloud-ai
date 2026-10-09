package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
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
 * 请假读路径（契约 2026-10-09-approval-projection-api §4.1 读语义取代版）：
 * 纯本地零 Feign——status/approvalId 由 mapper JOIN approval_projection 派生直出
 * （事件秒级收敛 + 框架定时对账兜底，业务读零纠偏零降级，3022 自读路径退役）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveManageService {

    private static final int ERR_LEAVE_NOT_FOUND = 3018;

    private static final String BUSINESS_TYPE_LEAVE = "leave";

    private static final String DICT_LEAVE_TYPE = "system_leave_type";
    private static final String DICT_APPROVAL_STATUS = "bpmn_approval_status";

    private final SysLeaveMapper leaveMapper;
    private final SysUserMapper userMapper;
    private final TranslationCacheService translationCacheService;

    /** 我的请假分页（恒按申请人，id 倒序）：JOIN 派生实时状态（发起后秒级窗口 status=0/approvalId=null） */
    public PageResult<SysLeaveVo> pageListMy(PageQuery query, String applyUser) {
        IPage<SysLeaveVo> page = leaveMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), BUSINESS_TYPE_LEAVE, applyUser);
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    /** 请假单详情（§5.3）：单行 JOIN 派生 + 译文手动回填（嵌套 VO 不走翻译 advisor，沿先例） */
    public SysLeaveDetailVo findById(Long id) {
        SysLeaveVo vo = leaveMapper.findById(BUSINESS_TYPE_LEAVE, id);
        if (vo == null) {
            throw new BusinessException(ERR_LEAVE_NOT_FOUND, "请假单不存在");
        }
        fillDetailLabels(vo);
        SysLeaveDetailVo detail = new SysLeaveDetailVo();
        detail.setLeave(vo);
        return detail;
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
