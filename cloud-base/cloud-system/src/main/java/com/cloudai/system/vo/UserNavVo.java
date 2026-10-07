package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 当前用户导航树节点（契约 2026-10-07-menu-nav-api.md §2）。
 * 最小暴露面：仅 M/C 两型，不含 perms/status/审计字段（可见性已由查询过滤保证，导航渲染不需要）。
 */
@Data
public class UserNavVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 父菜单 id，根为 0；孤儿提升到根级后保留原值 */
    private Long parentId;

    /** 显示名（侧边菜单/路由标题） */
    private String name;

    /** M目录 C菜单——无 F（按钮在 SQL 层已排除） */
    private String type;

    /** 路由路径；M 恒空串，C 以 / 开头（进入本树的 C 恒非空） */
    private String path;

    /** 图标名；空串 = 前端默认图标 */
    private String icon;

    /** 排序号，同级升序 */
    private Integer sort;

    /** 子节点数组；叶子为空数组而非 null，M 的 children 只含 C */
    private List<UserNavVo> children = new ArrayList<>();
}
