package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图对象（Controller 出参隔离 DB 实体：不含 password/deleted）
 */
@Data
public class SysUserVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String account;

    private String nickname;

    /** 0正常 1停用（字典见 SysUser.Status） */
    private Integer status;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;
}
