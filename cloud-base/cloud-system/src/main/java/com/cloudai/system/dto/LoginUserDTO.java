package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * sso 登录所需的用户聚合（含密码散列——仅内网 /inner 通道返回）
 */
@Data
public class LoginUserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long userId;
    private String account;
    private String nickname;
    private String password;
    /** 权限标识集合 */
    private List<String> permissions;
    /** 0正常 1停用 */
    private Integer status;
}
