package com.cloudai.system.controller.manage;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.NotifyTemplateAddRequest;
import com.cloudai.system.dto.NotifyTemplateEditRequest;
import com.cloudai.system.dto.NotifyTemplatePageQuery;
import com.cloudai.system.service.NotifyTemplateManageService;
import com.cloudai.system.vo.NotifyTemplateVo;
import jakarta.validation.Valid;
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
 * 站内信模板管理端点（契约 2026-11-08-notify-api §1，网关前缀 /system/notify/template）。
 * 内置模板（isBuiltin=1）内容可改、禁删（3049）。
 */
@RestController
@RequestMapping("/notify/template")
@RequiredArgsConstructor
public class NotifyTemplateManageController {

    private final NotifyTemplateManageService manageService;

    /** 模板分页（name/code 模糊 + type 精确，id 倒序） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('system:notify:list')")
    public R<PageResult<NotifyTemplateVo>> page(NotifyTemplatePageQuery query) {
        PageResult<NotifyTemplateVo> page = manageService.pageList(query);
        return R.ok(page);
    }

    /** 模板详情（列表与详情同构 VO；不存在或已删 3044） */
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('system:notify:list')")
    public R<NotifyTemplateVo> detail(@PathVariable("id") Long id) {
        NotifyTemplateVo template = manageService.findById(id);
        return R.ok(template);
    }

    /** 新增模板（参数不合法 1001 / code 重复 3043） */
    @PostMapping
    @PreAuthorize("hasAuthority('system:notify:add')")
    public R<Void> add(@Valid @RequestBody NotifyTemplateAddRequest req) {
        manageService.save(req);
        return R.ok();
    }

    /** 修改模板（code 允许改同查重 3043；模板不存在 3044） */
    @PutMapping
    @PreAuthorize("hasAuthority('system:notify:edit')")
    public R<Void> edit(@Valid @RequestBody NotifyTemplateEditRequest req) {
        manageService.update(req);
        return R.ok();
    }

    /** 删除模板（逻辑删；不存在 3044 / 内置模板 3049） */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('system:notify:remove')")
    public R<Void> remove(@PathVariable("id") Long id) {
        manageService.delete(id);
        return R.ok();
    }
}
