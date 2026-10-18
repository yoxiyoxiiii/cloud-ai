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
    /** 启用角色 id 集合（登录聚合；旧格式会话缺字段反序列化为 null，网关注入空串兜底） */
    private List<Long> roleIds;
    /** 部门祖先链 id 集合（自身+全部祖先，root→self；未挂部门为空表） */
    private List<Long> deptIds;
    /** 登录时间（epoch millis） */
    private Long loginTime;
    /** 登录 IP */
    private String ip;
}
