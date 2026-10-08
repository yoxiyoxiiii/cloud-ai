package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.service.DefinitionAppService;
import com.cloudai.bpmn.vo.DefinitionVo;
import com.cloudai.bpmn.vo.DefinitionXmlVo;
import com.cloudai.bpmn.vo.DeployResultVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 流程定义端点（契约 2026-10-08-bpmn-diagram-designer-api §0.1/§2，网关前缀 /bpmn/definition）。
 * 有 xml/deploy 两端点；无删除/挂起/激活端点（拍板：删除永不做）。
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

    /** 定义 XML（部署时原始资源，UTF-8）。definitionId 形态 key:version:generated——
     *  冒号为合法路径字符无需编码，@PathVariable 直收（契约 §1）；perms 属只读查看面 */
    @GetMapping("/{id}/xml")
    @PreAuthorize("hasAuthority('bpmn:definition:list')")
    public R<DefinitionXmlVo> xml(@PathVariable("id") String id) {
        DefinitionXmlVo vo = definitionAppService.findXmlById(id);
        return R.ok(vo);
    }

    /** 部署流程（multipart 而非 JSON body——文件字节按 UTF-8 原样传输，契约 §2.2/spec D4-D5；
     *  高权限端点：部署的 BPMN ${...} 表达式运行时可执行，缓解面见 spec D6（perms 仅绑 admin、
     *  2MB 上限、引擎 schema 校验）；校验在 Service 前置手写（multipart 非 JSON，Bean Validation 不适用） */
    @PostMapping(value = "/deploy", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('bpmn:definition:deploy')")
    public R<DeployResultVo> deploy(@RequestPart("file") MultipartFile file) {
        DeployResultVo result = definitionAppService.saveDeployment(file);
        return R.ok(result);
    }
}
