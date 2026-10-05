package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class LoginUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String account;
    private String nickname;
    private String password;
    private List<String> permissions;
    private Integer status;
}
