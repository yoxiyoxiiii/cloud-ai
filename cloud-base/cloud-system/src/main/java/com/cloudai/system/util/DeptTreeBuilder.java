package com.cloudai.system.util;

import com.cloudai.system.dto.DeptTreeNode;
import com.cloudai.system.entity.SysDept;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 部门树构建（沿 MenuTreeBuilder 先例）：按 parentId 组装、sort 升序（次序 id）；
 * 父节点缺失的孤儿挂到根级（数据问题不致部门消失）。
 */
public final class DeptTreeBuilder {

    private DeptTreeBuilder() {
    }

    public static List<DeptTreeNode> build(List<SysDept> depts) {
        Map<Long, List<DeptTreeNode>> byParent = depts.stream()
                .map(DeptTreeBuilder::toNode)
                .collect(Collectors.groupingBy(DeptTreeNode::getParentId));
        List<DeptTreeNode> roots = new ArrayList<>(byParent.getOrDefault(0L, List.of()));
        // 孤儿节点（父不存在于集合中）也挂到根级
        depts.stream().map(SysDept::getParentId).distinct()
                .filter(pid -> pid != 0 && depts.stream().noneMatch(d -> d.getId().equals(pid)))
                .forEach(pid -> roots.addAll(byParent.getOrDefault(pid, List.of())));
        sortNodes(roots);
        byParent.values().forEach(DeptTreeBuilder::sortNodes);
        roots.forEach(root -> fillChildren(root, byParent));
        return roots;
    }

    private static void sortNodes(List<DeptTreeNode> nodes) {
        nodes.sort(Comparator.comparing(DeptTreeNode::getSort,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DeptTreeNode::getId, Comparator.nullsLast(Comparator.naturalOrder())));
    }

    private static void fillChildren(DeptTreeNode node, Map<Long, List<DeptTreeNode>> byParent) {
        node.setChildren(new ArrayList<>(byParent.getOrDefault(node.getId(), List.of())));
        node.getChildren().forEach(child -> fillChildren(child, byParent));
    }

    private static DeptTreeNode toNode(SysDept d) {
        DeptTreeNode node = new DeptTreeNode();
        node.setId(d.getId());
        node.setParentId(d.getParentId());
        node.setName(d.getName());
        node.setSort(d.getSort());
        node.setStatus(d.getStatus());
        node.setCreateTime(d.getCreateTime());
        node.setBuiltin(Integer.valueOf(1).equals(d.getIsBuiltin()));
        return node;
    }
}
