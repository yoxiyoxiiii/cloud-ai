package com.cloudai.system.util;

import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.vo.UserNavVo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 当前用户导航树构建（契约 2026-10-07-menu-nav-api.md §2）：按 parentId 组装、sort 升序、
 * 自底向上剪掉无可见子级的 M 目录（空目录=噪声）、父不在可见集的孤儿 C 提升根级
 * （parentId 保留原值，数据问题不致菜单消失）。输入约定：F 型与空 path 的 C 已在 SQL 层排除。
 */
public final class NavTreeBuilder {

    /** 目录类型标识（sys_menu.type 字典值，非状态码故不入实体枚举） */
    private static final String TYPE_DIR = "M";

    private NavTreeBuilder() {
    }

    public static List<UserNavVo> build(List<SysMenu> menus) {
        Map<Long, List<UserNavVo>> byParent = menus.stream()
                .map(NavTreeBuilder::toNode)
                .collect(Collectors.groupingBy(UserNavVo::getParentId));
        List<UserNavVo> roots = new ArrayList<>(byParent.getOrDefault(0L, List.of()));
        // 孤儿节点（父不存在于可见集）也挂到根级，parentId 保留原值
        menus.stream().map(SysMenu::getParentId).distinct()
                .filter(pid -> pid != 0 && menus.stream().noneMatch(m -> m.getId().equals(pid)))
                .forEach(pid -> roots.addAll(byParent.getOrDefault(pid, List.of())));
        sortNodes(roots);
        byParent.values().forEach(NavTreeBuilder::sortNodes);
        roots.forEach(root -> fillChildren(root, byParent));
        return pruneEmptyDirs(roots);
    }

    private static void sortNodes(List<UserNavVo> nodes) {
        nodes.sort(Comparator.comparing(UserNavVo::getSort,
                Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private static void fillChildren(UserNavVo node, Map<Long, List<UserNavVo>> byParent) {
        node.setChildren(new ArrayList<>(byParent.getOrDefault(node.getId(), List.of())));
        node.getChildren().forEach(child -> fillChildren(child, byParent));
    }

    /** 自底向上剪枝：M 目录的可见子级全被过滤（F 排除/空 path/停用）则不渲染（如种子"认证管理"） */
    private static List<UserNavVo> pruneEmptyDirs(List<UserNavVo> nodes) {
        List<UserNavVo> kept = new ArrayList<>();
        for (UserNavVo node : nodes) {
            node.setChildren(pruneEmptyDirs(node.getChildren()));
            if (!TYPE_DIR.equals(node.getType()) || !node.getChildren().isEmpty()) {
                kept.add(node);
            }
        }
        return kept;
    }

    private static UserNavVo toNode(SysMenu m) {
        UserNavVo node = new UserNavVo();
        node.setId(m.getId());
        node.setParentId(m.getParentId());
        node.setName(m.getName());
        node.setType(m.getType());
        node.setPath(m.getPath());
        node.setIcon(m.getIcon());
        node.setSort(m.getSort());
        return node;
    }
}
