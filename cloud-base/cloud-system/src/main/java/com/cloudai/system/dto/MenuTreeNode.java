package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class MenuTreeNode implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;
    private Long parentId;
    private String name;
    private String perms;
    private String type;
    private Integer sort;

    /** 0 正常 1 停用（契约 v2 新增） */
    private Integer status;

    /** 创建人（种子数据可能为 null；契约 v2 新增） */
    private String createBy;

    /** 创建时间（全局 Jackson 序列化为 yyyy-MM-dd HH:mm:ss；契约 v2 新增） */
    private LocalDateTime createTime;

    /** 更新人（契约 v2 新增） */
    private String updateBy;

    /** 更新时间（同 createTime 格式；契约 v2 新增） */
    private LocalDateTime updateTime;

    private List<MenuTreeNode> children = new ArrayList<>();
}
