package com.cloudai.sso.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * /auth/me 出参 VO（当前会话投影，契约 perms-api §2）。
 * 最小暴露面：仅账号与权限快照，不含 userId/ip/loginTime/tokenId（设计 D8）。
 */
@Data
public class CurrentUserVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 会话登录账号（与 token 声明一致） */
    private String account;

    /** 该会话权限快照（登录/refresh 时聚合；零角色/零绑定为空数组，合法态） */
    private List<String> permissions;
}
