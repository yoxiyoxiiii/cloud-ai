package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;

@Data
public class LoginRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String account;
    private String password;
}
