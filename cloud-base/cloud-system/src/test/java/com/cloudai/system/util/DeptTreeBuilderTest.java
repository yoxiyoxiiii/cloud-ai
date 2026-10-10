package com.cloudai.system.util;

import com.cloudai.system.dto.DeptTreeNode;
import com.cloudai.system.entity.SysDept;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 部门树构建单测（沿 MenuTreeBuilderTest 先例）：多层组装 / 孤儿挂根森林 / 空表。
 */
class DeptTreeBuilderTest {

    @Test
    void build_multiLevel_assemblesForestWithSortOrder() {
        List<DeptTreeNode> roots = DeptTreeBuilder.build(List.of(
                dept(1L, 0L, "root", 0),
                dept(2L, 1L, "childB", 2),
                dept(3L, 1L, "childA", 1),
                dept(4L, 3L, "grandChild", 0),
                dept(9L, 0L, "root2", 1)));

        // 两棵根树（sort 升序）；子层按 sort 排（childA 前 childB 后）；孙层挂 childA 下
        assertThat(roots).extracting(DeptTreeNode::getName).containsExactly("root", "root2");
        List<DeptTreeNode> level1 = roots.get(0).getChildren();
        assertThat(level1).extracting(DeptTreeNode::getName).containsExactly("childA", "childB");
        assertThat(level1.get(0).getChildren()).extracting(DeptTreeNode::getName).containsExactly("grandChild");
        assertThat(level1.get(1).getChildren()).isEmpty();
    }

    @Test
    void build_orphanAttachesToRootForest() {
        // 父 99 不在集合中（数据问题）——孤儿不消失，挂根级（设计取舍同 MenuTreeBuilder）
        List<DeptTreeNode> roots = DeptTreeBuilder.build(List.of(
                dept(1L, 0L, "root", 0),
                dept(5L, 99L, "orphan", 1)));

        assertThat(roots).extracting(DeptTreeNode::getName).containsExactly("root", "orphan");
    }

    @Test
    void build_emptyInputReturnsEmptyForest() {
        assertThat(DeptTreeBuilder.build(List.of())).isEmpty();
    }

    private SysDept dept(Long id, Long parentId, String name, int sort) {
        SysDept dept = new SysDept();
        dept.setId(id);
        dept.setParentId(parentId);
        dept.setName(name);
        dept.setSort(sort);
        dept.setStatus(0);
        dept.setIsBuiltin(0);
        return dept;
    }
}
