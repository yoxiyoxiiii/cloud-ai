package com.cloudai.system.api.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 跨服务 deny 补痕入参（契约 2026-10-10-dataperm-component-api §2.2）：详情被行级拒绝后
 * 消费方补插一条 operation=deny 留痕（D13/D23 同语义），不返回任何范围数据。
 * 注解口径同 {@link DataPermEvaluateRequest}。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataPermDenyRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 越权尝试者账号 */
    private String account;

    /** 资源标识（空白或未注册 3034） */
    private String resource;

    /** 目标单据 id */
    private String businessKey;
}
