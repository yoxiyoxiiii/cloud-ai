package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.UserEntry;
import com.cloudai.common.translate.provider.UserSourceProvider;
import com.cloudai.system.api.domain.LoginUserDTO;
import com.cloudai.system.service.SysUserLinkageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间内部接口：仅 Feign 调用，网关已屏蔽 /{service}/inner/**。
 */
@RestController
@RequestMapping("/inner/user")
@RequiredArgsConstructor
public class InnerUserController {

    private final SysUserLinkageService linkageService;
    private final UserSourceProvider userSourceProvider;

    /** 按账号取登录聚合（含密码散列与权限集合）；账号不存在返回 data=null */
    @GetMapping("/{account}")
    public R<LoginUserDTO> getUserByAccount(@PathVariable("account") String account) {
        LoginUserDTO loginUser = linkageService.findByAccount(account);
        return R.ok(loginUser);
    }

    /** 全量用户投影（含停用用户——审计字段翻译需要全量昵称；契约 inner-api §2.2），供翻译远程回源；空库返回空数组 */
    @GetMapping("/all")
    public R<List<UserEntry>> listAll() {
        List<UserEntry> entries = userSourceProvider.listAll();
        return R.ok(entries);
    }
}
