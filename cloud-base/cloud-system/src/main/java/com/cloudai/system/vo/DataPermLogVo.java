package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据权限决策留痕行（契约 §6.6）：求值要素快照，排查链数据源。
 */
@Data
public class DataPermLogVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 留痕 id（Long→String 序列化） */
    private Long id;

    /** 决策对象账号 */
    private String account;

    /** 资源 */
    private String resource;

    /** list / detail / deny */
    private String operation;

    /** 命中行规则 id 逗号串（null=无规则默认档） */
    private String ruleIds;

    /** 决策摘要 */
    private String ruleDigest;

    /** all / accounts=N / empty（deny 行固定 deny） */
    private String scopeSummary;

    /** 列结论（null=无列动作） */
    private String columnSummary;

    /** detail/deny 时目标单据 id */
    private String businessKey;

    /** 决策时间 */
    private LocalDateTime createTime;
}
