package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 重置密码请求体（明文，服务端 BCrypt）。
 */
@Data
public class ResetPasswordRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 新密码（明文传输经网关 TLS，落库前服务端加密） */
    private String password;
}
