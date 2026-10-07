package com.cloudai.system.util;

import com.cloudai.system.dto.MenuTreeNode;
import com.cloudai.system.entity.SysMenu;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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

    @Test
    void builtinFlagPassedThroughNullSafe() {
        SysMenu builtinMenu = menu(10L, 0L, "内置菜单", 1);
        builtinMenu.setIsBuiltin(SysMenu.BuiltinEnum.BUILT_IN.getCode());
        SysMenu userMenu = menu(11L, 0L, "用户菜单", 2);
        userMenu.setIsBuiltin(SysMenu.BuiltinEnum.DEFAULT.getCode());
        SysMenu unmarkedMenu = menu(12L, 0L, "未标记菜单", 3);

        List<MenuTreeNode> tree = MenuTreeBuilder.build(List.of(builtinMenu, userMenu, unmarkedMenu));

        // null-safe：is_builtin=1 → true；0/null → false（保护契约 §7.1）
        assertThat(tree).extracting(MenuTreeNode::getBuiltin)
                .containsExactly(true, false, false);
    }

    @Test
    void statusAndAuditFieldsPassedThrough() {
        SysMenu root = menu(10L, 0L, "系统管理", 1);
        root.setStatus(SysMenu.StatusEnum.NORMAL.getCode());
        root.setCreateBy("admin");
        root.setCreateTime(LocalDateTime.of(2026, 10, 5, 20, 0, 0));
        SysMenu disabled = menu(101L, 10L, "停用菜单", 2);
        disabled.setStatus(SysMenu.StatusEnum.DISABLED.getCode());
        disabled.setCreateBy("admin");
        disabled.setCreateTime(LocalDateTime.of(2026, 10, 6, 9, 30, 0));
        disabled.setUpdateBy("admin");
        disabled.setUpdateTime(LocalDateTime.of(2026, 10, 6, 10, 0, 0));

        List<MenuTreeNode> tree = MenuTreeBuilder.build(List.of(root, disabled));

        assertThat(tree).hasSize(1);
        MenuTreeNode rootNode = tree.get(0);
        assertThat(rootNode.getStatus()).isEqualTo(SysMenu.StatusEnum.NORMAL.getCode());
        assertThat(rootNode.getCreateBy()).isEqualTo("admin");
        assertThat(rootNode.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 10, 5, 20, 0, 0));
        assertThat(rootNode.getUpdateBy()).isNull();
        assertThat(rootNode.getUpdateTime()).isNull();
        MenuTreeNode disabledNode = rootNode.getChildren().get(0);
        assertThat(disabledNode.getName()).isEqualTo("停用菜单");
        assertThat(disabledNode.getStatus()).isEqualTo(SysMenu.StatusEnum.DISABLED.getCode());
        assertThat(disabledNode.getCreateBy()).isEqualTo("admin");
        assertThat(disabledNode.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 10, 6, 9, 30, 0));
        assertThat(disabledNode.getUpdateBy()).isEqualTo("admin");
        assertThat(disabledNode.getUpdateTime()).isEqualTo(LocalDateTime.of(2026, 10, 6, 10, 0, 0));
    }
}
