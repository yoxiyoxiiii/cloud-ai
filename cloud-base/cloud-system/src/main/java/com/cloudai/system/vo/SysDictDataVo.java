package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 字典项视图对象（契约 2026-10-07-dict-api §4.2；Controller 出参隔离 DB 实体：不含 deleted）
 */
@Data
public class SysDictDataVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 归属类型 id */
    private Long typeId;

    private String label;

    private String value;

    /** 排序号（DDL NOT NULL DEFAULT 0，恒有值） */
    private Integer sort;

    /** 0正常 1停用（字典见 SysDictData.StatusEnum） */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
