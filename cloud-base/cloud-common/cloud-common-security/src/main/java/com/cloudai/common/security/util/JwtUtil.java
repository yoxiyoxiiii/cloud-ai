package com.cloudai.common.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/**
 * JWT 工具（HS512）。密钥由调用方传入（来自 Nacos 配置），便于阶段 3 升级 RS256 时只改本类。
 */
public final class JwtUtil {

    private JwtUtil() {
    }

    /**
     * 签发 access_token
     *
     * @param secret     HS512 密钥（>= 64 字节）
     * @param userId     用户 ID（写入 subject）
     * @param account    账号（写入 account claim）
     * @param tokenId    会话唯一标识（写入 jti，用于 Redis 在线状态）
     * @param ttlSeconds 有效期（秒），非正值将签发立即过期的令牌
     */
    public static String createToken(String secret, Long userId, String account, String tokenId, long ttlSeconds) {
        Objects.requireNonNull(userId, "userId");
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .id(tokenId)
                .subject(String.valueOf(userId))
                .claim("account", account)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds)))
                .signWith(key, Jwts.SIG.HS512)
                .compact();
    }

    /**
     * 解析并验签。失败统一抛 JwtException 子类；过期为 ExpiredJwtException，密钥不符为 SignatureException。
     */
    public static Claims parseToken(String secret, String token) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
