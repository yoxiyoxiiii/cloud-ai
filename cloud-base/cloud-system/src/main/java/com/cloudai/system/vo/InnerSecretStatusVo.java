package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 轮换状态卡出参 VO（契约 2026-11-09-inner-secret-admin-api §2，只读）。
 * 参数三字段为 Nacos cloud.inner.rotation.* 回显（运维改，界面不做编辑端点）；
 * redisConsistent=false 表示发布落后（任务一分钟内自愈），界面可提示等待自动重发。
 */
@Data
public class InnerSecretStatusVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 密钥有效期（小时，参数回显） */
    private Integer validityHours;

    /** 重叠窗（秒，参数回显） */
    private Integer overlapSeconds;

    /** 提前轮换预算（小时，参数回显） */
    private Integer leadHours;

    /** 最新生效代数（无生效密钥=0） */
    private Integer currentSeq;

    /** 当前活跃集行数（status∈{1,2} 且未到退场时刻，通常 1-2） */
    private Integer activeCount;

    /** 预计下次轮换时刻（=最新生效行过期时间−提前预算；无生效行 null） */
    private LocalDateTime nextRotationTime;

    /** Redis 活跃集 version（键缺失 null） */
    private Integer redisVersion;

    /** Redis version == DB 最新 seq 且键存在 */
    private Boolean redisConsistent;
}
