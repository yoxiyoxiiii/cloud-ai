package com.cloudai.system.api.dataperm;

import lombok.Data;

import java.io.Serializable;

/**
 * advice 单测脚手架：自引用白名单 VO（防环用例——IdentitySet 剪枝 + 白名单消费后不下钻其字段）。
 */
@Data
@DataPermVo
public class SelfRefVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String title;

    private SelfRefVo next;
}
