package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.LoginUserDTO;
import com.cloudai.system.service.SysUserLinkageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务间内部接口：仅 Feign 调用，网关已屏蔽 /{service}/inner/**。
 */
@RestController
@RequestMapping("/inner/user")
@RequiredArgsConstructor
public class InnerUserController {

    private final SysUserLinkageService linkageService;

    /** 按账号取登录聚合（含密码散列与权限集合）；账号不存在返回 data=null */
    @GetMapping("/{account}")
    public R<LoginUserDTO> getUserByAccount(@PathVariable("account") String account) {
        return R.ok(linkageService.getLoginUserByAccount(account));
    }
}
