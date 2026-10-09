package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.system.convert.SysLeaveConvert;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.system.entity.SysLeave;
import com.cloudai.system.mapper.SysLeaveMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.system.vo.SysLeaveDetailVo;
import com.cloudai.system.vo.SysLeaveVo;
import com.cloudai.system.vo.UserOptionVo;
import com.cloudai.common.translate.core.TranslationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 请假读路径（契约 2026-10-08-approval-platform-api §5.2/§5.3/§5.5）：
 * 状态快照读时纠偏——本地分页后经 /inner/approval/status-list 拉真相源，不一致回写。
 * 分批铁律：单批 ≤100（分页 pageSize 上限 200 而平台单批上限 100，按键分批防整页撞 1001）；
 * Feign 失败分页降级返回本地快照（log.error，状态可能滞后记档）、详情诚实抛 3022。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeaveManageService {

    private static final int ERR_LEAVE_NOT_FOUND = 3018;
    private static final int ERR_APPROVAL_UNAVAILABLE = 3022;

    /** 纠偏单批上限（对齐平台 §4.2 businessKeys ≤100；调用方分批责任） */
    private static final int RECONCILE_BATCH_SIZE = 100;

    private static final String BUSINESS_TYPE_LEAVE = "leave";

    private static final String DICT_LEAVE_TYPE = "system_leave_type";
    private static final String DICT_APPROVAL_STATUS = "bpmn_approval_status";

    private final SysLeaveMapper leaveMapper;
    private final SysUserMapper userMapper;
    private final BpmnApprovalClient approvalClient;
    private final TranslationCacheService translationCacheService;

    /** 我的请假分页（恒按申请人，id 倒序）：纠偏后返回实时状态；Feign 失败降级快照 */
    public PageResult<SysLeaveVo> pageListMy(PageQuery query, String applyUser) {
        IPage<SysLeave> page = leaveMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), applyUser);
        List<SysLeave> rows = page.getRecords();
        try {
            reconcileStatus(rows, applyUser);
        } catch (BusinessException e) {
            // 分页降级（契约 §5.2）：记档返回快照，状态可能滞后——详情/办理路径仍会纠偏
            log.error("分页纠偏失败，降级返回本地快照: applyUser={}", applyUser, e);
        }
        List<SysLeaveVo> vos = rows.stream().map(SysLeaveConvert::toVo).toList();
        return PageResult.of(page.getTotal(), vos);
    }

    /** 请假单详情（§5.3）：单行纠偏；Feign 失败诚实抛 3022（不降级——详情是纠偏可见性锚点） */
    public SysLeaveDetailVo findById(Long id, String operator) {
        SysLeave leave = leaveMapper.findById(id);
        if (leave == null) {
            throw new BusinessException(ERR_LEAVE_NOT_FOUND, "请假单不存在");
        }
        reconcileStatus(List.of(leave), operator);
        SysLeaveDetailVo detail = new SysLeaveDetailVo();
        SysLeaveVo vo = SysLeaveConvert.toVo(leave);
        fillDetailLabels(vo);
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

    /** 纠偏：按键 ≤100 分批拉真相源，快照≠真相即回写（内存行同步刷新，出参即时一致）；
     *  传输失败/平台非 200 抛 3022 由调用方决定降级或诚实报错 */
    private void reconcileStatus(List<SysLeave> rows, String operator) {
        for (int from = 0; from < rows.size(); from += RECONCILE_BATCH_SIZE) {
            List<SysLeave> batch = rows.subList(from, Math.min(from + RECONCILE_BATCH_SIZE, rows.size()));
            Map<String, String> truth = fetchPlatformStatus(batch);
            applyTruth(batch, truth, operator);
        }
    }

    /** 单批 Feign 调用：仅带有关联审批单的键（approvalId=null 为发起失败行，无可纠偏） */
    private Map<String, String> fetchPlatformStatus(List<SysLeave> batch) {
        List<String> keys = new ArrayList<>();
        for (SysLeave row : batch) {
            if (row.getApprovalId() != null) {
                keys.add(String.valueOf(row.getId()));
            }
        }
        if (keys.isEmpty()) {
            return Map.of();
        }
        ApprovalStatusQueryInnerRequest req = new ApprovalStatusQueryInnerRequest();
        req.setBusinessType(BUSINESS_TYPE_LEAVE);
        req.setBusinessKeys(keys);
        R<List<InnerApprovalStatusVo>> response;
        try {
            response = approvalClient.statusList(req);
        } catch (Exception e) {
            log.error("纠偏状态查询 Feign 调用失败: keys={}", keys.size(), e);
            throw new BusinessException(ERR_APPROVAL_UNAVAILABLE, "审批服务不可用");
        }
        if (response == null || response.getCode() != ErrorCode.SUCCESS.getCode() || response.getData() == null) {
            log.error("纠偏状态查询平台返回失败: code={}", response == null ? null : response.getCode());
            throw new BusinessException(ERR_APPROVAL_UNAVAILABLE, "审批服务不可用");
        }
        Map<String, String> truth = new HashMap<>();
        for (InnerApprovalStatusVo vo : response.getData()) {
            truth.put(vo.getBusinessKey(), vo.getStatus());
        }
        return truth;
    }

    /** 真相回写：不一致才落库（避免无谓 UPDATE）；快照同值行零写 */
    private void applyTruth(List<SysLeave> batch, Map<String, String> truth, String operator) {
        for (SysLeave row : batch) {
            String key = String.valueOf(row.getId());
            String platformStatus = truth.get(key);
            if (platformStatus == null || row.getApprovalId() == null) {
                continue;
            }
            Integer truthCode = Integer.valueOf(platformStatus);
            if (!truthCode.equals(row.getStatus())) {
                leaveMapper.updateStatusById(row.getId(), truthCode, operator, LocalDateTime.now());
                row.setStatus(truthCode);
            }
        }
    }

    private static UserOptionVo toOptionVo(com.cloudai.system.entity.SysUser user) {
        UserOptionVo vo = new UserOptionVo();
        vo.setId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setNickname(user.getNickname());
        return vo;
    }
}
