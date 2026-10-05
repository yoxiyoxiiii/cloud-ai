package com.cloudai.common.mybatis.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类：审计时间字段自动填充 + 逻辑删除。
 * createBy/updateBy 由登录上下文（LoginUser.account）自动填充，匿名场景留空。
 * 适用范围：仅对 MP BaseMapper CRUD 自动生效；system 模块手写 SQL（mapper XML）显式维护 deleted 与审计字段，约定见 SysUserMapper.xml 头注。
 */
@Getter
@Setter
public abstract class BaseEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 创建时间（插入填充） */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /** 更新时间（插入和更新都填充） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /** 创建人（插入填充，来自登录上下文） */
    @TableField(fill = FieldFill.INSERT)
    private String createBy;

    /** 更新人（插入和更新都填充，来自登录上下文） */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updateBy;

    /** 逻辑删除：0 未删除 1 已删除 */
    @TableLogic
    private Integer deleted;
}
