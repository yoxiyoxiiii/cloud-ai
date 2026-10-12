package com.cloudai.system.api.dataperm;

import lombok.Data;

import java.io.Serializable;

/**
 * advice 单测脚手架：白名单列载体 VO（同形 SysLeaveVo 列面——title/reason 可配列 +
 * statusLabel/id 非列字段）。真类不 mock（D14 教训：反射映射类缺陷 mock 不可见）。
 * 留作 advice 行为演进的回归基线（设计移交备忘 8，勿删）。
 */
@Data
@DataPermVo
public class TestColumnVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String title;

    private String reason;

    /** 非列字段：断言树遍历只改列字段（用例 8） */
    private String statusLabel;

    /** 非列字段：断言原值保持 */
    private Long id;
}
