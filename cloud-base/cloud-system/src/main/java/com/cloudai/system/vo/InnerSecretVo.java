package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 密钥行出参 VO（契约 2026-11-09-inner-secret-admin-api §1）。
 * 真值红线：永不携带密钥明文，仅掩码 secretMask（****+尾4字符）——实体→VO 转换时脱敏。
 */
@Data
public class InnerSecretVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 记录ID（Jackson Long→String） */
    private Long id;

    /** 轮换代数（场景内单调递增） */
    private Integer seq;

    /** 状态：1生效中 2重叠期 3已过期 4已作废 */
    private Integer status;

    /** 掩码（****+尾4字符） */
    private String secretMask;

    /** 来源：1定时任务 2手动操作 */
    private Integer source;

    /** 生效时间（创建即生效） */
    private LocalDateTime activateTime;

    /** 过期时间（创建时快照） */
    private LocalDateTime expireTime;

    /** 退场时间（未被顶替为 null） */
    private LocalDateTime retireTime;

    /** 创建人（定时任务=handler 名） */
    private String createBy;

    /** 创建时间 */
    private LocalDateTime createTime;
}
