package com.cloudai.system.api.domain;

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
    /** 启用角色 id 集合（知识库授权等身份快照消费；停用角色排除，与权限聚合同口径） */
    private List<Long> roleIds;
    /** 部门祖先链 id 集合（自身部门 + 全部祖先，root→self 顺序；未挂部门为空表） */
    private List<Long> deptIds;
    /** 0正常 1停用 */
    private Integer status;
}
