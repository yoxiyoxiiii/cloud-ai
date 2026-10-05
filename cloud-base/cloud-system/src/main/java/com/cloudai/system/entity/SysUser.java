package com.cloudai.system.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.cloudai.common.mybatis.domain.BaseEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String account;

    private String nickname;

    /** BCrypt 散列；序列化时不出现在响应中 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /** 0正常 1停用 */
    private Integer status;
}
