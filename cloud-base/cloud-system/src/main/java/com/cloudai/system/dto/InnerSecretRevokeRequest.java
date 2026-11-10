package com.cloudai.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 密钥作废入参（契约 2026-11-09-inner-secret-admin-api §4）。
 * 作废即终态（追加式流水无删除）；生效时延 ≤ 缓存 TTL（前端确认弹窗须明示非瞬时语义）。
 */
@Data
public class InnerSecretRevokeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 目标密钥记录ID（列表行取）；不存在或已退场 3050 */
    @NotNull
    private Long id;
}
