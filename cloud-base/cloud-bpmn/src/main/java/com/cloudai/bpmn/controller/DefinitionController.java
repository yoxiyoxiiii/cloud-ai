package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.service.DefinitionAppService;
import com.cloudai.bpmn.vo.DefinitionVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流程定义端点（契约 2026-10-07-bpmn-leave-api §4，网关前缀 /bpmn/definition）。
 * 只读——无部署/删除/挂起端点（MVP 边界）。
 */
@RestController
@RequestMapping("/definition")
@RequiredArgsConstructor
public class DefinitionController {

    private final DefinitionAppService definitionAppService;

    /** 定义分页（latestVersion 过滤，key 升序） */
    @GetMapping("/page")
    @PreAuthorize("hasAuthority('bpmn:definition:list')")
    public R<PageResult<DefinitionVo>> page(PageQuery query) {
        PageResult<DefinitionVo> page = definitionAppService.pageList(query);
        return R.ok(page);
    }
}
