package com.cloudai.system.controller;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.MenuTreeNode;
import com.cloudai.system.entity.SysMenu;
import com.cloudai.system.service.SysMenuManageService;
import com.cloudai.system.util.MenuTreeBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/menu")
@RequiredArgsConstructor
public class SysMenuController {

    private final SysMenuManageService manageService;

    /** 查询菜单树（目录/菜单/按钮三级） */
    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public R<List<MenuTreeNode>> tree() {
        List<SysMenu> menus = manageService.listAll();
        List<MenuTreeNode> tree = MenuTreeBuilder.build(menus);
        return R.ok(tree);
    }

    /** 新增菜单/按钮（返回新菜单 ID） */
    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:add')")
    public R<Long> add(@RequestBody SysMenu menu) {
        Long menuId = manageService.add(menu);
        return R.ok(menuId);
    }

    /** 修改菜单/按钮信息 */
    @PutMapping
    @PreAuthorize("hasAuthority('system:menu:edit')")
    public R<Void> edit(@RequestBody SysMenu menu) {
        manageService.edit(menu);
        return R.ok();
    }

    /** 删除菜单（须无子级；逻辑删除并解除角色绑定） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.remove(id);
        return R.ok();
    }
}
