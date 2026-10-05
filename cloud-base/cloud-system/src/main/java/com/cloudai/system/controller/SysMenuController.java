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

    @GetMapping("/tree")
    @PreAuthorize("hasAuthority('system:menu:list')")
    public R<List<MenuTreeNode>> tree() {
        return R.ok(MenuTreeBuilder.build(manageService.listAll()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:menu:add')")
    public R<Long> add(@RequestBody SysMenu menu) {
        return R.ok(manageService.add(menu));
    }

    @PutMapping
    @PreAuthorize("hasAuthority('system:menu:edit')")
    public R<Void> edit(@RequestBody SysMenu menu) {
        manageService.edit(menu);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:menu:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.remove(id);
        return R.ok();
    }
}
