package com.cloudai.system.controller.manage;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.InnerSecretPageQuery;
import com.cloudai.system.dto.InnerSecretRevokeRequest;
import com.cloudai.system.entity.SysSharedSecret;
import com.cloudai.system.service.InnerSecretService;
import com.cloudai.system.vo.InnerSecretStatusVo;
import com.cloudai.system.vo.InnerSecretVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * inner 面共享密钥管理端点（契约 2026-11-09-inner-secret-admin-api，网关前缀 /system）。
 * 路径刻意不在 /inner/* 下：那既是 inner 面共享密钥过滤器的保护面也是网关屏蔽面，
 * 管理端点走用户 JWT + @PreAuthorize，不与服务主体密钥认证混用。
 * 真值红线：出参一律 VO，仅掩码，任何端点不返回密钥明文。
 */
@RestController
@RequestMapping("/inner-secret")
@RequiredArgsConstructor
public class InnerSecretManageController {

    private final InnerSecretService manageService;

    /** 密钥分页（id 倒序流水全量可见含终态行；status 精确过滤可选；出参仅掩码） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:innerSecret:list')")
    public R<PageResult<InnerSecretVo>> page(InnerSecretPageQuery query) {
        PageResult<InnerSecretVo> page = manageService.pageList(query);
        return R.ok(page);
    }

    /** 轮换状态卡（Nacos 参数只读回显；redisConsistent=false 表示发布落后，任务一分钟内自愈） */
    @GetMapping("/status")
    @PreAuthorize("hasAuthority('system:innerSecret:list')")
    public R<InnerSecretStatusVo> status() {
        InnerSecretStatusVo status = manageService.findRotationStatus();
        return R.ok(status);
    }

    /** 立即轮换（与定时任务同一条代码路径）：新钥即刻生效、前任进重叠期；
     *  3053 频控（上一轮重叠窗未结束，msg 带剩余秒数）/ 3052 发布软失败（落库已生效，任务自愈重发） */
    @PostMapping("/rotate")
    @PreAuthorize("hasAuthority('system:innerSecret:rotate')")
    public R<InnerSecretVo> rotate() {
        InnerSecretVo created = manageService.rotateSecret(SecurityUtils.currentAccount(),
                SysSharedSecret.SourceEnum.MANUAL.getCode());
        return R.ok(created);
    }

    /** 作废（终态；生效时延 ≤ 各服务缓存 TTL，最迟约 30 秒——前端确认弹窗须明示非瞬时语义）；
     *  3050 不存在或已退场 / 3051 最后一把生效密钥禁作废 / 3052 发布软失败 */
    @PutMapping("/revoke")
    @PreAuthorize("hasAuthority('system:innerSecret:revoke')")
    public R<Void> revoke(@RequestBody InnerSecretRevokeRequest req) {
        manageService.revokeSecret(req.getId(), SecurityUtils.currentAccount());
        return R.ok();
    }
}
