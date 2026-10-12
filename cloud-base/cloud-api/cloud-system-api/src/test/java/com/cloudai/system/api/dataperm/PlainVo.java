package com.cloudai.system.api.dataperm;

import lombok.Data;

import java.io.Serializable;

/**
 * advice 单测脚手架：无注解普通 VO（白名单外树——上下文空也不触发 3042 的负例）。
 */
@Data
public class PlainVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String name;

    private Long id;
}
