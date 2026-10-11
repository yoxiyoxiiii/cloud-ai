package com.cloudai.system.controller.manage;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.MqDlqPageQueryDto;
import com.cloudai.system.service.MqDlqLogService;
import com.cloudai.system.vo.MqDlqDetailVo;
import com.cloudai.system.vo.MqDlqVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MQ 死信治理端点（契约 2026-10-11-mq-dlq-governance-api §1/§3，网关前缀 /system/mq-dlq）。
 * 处置动作（重投/忽略/重置）前端必须二次确认弹窗——重投将真实触发业务动作。
 */
@RestController
@RequestMapping("/mq-dlq")
@RequiredArgsConstructor
public class MqDlqManageController {

    private final MqDlqLogService manageService;

    /** 死信台账分页（dlq_time 倒序；status/bornGroup 精确、msgKey 前后模糊、死信时间闭区间；列表不返 body） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:mqDlq:list')")
    public R<PageResult<MqDlqVo>> page(MqDlqPageQueryDto query) {
        PageResult<MqDlqVo> page = manageService.pageList(query);
        return R.ok(page);
    }

    /** 死信详情（含 body 原文全文与截断标记；行缺失 3038） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:mqDlq:list')")
    public R<MqDlqDetailVo> detail(@PathVariable("id") Long id) {
        MqDlqDetailVo detail = manageService.findDetail(id);
        return R.ok(detail);
    }

    /** 人工重投（高危，前端必须二次确认）：清 dedup 残留行→syncSend 原 topic→行流转留痕；
     *  终态 3040 / 上限自动置弃 3041 / 发送失败 3039（行状态不动可重试） */
    @PostMapping("/{id}/resend")
    @PreAuthorize("hasAuthority('system:mqDlq:resend')")
    public R<Void> resend(@PathVariable("id") Long id) {
        manageService.updateResend(id);
        return R.ok();
    }

    /** 人工忽略（裁定不处理，终态——仅 admin reset 可解除）：仅待处理态可忽略，非 0 态 3040 */
    @PostMapping("/{id}/ignore")
    @PreAuthorize("hasAuthority('system:mqDlq:ignore')")
    public R<Void> ignore(@PathVariable("id") Long id) {
        manageService.updateIgnore(id);
        return R.ok();
    }

    /** 解除终态（admin）：已忽略/已放弃 → 待处理并清零重投计数；非终态 3040 */
    @PostMapping("/{id}/reset")
    @PreAuthorize("hasAuthority('system:mqDlq:reset')")
    public R<Void> reset(@PathVariable("id") Long id) {
        manageService.updateReset(id);
        return R.ok();
    }
}
