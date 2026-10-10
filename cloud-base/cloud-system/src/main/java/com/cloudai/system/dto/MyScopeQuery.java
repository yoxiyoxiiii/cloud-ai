package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 我的数据范围自查入参（契约 §3.9：resource 必填，非法 3034；登录即可访问，设计 D12）。
 */
@Data
public class MyScopeQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源标识 */
    private String resource;
}
