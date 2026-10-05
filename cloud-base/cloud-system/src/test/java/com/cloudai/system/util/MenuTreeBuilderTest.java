package com.cloudai.system.util;

import com.cloudai.system.dto.MenuTreeNode;
import com.cloudai.system.entity.SysMenu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MenuTreeBuilderTest {

    private SysMenu menu(Long id, Long parentId, String name, int sort) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setParentId(parentId);
        m.setName(name);
        m.setSort(sort);
        return m;
    }

    @Test
    void buildsTreeByParentAndSort() {
        List<SysMenu> menus = List.of(
                menu(10L, 0L, "系统管理", 1),
                menu(12L, 10L, "角色管理", 2),
                menu(11L, 10L, "用户管理", 1),
                menu(111L, 11L, "用户新增", 1),
                menu(20L, 0L, "认证管理", 2));
        List<MenuTreeNode> tree = MenuTreeBuilder.build(menus);
        assertThat(tree).hasSize(2);
        assertThat(tree.get(0).getName()).isEqualTo("系统管理");
        assertThat(tree.get(0).getChildren()).extracting(MenuTreeNode::getName)
                .containsExactly("用户管理", "角色管理");
        assertThat(tree.get(0).getChildren().get(0).getChildren())
                .extracting(MenuTreeNode::getName).containsExactly("用户新增");
    }

    @Test
    void orphanNodeAttachedToRootLevel() {
        List<SysMenu> menus = List.of(menu(999L, 888L, "孤儿", 0));
        List<MenuTreeNode> tree = MenuTreeBuilder.build(menus);
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getName()).isEqualTo("孤儿");
    }
}
