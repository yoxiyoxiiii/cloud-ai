package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.system.dto.DictDataSaveRequest;
import com.cloudai.system.service.SysDictDataManageService;
import com.cloudai.system.vo.SysDictDataVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 字典项端点（契约 2026-10-07-dict-api §3，网关前缀 /system/dict/data）。
 */
@RestController
@RequestMapping("/dict/data")
@RequiredArgsConstructor
public class SysDictDataController {

    private final SysDictDataManageService manageService;

    /** 分页查询某类型字典项（typeId 必填——缺失 1002、类型不存在 3008；sort 升序 id 升序，VO 出参） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:dict:list')")
    public R<PageResult<SysDictDataVo>> page(PageQuery query,
                                             @RequestParam(value = "typeId", required = false) Long typeId) {
        PageResult<SysDictDataVo> page = manageService.pageList(typeId, query);
        return R.ok(page);
    }

    /** 新增字典项（返回新项 ID；同类型内 value 唯一，重复 3012） */
    @PostMapping
    @PreAuthorize("hasAuthority('system:dict:add')")
    public R<Long> add(@RequestBody DictDataSaveRequest req) {
        Long id = manageService.save(req);
        return R.ok(id);
    }

    /** 修改字典项（部分更新语义：null 不更新该列；typeId 变更即项迁移，按生效 (typeId,value) 查重排除自身） */
    @PutMapping
    @PreAuthorize("hasAuthority('system:dict:edit')")
    public R<Void> edit(@RequestBody DictDataSaveRequest req) {
        manageService.update(req);
        return R.ok();
    }

    /** 删除字典项（逻辑删除；不校验所属类型是否存活，可治理遗留数据） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:dict:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.delete(id);
        return R.ok();
    }

    /**
     * 按字典键取启用项（消费口径：类型启用未删 ∧ 项启用未删，sort,id 升序；数据经 Redis 缓存回源）。
     * 无 @PreAuthorize——登录即可（user-nav 先例：表单下拉是登录用户基础能力）；
     * dictKey 不存在/类型停用/无启用项一律 200 + 空数组（契约 §2.1 容错语义，零业务错误码）。
     */
    @GetMapping("/type/{dictKey}")
    public R<List<DictItemEntry>> listByDictKey(@PathVariable("dictKey") String dictKey) {
        List<DictItemEntry> items = manageService.listByDictKey(dictKey);
        return R.ok(items);
    }
}
