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

import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private static final String REFRESH_KEY_PREFIX = "sso:refresh:";

    /** refresh 反向索引键前缀：sso:refresh-token:{token} = userId 纯字符串（非 JSON），TTL 与主槽相同 */
    private static final String REFRESH_INDEX_KEY_PREFIX = "sso:refresh-token:";

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

    /**
     * 以 refreshToken 换发新双令牌。
     * 归属定位走反向索引：GET sso:refresh-token:{token} 得 userId，再 GET 主槽做 token
     * 相等校验——两次 GET 精确定位，替代对全量用户槽的 KEYS 扫描（KEYS 是 O(N) 且阻塞
     * Redis 单线程，用户量增长会拖慢整个实例上的全部请求）。
     */
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BusinessException(2004, "refreshToken 不能为空");
        }
        // sso:refresh-token:{token},获取userId
        String userId = stringRedisTemplate.opsForValue().get(REFRESH_INDEX_KEY_PREFIX + refreshToken);
        if (userId == null) {
            throw new BusinessException(2005, "refreshToken 无效或已过期");
        }

        // sso:refresh:{userId}
        RefreshTokenValue matched = parseRefreshValue(
                stringRedisTemplate.opsForValue().get(REFRESH_KEY_PREFIX + userId));
        // 相等校验防陈旧索引错位：主槽已被新登录覆盖时，残留索引指向的旧 token 比对不符即拒绝
        if (matched == null || !refreshToken.equals(matched.getToken())) {
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
        // issueTokens 以新值覆盖 sso:refresh:{userId}，此处不得再 delete 主槽（曾致新 refreshToken 落地即死）
        LoginResult result = issueTokens(latest, old.getIp());
        // 换发成功后才清理旧 token 的反向索引：失败路径旧 token 仍应可重试
        stringRedisTemplate.delete(REFRESH_INDEX_KEY_PREFIX + refreshToken);
        return result;
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
        // 先读主槽取当前 refreshToken，反向索引与之成对清理（miss 时无从得知 token，跳过）
        RefreshTokenValue refreshValue = parseRefreshValue(
                stringRedisTemplate.opsForValue().get(REFRESH_KEY_PREFIX + userId));
        if (refreshValue != null) {
            stringRedisTemplate.delete(REFRESH_INDEX_KEY_PREFIX + refreshValue.getToken());
        }
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
        // 创建token
        String accessToken = JwtUtil.createToken(jwtProperties.getSecret(), dto.getUserId(), dto.getAccount(), tokenId,
                jwtProperties.getAccessTokenTtl());
        String refreshToken = UUID.randomUUID().toString();

        OnlineSession session = new OnlineSession();
        session.setTokenId(tokenId);
        session.setUserId(dto.getUserId());
        session.setAccount(dto.getAccount());
        session.setPermissions(dto.getPermissions());
        session.setRoleIds(dto.getRoleIds());
        session.setDeptIds(dto.getDeptIds());
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
        // 反向索引与主槽同 TTL 双写（值 = userId 纯字符串）。登录覆盖旧槽时不主动清旧索引：
        // 主槽 token 相等校验已挡住错位、TTL 自灭，避免登录路径为此多一次读。
        stringRedisTemplate.opsForValue().set(REFRESH_INDEX_KEY_PREFIX + refreshToken,
                String.valueOf(dto.getUserId()), jwtProperties.getRefreshTokenTtl(), TimeUnit.SECONDS);

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
