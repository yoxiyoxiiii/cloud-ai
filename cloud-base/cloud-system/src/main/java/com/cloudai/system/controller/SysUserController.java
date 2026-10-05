package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.ResetPasswordRequest;
import com.cloudai.system.dto.UserRoleRequest;
import com.cloudai.system.dto.UserSaveRequest;
import com.cloudai.system.service.SysUserManageService;
import com.cloudai.system.vo.SysUserVo;
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
@RequestMapping("/user")
@RequiredArgsConstructor
public class SysUserController {

    private final SysUserManageService manageService;

    /** 分页查询用户列表（VO 出参，不含 password/deleted） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<PageResult<SysUserVo>> page(PageQuery query) {
        PageResult<SysUserVo> page = manageService.pageList(query);
        return R.ok(page);
    }

    /** 查询用户详情（VO 出参，不含 password/deleted） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<SysUserVo> detail(@PathVariable("id") Long id) {
        SysUserVo user = manageService.findById(id);
        return R.ok(user);
    }

    /** 新增用户（返回新用户 ID） */
    @PostMapping
    @PreAuthorize("hasAuthority('system:user:add')")
    public R<Long> add(@RequestBody UserSaveRequest req) {
        Long userId = manageService.save(req);
        return R.ok(userId);
    }

    /** 修改用户基本信息（昵称/状态） */
    @PutMapping
    @PreAuthorize("hasAuthority('system:user:edit')")
    public R<Void> edit(@RequestBody UserSaveRequest req) {
        manageService.update(req.getId(), req);
        return R.ok();
    }

    /** 删除用户（逻辑删除并解除角色绑定） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:user:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.delete(id);
        return R.ok();
    }

    /** 管理员重置指定用户密码 */
    @PutMapping("/password/{id}")
    @PreAuthorize("hasAuthority('system:user:resetPwd')")
    public R<Void> resetPassword(@PathVariable("id") Long id, @RequestBody ResetPasswordRequest req) {
        manageService.resetPassword(id, req.getPassword());
        return R.ok();
    }

    /** 全量分配用户角色（先清后插） */
    @PutMapping("/role")
    @PreAuthorize("hasAuthority('system:user:assignRole')")
    public R<Void> assignRoles(@RequestBody UserRoleRequest req) {
        manageService.assignRoles(req.getUserId(), req.getRoleIds());
        return R.ok();
    }

    /** 查询用户已绑定的角色 ID 列表 */
    @GetMapping("/{id}/roles")
    @PreAuthorize("hasAuthority('system:user:list')")
    public R<List<Long>> roleIds(@PathVariable("id") Long id) {
        List<Long> roleIds = manageService.listRoleIds(id);
        return R.ok(roleIds);
    }
}
