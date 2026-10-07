package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.DictTypeSaveRequest;
import com.cloudai.system.service.SysDictTypeManageService;
import com.cloudai.system.vo.SysDictTypeVo;
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

/**
 * 字典类型端点（契约 2026-10-07-dict-api §2，网关前缀 /system/dict/type）。
 */
@RestController
@RequestMapping("/dict/type")
@RequiredArgsConstructor
public class SysDictTypeController {

    private final SysDictTypeManageService manageService;

    /** 分页查询字典类型（全量未删除含停用，id 倒序，VO 出参） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:dict:list')")
    public R<PageResult<SysDictTypeVo>> page(PageQuery query) {
        PageResult<SysDictTypeVo> page = manageService.pageList(query);
        return R.ok(page);
    }

    /** 新增字典类型（返回新类型 ID；dictKey 库级唯一，重复 3009） */
    @PostMapping
    @PreAuthorize("hasAuthority('system:dict:add')")
    public R<Long> add(@RequestBody DictTypeSaveRequest req) {
        Long id = manageService.save(req);
        return R.ok(id);
    }

    /** 修改字典类型（部分更新语义：null 不更新该列；改键触发唯一性校验排除自身） */
    @PutMapping
    @PreAuthorize("hasAuthority('system:dict:edit')")
    public R<Void> edit(@RequestBody DictTypeSaveRequest req) {
        manageService.update(req);
        return R.ok();
    }

    /** 删除字典类型（逻辑删除；该类型下存在字典项时 3011 禁删，无级联） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:dict:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.delete(id);
        return R.ok();
    }
}
