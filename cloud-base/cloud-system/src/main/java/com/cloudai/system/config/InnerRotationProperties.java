package com.cloudai.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * inner 面共享密钥轮换参数（契约 2026-11-09-inner-access-secret-api §3 / admin 契约 §2）。
 * 架构口径非密钥：仓库化默认值、Nacos cloud-system.yaml 可覆盖（运维双租户整文件重发）；
 * 管理界面只读回显不做编辑端点。改参数不追溯既有密钥行（expire_time 创建时快照）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloud.inner.rotation")
public class InnerRotationProperties {

    /** 密钥有效期（小时）：轮换生成的每把钥生效起算的寿命 */
    private int validityHours = 168;

    /** 重叠窗（秒）：轮换后旧钥仍被接受的过渡期，须 ≥ 收发双方缓存 TTL + 任务周期之和 */
    private int overlapSeconds = 180;

    /** 提前轮换预算（小时）：最新钥距过期不足该值时任务触发轮换 */
    private int leadHours = 24;
}
