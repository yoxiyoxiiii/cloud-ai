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

    /** 路由路径；M/F 通常空串（前端约定不采编），C 为 / 开头或空串（契约 2026-10-07-menu-nav-api.md §5.1 增补） */
    private String path;

    /** 图标名，空串 = 默认图标（契约 v2 增补） */
    private String icon;

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
