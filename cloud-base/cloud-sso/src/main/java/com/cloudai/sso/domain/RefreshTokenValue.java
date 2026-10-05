package com.cloudai.sso.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * refreshToken 存储值（sso:refresh:{userId} 的纯 JSON）：
 * 绑定 tokenId 使 refresh 精确失效对应会话（多会话下不误杀）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefreshTokenValue implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tokenId;
    private String token;
}
