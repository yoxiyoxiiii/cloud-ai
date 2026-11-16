package com.cloudai.gateway.filter;

import lombok.Data;

import java.util.List;

/** 网关侧在线会话投影（只取权限/角色/部门字段；@class 头与其余字段被 ObjectMapper 忽略；旧会话缺新字段反序列化为 null → 注入空串）
 * @author jy-root
 *
 * */
@Data
public class OnlineSessionView {
    private List<String> permissions;
    private List<Long> roleIds;
    private List<Long> deptIds;
}
