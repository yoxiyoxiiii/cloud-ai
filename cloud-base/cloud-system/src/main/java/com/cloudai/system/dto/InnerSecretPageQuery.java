package com.cloudai.system.dto;

import com.cloudai.common.core.domain.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * 密钥分页筛选（契约 2026-11-09-inner-secret-admin-api §1）：仅 status 精确过滤可选，
 * 场景固定 inner 不开放入参；流水全量可见（含已过期/已作废行）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class InnerSecretPageQuery extends PageQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态过滤：1生效中 2重叠期 3已过期 4已作废（缺省=全部） */
    private Integer status;
}
