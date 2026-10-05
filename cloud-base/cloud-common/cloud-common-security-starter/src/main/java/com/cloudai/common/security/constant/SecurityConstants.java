package com.cloudai.common.security.constant;

/**
 * 网关与资源端共享的内部透传 header 常量（网关剥离外部同名 header 后注入）
 * 信任前提：仅限网关下游信任链使用——直连服务端口可伪造任意 X-User-* 身份（服务端口不对公网暴露是部署约束）。
 */
public final class SecurityConstants {

    public static final String HEADER_USER_ID = "X-User-Id";
    public static final String HEADER_USER_ACCOUNT = "X-User-Account";
    public static final String HEADER_USER_PERMS = "X-User-Perms";
    public static final String PERMS_SEPARATOR = ",";

    /**
     * Redis 在线会话键前缀，jti 为 JWT 的 jti 声明。
     * 值 = OnlineSession 的纯 JSON 字符串（无 @class 类型头），网关以 OnlineSessionView 投影解析 permissions。
     */
    public static final String ONLINE_KEY_PREFIX = "sso:online:";

    private SecurityConstants() {
    }
}
