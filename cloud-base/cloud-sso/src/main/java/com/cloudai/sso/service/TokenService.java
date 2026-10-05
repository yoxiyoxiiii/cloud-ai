package com.cloudai.sso.service;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.constant.SecurityConstants;
import com.cloudai.common.security.util.JwtUtil;
import com.cloudai.sso.client.SystemUserClient;
import com.cloudai.sso.domain.OnlineSession;
import com.cloudai.sso.dto.LoginResult;
import com.cloudai.sso.dto.LoginUserDTO;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

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

    @Value("${cloud.jwt.secret}")
    private String secret;

    /** 字段初始化默认值供纯单元测试使用（无 Spring 注入）；运行时由 Nacos 配置覆盖 */
    @Value("${cloud.jwt.access-token-ttl:7200}")
    private long accessTtlSeconds = 7200;

    @Value("${cloud.jwt.refresh-token-ttl:604800}")
    private long refreshTtlSeconds = 604800;

    public LoginResult login(String account, String password, String ip) {
        R<LoginUserDTO> resp;
        try {
            resp = userClient.getUserByAccount(account);
        } catch (FeignException e) {
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
        if (dto.getStatus() == null || dto.getStatus() != 0) {
            throw new BusinessException(2003, "账号已停用");
        }
        return issueTokens(dto, ip);
    }

    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(2004, "refreshToken 不能为空");
        }
        Long userId = null;
        Set<String> keys = stringRedisTemplate.keys(REFRESH_KEY_PREFIX + "*");
        if (keys == null) {
            throw new BusinessException(2005, "refreshToken 无效或已过期");
        }
        for (String key : keys) {
            String value = stringRedisTemplate.opsForValue().get(key);
            if (refreshToken.equals(value)) {
                userId = Long.valueOf(key.substring(REFRESH_KEY_PREFIX.length()));
                break;
            }
        }
        if (userId == null) {
            throw new BusinessException(2005, "refreshToken 无效或已过期");
        }
        OnlineSession session = findOnlineSessionByUserId(userId);
        if (session == null) {
            throw new BusinessException(2005, "会话已失效，请重新登录");
        }
        stringRedisTemplate.delete(SecurityConstants.ONLINE_KEY_PREFIX + session.getTokenId());
        // 回查 system：校验账号未停用并取最新权限快照（防停用/降权用户经 refresh 保活旧权限）
        LoginUserDTO latest;
        try {
            R<LoginUserDTO> resp = userClient.getUserByAccount(session.getAccount());
            latest = resp == null ? null : resp.getData();
        } catch (FeignException e) {
            throw new BusinessException(2002, "用户服务不可用，请稍后重试");
        }
        if (latest == null || latest.getStatus() == null || latest.getStatus() != 0) {
            throw new BusinessException(2005, "会话已失效，请重新登录");
        }
        // issueTokens 以新值覆盖 sso:refresh:{userId}，此处不得再 delete（曾致新 refreshToken 落地即死）
        return issueTokens(latest, session.getIp());
    }

    public void logout(String accessToken) {
        Claims claims;
        try {
            claims = JwtUtil.parseToken(secret, accessToken);
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

    private LoginResult issueTokens(LoginUserDTO dto, String ip) {
        String tokenId = UUID.randomUUID().toString();
        String accessToken = JwtUtil.createToken(secret, dto.getUserId(), dto.getAccount(), tokenId, accessTtlSeconds);
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
                sessionJson, accessTtlSeconds, TimeUnit.SECONDS);
        stringRedisTemplate.opsForValue().set(REFRESH_KEY_PREFIX + dto.getUserId(),
                refreshToken, refreshTtlSeconds, TimeUnit.SECONDS);

        LoginResult result = new LoginResult();
        result.setAccessToken(accessToken);
        result.setRefreshToken(refreshToken);
        result.setExpiresIn(accessTtlSeconds);
        return result;
    }

    private OnlineSession findOnlineSessionByUserId(Long userId) {
        Set<String> keys = stringRedisTemplate.keys(SecurityConstants.ONLINE_KEY_PREFIX + "*");
        if (keys == null) {
            return null;
        }
        for (String key : keys) {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json == null) {
                continue;
            }
            OnlineSession session;
            try {
                session = JSON.readValue(json, OnlineSession.class);
            } catch (JsonProcessingException e) {
                continue;
            }
            if (userId.equals(session.getUserId())) {
                return session;
            }
        }
        return null;
    }
}
