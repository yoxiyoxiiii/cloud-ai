package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 字典类型视图对象（契约 2026-10-07-dict-api §4.1；Controller 出参隔离 DB 实体：不含 deleted）
 */
@Data
public class SysDictTypeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String dictName;

    private String dictKey;

    /** 0正常 1停用（字典见 SysDictType.StatusEnum） */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
