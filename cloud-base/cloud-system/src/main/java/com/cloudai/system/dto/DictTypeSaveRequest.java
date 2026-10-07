package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 字典类型新增/修改入参（契约 2026-10-07-dict-api §2.2/§2.3：
 * 新增必填 dictName/dictKey；修改必填 id，部分更新语义 null 不更新该列）
 */
@Data
public class DictTypeSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 修改必填；新增忽略 */
    private Long id;

    /** 新增必填（非空白）；修改空白串拦截、null 不更新 */
    private String dictName;

    /** 新增必填（非空白，库级唯一）；修改空白串拦截、null 不更新，改键触发唯一性校验 */
    private String dictKey;

    /** 0正常 1停用；null 落库默认 0（新增）/ 不更新（修改） */
    private Integer status;
}
