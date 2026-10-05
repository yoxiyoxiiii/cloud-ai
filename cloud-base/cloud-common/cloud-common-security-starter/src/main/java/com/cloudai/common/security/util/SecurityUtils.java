package com.cloudai.common.security.util;

import com.cloudai.common.core.domain.LoginUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 登录上下文工具（手写 SQL 场景替代 MetaObjectHandler 自动填充取操作人）
 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    /** 当前登录账号；匿名/无上下文返回 null */
    public static String currentAccount() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof UsernamePasswordAuthenticationToken token
                && token.getPrincipal() instanceof LoginUser loginUser) {
            return loginUser.getAccount();
        }
        return null;
    }
}
