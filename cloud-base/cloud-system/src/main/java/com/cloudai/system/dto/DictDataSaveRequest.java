package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 字典项新增/修改入参（契约 2026-10-07-dict-api §3.2/§3.3：
 * 新增必填 typeId/label/value；修改必填 id，部分更新语义 null 不更新该列）
 */
@Data
public class DictDataSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 修改必填；新增忽略 */
    private Long id;

    /** 归属类型 id：新增必填；修改 null 不更新，变更时校验目标类型存在（项可在类型间迁移） */
    private Long typeId;

    /** 新增必填（非空白）；修改空白串拦截、null 不更新 */
    private String label;

    /** 新增必填（非空白，同类型内唯一）；修改空白串拦截、null 不更新 */
    private String value;

    /** 排序号；null 落库默认 0（新增）/ 不更新（修改） */
    private Integer sort;

    /** 0正常 1停用；null 落库默认 0（新增）/ 不更新（修改） */
    private Integer status;
}
