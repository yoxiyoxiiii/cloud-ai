package com.cloudai.system.util;

import com.cloudai.system.dto.MenuTreeNode;
import com.cloudai.system.entity.SysMenu;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 菜单树构建：按 parentId 组装、sort 升序；父节点缺失的孤儿挂到根级（数据问题不致菜单消失）。
 */
public final class MenuTreeBuilder {

    private MenuTreeBuilder() {
    }

    public static List<MenuTreeNode> build(List<SysMenu> menus) {
        Map<Long, List<MenuTreeNode>> byParent = menus.stream()
                .map(MenuTreeBuilder::toNode)
                .collect(Collectors.groupingBy(MenuTreeNode::getParentId));
        List<MenuTreeNode> roots = new ArrayList<>(byParent.getOrDefault(0L, List.of()));
        // 孤儿节点（父不存在于集合中）也挂到根级
        menus.stream().map(SysMenu::getParentId).distinct()
                .filter(pid -> pid != 0 && menus.stream().noneMatch(m -> m.getId().equals(pid)))
                .forEach(pid -> roots.addAll(byParent.getOrDefault(pid, List.of())));
        sortNodes(roots);
        byParent.values().forEach(MenuTreeBuilder::sortNodes);
        roots.forEach(root -> fillChildren(root, byParent));
        return roots;
    }

    private static void sortNodes(List<MenuTreeNode> nodes) {
        nodes.sort(Comparator.comparing(MenuTreeNode::getSort,
                Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private static void fillChildren(MenuTreeNode node, Map<Long, List<MenuTreeNode>> byParent) {
        node.setChildren(new ArrayList<>(byParent.getOrDefault(node.getId(), List.of())));
        node.getChildren().forEach(child -> fillChildren(child, byParent));
    }

    private static MenuTreeNode toNode(SysMenu m) {
        MenuTreeNode node = new MenuTreeNode();
        node.setId(m.getId());
        node.setParentId(m.getParentId());
        node.setName(m.getName());
        node.setPerms(m.getPerms());
        node.setType(m.getType());
        node.setPath(m.getPath());
        node.setIcon(m.getIcon());
        node.setSort(m.getSort());
        node.setStatus(m.getStatus());
        node.setCreateBy(m.getCreateBy());
        node.setCreateTime(m.getCreateTime());
        node.setUpdateBy(m.getUpdateBy());
        node.setUpdateTime(m.getUpdateTime());
        return node;
    }
}
