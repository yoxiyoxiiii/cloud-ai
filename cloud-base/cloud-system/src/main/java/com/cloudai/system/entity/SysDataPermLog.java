package com.cloudai.system.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 数据权限决策留痕（设计 §3.4）：只插不删的流水表（沿 mq_tx_log 先例），无 update 路径；
 * 每次真实查询求值（list/detail）一条 + 详情被拒补记 deny 一条（设计 D7）。
 */
@Data
public class SysDataPermLog implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 决策对象账号（求值时的登录人） */
    private String account;

    /** 资源标识 */
    private String resource;

    /** 操作：list / detail / deny */
    private String operation;

    /** 命中行规则ID集合（逗号分隔；NULL=无规则命中走默认档） */
    private String ruleIds;

    /** 决策摘要（命中规则与档位可读描述） */
    private String ruleDigest;

    /** 行范围结论：all=过滤豁免 / accounts=N / empty=空集 */
    private String scopeSummary;

    /** 列决策结论（如 reason:脱敏;title:隐藏；NULL=无列动作） */
    private String columnSummary;

    /** 业务键（detail/deny 时为目标单据 id；list 为 NULL） */
    private String businessKey;

    /** 决策时间（DDL DEFAULT CURRENT_TIMESTAMP 兜底，Service 显式传值对齐） */
    private LocalDateTime createTime;
}
