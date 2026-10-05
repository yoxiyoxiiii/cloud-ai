package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class LoginResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String accessToken;
    private String refreshToken;
    /** access_token 有效期（秒） */
    private Long expiresIn;
}
