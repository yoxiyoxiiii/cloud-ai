package com.cloudai.sso.controller;

import com.cloudai.common.core.domain.R;
import com.cloudai.sso.domain.OnlineSession;
import com.cloudai.sso.dto.LoginRequest;
import com.cloudai.sso.dto.LoginResult;
import com.cloudai.sso.dto.RefreshRequest;
import com.cloudai.sso.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final TokenService tokenService;

    @PostMapping("/login")
    public R<LoginResult> login(@RequestBody LoginRequest req, HttpServletRequest http) {
        LoginResult result = tokenService.login(req.getAccount(), req.getPassword(), http.getRemoteAddr());
        return R.ok(result);
    }

    @PostMapping("/refresh")
    public R<LoginResult> refresh(@RequestBody RefreshRequest req) {
        LoginResult result = tokenService.refresh(req.getRefreshToken());
        return R.ok(result);
    }

    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader("Authorization") String authorization) {
        tokenService.logout(stripBearer(authorization));
        return R.ok();
    }

    @GetMapping("/online")
    @PreAuthorize("hasAuthority('sso:online:list')")
    public R<List<OnlineSession>> online() {
        List<OnlineSession> sessions = tokenService.onlineList();
        return R.ok(sessions);
    }

    @DeleteMapping("/online/{tokenId}")
    @PreAuthorize("hasAuthority('sso:online:kick')")
    public R<Void> kick(@PathVariable("tokenId") String tokenId) {
        tokenService.kick(tokenId);
        return R.ok();
    }

    private String stripBearer(String authorization) {
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return authorization;
    }
}
