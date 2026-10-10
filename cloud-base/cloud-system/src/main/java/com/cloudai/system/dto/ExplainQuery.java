package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 模拟解释入参（契约 §3.8：account 非空白 1002、目标用户无效 3032、resource 非法 3034）。
 */
@Data
public class ExplainQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 被模拟账号 */
    private String account;

    /** 资源标识 */
    private String resource;
}
