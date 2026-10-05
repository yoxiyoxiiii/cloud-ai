package com.cloudai.common.core.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 登录用户上下文（资源端从网关透传 header 构建）
 */
@Data
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String account;
    private String nickname;
    private List<String> permissions;
}
