package com.cloudai.common.security.constant;

/**
 * 网关与资源端共享的内部透传 header 常量（网关剥离外部同名 header 后注入）
 */
public final class SecurityConstants {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ACCOUNT = "X-User-Account";
    public static final String HEADER_USER_PERMS = "X-User-Perms";
    public static final String PERMS_SEPARATOR = ",";

    /** Redis 在线会话键前缀，jti 为 JWT 的 jti 声明 */
    public static final String ONLINE_KEY_PREFIX = "sso:online:";

    private SecurityConstants() {
    }
}
