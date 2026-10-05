package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.UserRoleRequest;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.service.SysUserManageService;
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
import java.util.Map;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserManageService manageService;

    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<PageResult<SysUser>> page(PageQuery query) {
        PageResult<SysUser> page = manageService.page(query);
        return R.ok(page);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<SysUser> detail(@PathVariable("id") Long id) {
        SysUser user = manageService.detail(id);
        return R.ok(user);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('system:user:add')")
    public R<Long> add(@RequestBody UserSaveRequest req) {
        Long userId = manageService.add(req);
        return R.ok(userId);
    }

    @PutMapping
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<Void> edit(@RequestBody UserSaveRequest req) {
        manageService.edit(req.getId(), req);
        return R.ok();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.remove(id);
        return R.ok();
    }

    @PutMapping("/password/{id}")
    @PreAuthorize("hasAuthority('system:user:resetPwd')")
    public R<Void> resetPassword(@PathVariable("id") Long id, @RequestBody Map<String, String> body) {
        manageService.resetPassword(id, body.get("password"));
        return R.ok();
    }

    @PutMapping("/role")
    @PreAuthorize("hasAuthority('system:user:assignRole')")
    public R<Void> assignRoles(@RequestBody UserRoleRequest req) {
        manageService.assignRoles(req.getUserId(), req.getRoleIds());
        return R.ok();
    }

    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<List<Long>> roleIds(@PathVariable("id") Long id) {
        List<Long> roleIds = manageService.roleIdsOf(id);
        return R.ok(roleIds);
    }
}
