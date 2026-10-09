package com.cloudai.sso.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import com.cloudai.sso.domain.OnlineSession;
import com.cloudai.sso.domain.RefreshTokenValue;
import com.cloudai.sso.dto.CurrentUserVo;
import com.cloudai.sso.dto.LoginResult;
import com.cloudai.system.api.client.SystemUserClient;
import com.cloudai.system.api.domain.LoginUserDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String REFRESH_KEY_PREFIX = "sso:refresh:";

    /** 时序旁路防护用的固定散列（明文为随机无效串） */
    private static final String DUMMY_HASH = "$2a$10$VAopGs8o/mcBgRg6G0G6..u1kOmyJqxrqhu74w9MhfYZgWKZHZBSG";

    /**
     * 在线会话以纯 JSON 字符串写入（StringRedisTemplate）：
     * 网关用 OnlineSessionView 解析 permissions，带 default typing 的值
     * （@class 头 + List 的 ["java.util.ArrayList",[...]] 包裹）会解析失败致权限丢失。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final SystemUserClient userClient;
    private final StringRedisTemplate stringRedisTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;

    public LoginResult login(String account, String password, String ip) {
        R<LoginUserDTO> resp;
        try {
            resp = userClient.getUserByAccount(account);
        } catch (FeignException e) {
            log.error("cloud-system 调用失败，account={}", account, e);
            throw new BusinessException(2002, "用户服务不可用，请稍后重试");
        }
        if (resp == null || resp.getCode() != 200) {
            throw new BusinessException(resp == null ? 2002 : resp.getCode(),
                    resp == null ? "用户服务不可用" : resp.getMsg());
        }
        LoginUserDTO dto = resp.getData();
        if (dto == null) {
            // 恒定时间：对固定散列跑一次匹配，抹平"账号不存在即快速返回"的时序旁路
            passwordEncoder.matches(password, DUMMY_HASH);
            throw new BusinessException(2001, "账号或密码错误");
        }
        if (!passwordEncoder.matches(password, dto.getPassword())) {
            throw new BusinessException(2001, "账号或密码错误");
        }
        // 0=正常（LoginUserDTO 为跨服务契约，语义由 cloud-system sys_user.status 定义，sso 不引 system 实体枚举）
        if (dto.getStatus() == null || dto.getStatus() != 0) {
            throw new BusinessException(2003, "账号已停用");
        }
        return issueTokens(dto, ip);
    }

    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(2004, "refreshToken 不能为空");
        }
        Set<String> keys = stringRedisTemplate.keys(REFRESH_KEY_PREFIX + "*");
        if (keys == null) {
            throw new BusinessException(2005, "refreshToken 无效或已过期");
        }
        RefreshTokenValue matched = null;
        for (String key : keys) {
            RefreshTokenValue value = parseRefreshValue(stringRedisTemplate.opsForValue().get(key));
            if (value != null && refreshToken.equals(value.getToken())) {
                matched = value;
                break;
            }
        }
        if (matched == null) {
            throw new BusinessException(2005, "refreshToken 无效或已过期");
        }
        // 读旧会话（保留 ip）后精确删除绑定会话（多会话下不误杀其他端）
        String onlineJson = stringRedisTemplate.opsForValue()
                .get(SecurityConstants.ONLINE_KEY_PREFIX + matched.getTokenId());
        if (onlineJson == null) {
            throw new BusinessException(2005, "会话已失效，请重新登录");
        }
        OnlineSession old = parseSession(onlineJson);
        if (old == null) {
            throw new BusinessException(2005, "会话已失效，请重新登录");
        }
        stringRedisTemplate.delete(SecurityConstants.ONLINE_KEY_PREFIX + matched.getTokenId());
        // 回查 system：校验账号未停用并取最新权限快照（防停用/降权用户经 refresh 保活旧权限）
        LoginUserDTO latest;
        try {
            R<LoginUserDTO> resp = userClient.getUserByAccount(old.getAccount());
            if (resp == null || resp.getCode() != 200) {
                // 降级/远端失败统一 2002（设计 D4 表 1b：fallback 2002 走此分支端到端等价；
                // 原 getData()==null→2005 会把服务故障误报为会话失效——边缘路径语义修正记档）
                log.error("cloud-system 调用失败或返回失败，account={}, code={}", old.getAccount(),
                        resp == null ? null : resp.getCode());
                throw new BusinessException(2002, "用户服务不可用，请稍后重试");
            }
            latest = resp.getData();
        } catch (FeignException e) {
            log.error("cloud-system 调用失败，account={}", old.getAccount(), e);
            throw new BusinessException(2002, "用户服务不可用，请稍后重试");
        }
        // 0=正常（同上：跨服务 DTO，状态语义由 cloud-system 定义）
        if (latest == null || latest.getStatus() == null || latest.getStatus() != 0) {
            throw new BusinessException(2005, "会话已失效，请重新登录");
        }
        // issueTokens 以新值覆盖 sso:refresh:{userId}，此处不得再 delete（曾致新 refreshToken 落地即死）
        return issueTokens(latest, old.getIp());
    }

    public void logout(String accessToken) {
        Claims claims;
        try {
            claims = JwtUtil.parseToken(jwtProperties.getSecret(), accessToken);
        } catch (io.jsonwebtoken.JwtException e) {
            // 已过期/无效的 token 注销视为成功（幂等；其键随 TTL 自灭）
            return;
        }
        String tokenId = claims.getId();
        Long userId = Long.valueOf(claims.getSubject());
        stringRedisTemplate.delete(SecurityConstants.ONLINE_KEY_PREFIX + tokenId);
        stringRedisTemplate.delete(REFRESH_KEY_PREFIX + userId);
    }

    public List<OnlineSession> onlineList() {
        List<OnlineSession> list = new ArrayList<>();
        Set<String> keys = stringRedisTemplate.keys(SecurityConstants.ONLINE_KEY_PREFIX + "*");
        if (keys != null) {
            keys.forEach(key -> {
                String json = stringRedisTemplate.opsForValue().get(key);
                if (json == null) {
                    return;
                }
                try {
                    list.add(JSON.readValue(json, OnlineSession.class));
                } catch (JsonProcessingException ignored) {
                    // 跳过损坏条目
                }
            });
        }
        return list;
    }

    public void kick(String tokenId) {
        stringRedisTemplate.delete(SecurityConstants.ONLINE_KEY_PREFIX + tokenId);
    }

    public CurrentUserVo findCurrentUser(String accessToken) {
        Claims claims;
        try {
            claims = JwtUtil.parseToken(jwtProperties.getSecret(), accessToken);
        } catch (JwtException e) {
            log.error("accessToken 解析失败", e);
            throw new BusinessException(401, "会话已失效，请重新登录");
        }
        String tokenId = claims.getId();
        String onlineJson = stringRedisTemplate.opsForValue()
                .get(SecurityConstants.ONLINE_KEY_PREFIX + tokenId);
        OnlineSession session = parseSession(onlineJson);
        if (session == null) {
            // 防御路径：网关刚放行而 Redis 会话竞态缺失（契约 §2 错误表，理论不达）
            throw new BusinessException(401, "会话已失效，请重新登录");
        }
        CurrentUserVo vo = new CurrentUserVo();
        vo.setAccount(session.getAccount());
        // 旧格式会话残留无 permissions 字段时兜底空数组（契约必返 string[]）
        vo.setPermissions(session.getPermissions() == null ? Collections.emptyList() : session.getPermissions());
        return vo;
    }

    private LoginResult issueTokens(LoginUserDTO dto, String ip) {
        String tokenId = UUID.randomUUID().toString();
        String accessToken = JwtUtil.createToken(jwtProperties.getSecret(), dto.getUserId(), dto.getAccount(), tokenId,
                jwtProperties.getAccessTokenTtl());
        String refreshToken = UUID.randomUUID().toString();

        OnlineSession session = new OnlineSession();
        session.setTokenId(tokenId);
        session.setUserId(dto.getUserId());
        session.setAccount(dto.getAccount());
        session.setPermissions(dto.getPermissions());
        session.setLoginTime(System.currentTimeMillis());
        session.setIp(ip);
        String sessionJson;
        try {
            sessionJson = JSON.writeValueAsString(session);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("在线会话序列化失败", e);
        }
        stringRedisTemplate.opsForValue().set(SecurityConstants.ONLINE_KEY_PREFIX + tokenId,
                sessionJson, jwtProperties.getAccessTokenTtl(), TimeUnit.SECONDS);
        String refreshJson;
        try {
            refreshJson = JSON.writeValueAsString(new RefreshTokenValue(tokenId, refreshToken));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("refreshToken 序列化失败", e);
        }
        stringRedisTemplate.opsForValue().set(REFRESH_KEY_PREFIX + dto.getUserId(),
                refreshJson, jwtProperties.getRefreshTokenTtl(), TimeUnit.SECONDS);

        LoginResult result = new LoginResult();
        result.setAccessToken(accessToken);
        result.setRefreshToken(refreshToken);
        result.setExpiresIn(jwtProperties.getAccessTokenTtl());
        return result;
    }

    private RefreshTokenValue parseRefreshValue(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, RefreshTokenValue.class);
        } catch (JsonProcessingException e) {
            return null; // 旧格式/损坏值一律视为不匹配
        }
    }

    private OnlineSession parseSession(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, OnlineSession.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
