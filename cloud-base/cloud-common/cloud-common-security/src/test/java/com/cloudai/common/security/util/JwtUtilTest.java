package com.cloudai.common.security.util;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtUtilTest {

    /** HS512 要求密钥 >= 512bit（64 字节） */
    private static final String SECRET = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String OTHER_SECRET = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";

    @Test
    void createAndParse_roundtrip() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "token-uuid-1", 7200);
        Claims claims = JwtUtil.parseToken(SECRET, token);
        assertThat(claims.getSubject()).isEqualTo("1");
        assertThat(claims.get("account", String.class)).isEqualTo("admin");
        assertThat(claims.getId()).isEqualTo("token-uuid-1");
    }

    @Test
    void parseToken_expiredThrows() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "token-uuid-2", -10);
        assertThatThrownBy(() -> JwtUtil.parseToken(SECRET, token))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void parseToken_wrongSecretThrows() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "token-uuid-3", 7200);
        assertThatThrownBy(() -> JwtUtil.parseToken(OTHER_SECRET, token))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void parseToken_tamperedTokenThrows() {
        String token = JwtUtil.createToken(SECRET, 1L, "admin", "token-uuid-4", 7200);
        String tampered = token.substring(0, token.length() - 3) + "aaa";
        assertThatThrownBy(() -> JwtUtil.parseToken(SECRET, tampered))
                .isInstanceOf(JwtException.class);
    }
}
