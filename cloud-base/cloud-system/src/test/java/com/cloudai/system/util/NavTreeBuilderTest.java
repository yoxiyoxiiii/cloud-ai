package com.cloudai.system.util;

import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.vo.UserNavVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 导航树构建测试（契约 2026-10-07-menu-nav-api.md §2）。
 * 输入侧约定：F 型与空 path 的 C 已被 SQL 排除，剪枝场景以"仅 M 无子"输入模拟。
 */
class NavTreeBuilderTest {

    private SysMenu menu(Long id, Long parentId, String name, String type, String path, int sort) {
        SysMenu m = new SysMenu();
        m.setId(id);
        m.setParentId(parentId);
        m.setName(name);
        m.setType(type);
        m.setPath(path);
        m.setSort(sort);
        return m;
    }

    @Test
    void buildsTreeByParentAndSort() {
        List<SysMenu> menus = List.of(
                menu(10L, 0L, "系统管理", "M", "", 1),
                menu(13L, 10L, "菜单管理", "C", "/system/menu", 3),
                menu(11L, 10L, "用户管理", "C", "/system/user", 1),
                menu(12L, 10L, "角色管理", "C", "/system/role", 2));
        List<UserNavVo> tree = NavTreeBuilder.build(menus);
        assertThat(tree).hasSize(1);
        UserNavVo root = tree.get(0);
        assertThat(root.getName()).isEqualTo("系统管理");
        assertThat(root.getType()).isEqualTo("M");
        assertThat(root.getPath()).isEmpty();
        assertThat(root.getChildren()).extracting(UserNavVo::getName)
                .containsExactly("用户管理", "角色管理", "菜单管理");
        assertThat(root.getChildren()).extracting(UserNavVo::getPath)
                .containsExactly("/system/user", "/system/role", "/system/menu");
    }

    @Test
    void dirWithoutVisibleChildrenPruned() {
        // 种子"认证管理"(20) 唯一 C 子级(21) 无 path 被 SQL 滤空 → 输入侧仅剩 M → 剪掉不渲染
        List<UserNavVo> tree = NavTreeBuilder.build(List.of(
                menu(20L, 0L, "认证管理", "M", "", 2)));
        assertThat(tree).isEmpty();
    }

    @Test
    void orphanCAttachedToRootKeepingParentId() {
        // C 的父 M 不在可见集（直连 API 只绑 C 的脏数据）→ 挂根级，parentId 保留原值
        List<UserNavVo> tree = NavTreeBuilder.build(List.of(
                menu(11L, 10L, "用户管理", "C", "/system/user", 1),
                menu(12L, 10L, "角色管理", "C", "/system/role", 2)));
        assertThat(tree).extracting(UserNavVo::getName)
                .containsExactly("用户管理", "角色管理");
        assertThat(tree).allSatisfy(n -> assertThat(n.getParentId()).isEqualTo(10L));
    }

    @Test
    void rootLevelCStayAtRoot() {
        List<UserNavVo> tree = NavTreeBuilder.build(List.of(
                menu(30L, 0L, "直挂菜单", "C", "/standalone", 5)));
        assertThat(tree).hasSize(1);
        assertThat(tree.get(0).getParentId()).isEqualTo(0L);
        assertThat(tree.get(0).getPath()).isEqualTo("/standalone");
    }

    @Test
    void leafChildrenIsEmptyArrayNotNull() {
        List<UserNavVo> tree = NavTreeBuilder.build(List.of(
                menu(10L, 0L, "系统管理", "M", "", 1),
                menu(11L, 10L, "用户管理", "C", "/system/user", 1)));
        assertThat(tree.get(0).getChildren().get(0).getChildren())
                .isNotNull()
                .isEmpty();
    }
}
