package com.cloudai.sso.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 在线会话（存 Redis：sso:online:{tokenId}，value 为本对象 JSON）
 */
@Data
public class OnlineSession implements Serializable {

    private static final long serialVersionUID = 1L;

    private String tokenId;
    private Long userId;
    private String account;
    private List<String> permissions;
    /** 登录时间（epoch millis） */
    private Long loginTime;
    /** 登录 IP */
    private String ip;
}
