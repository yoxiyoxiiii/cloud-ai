package com.cloudai.system.controller;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.DeptSaveRequest;
import com.cloudai.system.dto.DeptTreeNode;
import com.cloudai.system.service.SysDeptManageService;
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

/**
 * 部门端点（契约 2026-10-10-data-permission-api §2，网关前缀 /system/dept）。
 */
@RestController
@RequestMapping("/dept")
@RequiredArgsConstructor
public class SysDeptController {

    private final SysDeptManageService manageService;

    /**
     * 部门树（§2.1）：全量未删除部门（含停用，tag 区分），sort/id 升序内存组森林。
     * hasAnyAuthority 三权限（设计 D12）：树是部门管理与用户表单（部门选择器）共同数据源，
     * 用户管理员无部门管理权也应能选部门。
     */
    @GetMapping("/tree")
    @PreAuthorize("hasAnyAuthority('system:dept:list','system:user:add','system:user:edit')")
    public R<List<DeptTreeNode>> tree() {
        List<DeptTreeNode> tree = manageService.listTree();
        return R.ok(tree);
    }

    /** 新增部门（§2.2；返回新部门 id） */
    @PostMapping
    @PreAuthorize("hasAuthority('system:dept:add')")
    public R<Long> add(@RequestBody DeptSaveRequest req) {
        Long id = manageService.save(req);
        return R.ok(id);
    }

    /** 修改部门（§2.3；MVP 禁改上级部门，部分更新语义 null 不更新） */
    @PutMapping
    @PreAuthorize("hasAuthority('system:dept:edit')")
    public R<Void> edit(@RequestBody DeptSaveRequest req) {
        manageService.update(req);
        return R.ok();
    }

    /** 删除部门（§2.4；内置 3029 / 有子部门或挂载在职用户 3030 拦截，逻辑删除） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:dept:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.delete(id);
        return R.ok();
    }
}
