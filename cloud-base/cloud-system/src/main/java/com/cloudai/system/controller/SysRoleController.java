package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.RoleMenuRequest;
import com.cloudai.system.entity.SysRole;
import com.cloudai.system.service.SysRoleManageService;
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
@RequestMapping("/role")
@RequiredArgsConstructor
public class SysRoleController {

    private final SysRoleManageService manageService;

    /** 分页查询角色列表 */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<PageResult<SysRole>> page(PageQuery query) {
        PageResult<SysRole> page = manageService.page(query);
        return R.ok(page);
    }

    /** 查询全部启用角色（下拉选项用） */
    @GetMapping("/list")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<List<SysRole>> list() {
        List<SysRole> roles = manageService.listAll();
        return R.ok(roles);
    }

    /** 新增角色（返回新角色 ID） */
    @PostMapping
    @PreAuthorize("hasAuthority('system:role:add')")
    public R<Long> add(@RequestBody SysRole role) {
        Long roleId = manageService.add(role);
        return R.ok(roleId);
    }

    /** 修改角色基本信息（名称/标识/状态） */
    @PutMapping
    @PreAuthorize("hasAuthority('system:role:edit')")
    public R<Void> edit(@RequestBody SysRole role) {
        manageService.edit(role);
        return R.ok();
    }

    /** 删除角色（逻辑删除并解除菜单/用户绑定） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:role:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.remove(id);
        return R.ok();
    }

    /** 全量分配角色菜单权限（先清后插） */
    @PutMapping("/menu")
    @PreAuthorize("hasAuthority('system:role:assignMenu')")
    public R<Void> assignMenus(@RequestBody RoleMenuRequest req) {
        manageService.assignMenus(req.getRoleId(), req.getMenuIds());
        return R.ok();
    }

    /** 查询角色已绑定的菜单 ID 列表 */
    @GetMapping("/{id}/menus")
    @PreAuthorize("hasAuthority('system:role:list')")
    public R<List<Long>> menuIds(@PathVariable("id") Long id) {
        List<Long> menuIds = manageService.menuIdsOf(id);
        return R.ok(menuIds);
    }
}
