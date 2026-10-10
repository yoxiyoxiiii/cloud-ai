package com.cloudai.system.controller;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.DataPermLogPageQuery;
import com.cloudai.system.dto.DataPermRulePageQuery;
import com.cloudai.system.dto.DataPermRuleSaveRequest;
import com.cloudai.system.dto.ExplainQuery;
import com.cloudai.system.dto.MyScopeQuery;
import com.cloudai.system.dto.RuleConfigQuery;
import com.cloudai.system.service.DataPermManageService;
import com.cloudai.system.vo.DataPermExplainVo;
import com.cloudai.system.vo.DataPermLogVo;
import com.cloudai.system.vo.DataPermResourceVo;
import com.cloudai.system.vo.DataPermRuleConfigVo;
import com.cloudai.system.vo.DataPermRuleVo;
import com.cloudai.system.vo.MyScopeVo;
import com.cloudai.system.vo.SubjectOptionVo;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据权限配置端点（契约 2026-10-10-data-permission-api §3，网关前缀 /system）。
 */
@RestController
@RequestMapping("/data-perm")
@RequiredArgsConstructor
public class DataPermController {

    private final DataPermManageService manageService;

    /** 规则分页（§3.1）：动态筛选组合；resource 非法值宽松语义=空集；rows 补 subjectName/columns */
    @GetMapping("/rule/page")
    @PreAuthorize("hasAuthority('system:dataPerm:list')")
    public R<PageResult<DataPermRuleVo>> pageRules(DataPermRulePageQuery query) {
        PageResult<DataPermRuleVo> page = manageService.pageListRules(query);
        return R.ok(page);
    }

    /** 规则配置回显（§3.2）：三项必填；无行规则 configured=false 走新增态 */
    @GetMapping("/rule/config")
    @PreAuthorize("hasAuthority('system:dataPerm:list')")
    public R<DataPermRuleConfigVo> getRuleConfig(RuleConfigQuery query) {
        DataPermRuleConfigVo config = manageService.findRuleConfig(query);
        return R.ok(config);
    }

    /** 保存规则（§3.3）：upsert 全量覆盖——行规则按 uk insert/update + 列规则全删全插 */
    @PostMapping("/rule")
    @PreAuthorize("hasAuthority('system:dataPerm:save')")
    public R<Void> saveRule(@RequestBody DataPermRuleSaveRequest req) {
        manageService.saveRule(req);
        return R.ok();
    }

    /** 删除规则（§3.4）：物理删行规则 + 连带物理删同主体全部列规则 */
    @DeleteMapping("/rule/{id}")
    @PreAuthorize("hasAuthority('system:dataPerm:remove')")
    public R<Void> deleteRule(@PathVariable("id") Long id) {
        manageService.deleteRule(id);
        return R.ok();
    }

    /** 资源注册表（§3.5）：资源下拉与列配置动态渲染数据源 */
    @GetMapping("/resources")
    @PreAuthorize("hasAuthority('system:dataPerm:list')")
    public R<List<DataPermResourceVo>> listResources() {
        List<DataPermResourceVo> resources = manageService.listResources();
        return R.ok(resources);
    }

    /** 主体选项（§3.6）：type=0 启用角色 / type=1 启用用户；label 后端拼好 */
    @GetMapping("/subject-options")
    @PreAuthorize("hasAuthority('system:dataPerm:list')")
    public R<List<SubjectOptionVo>> listSubjectOptions(@RequestParam("type") Integer type) {
        List<SubjectOptionVo> options = manageService.listSubjectOptions(type);
        return R.ok(options);
    }

    /** 决策留痕分页（§3.7）：account/resource 精确筛选 */
    @GetMapping("/log/page")
    @PreAuthorize("hasAuthority('system:dataPerm:list')")
    public R<PageResult<DataPermLogVo>> pageLogs(DataPermLogPageQuery query) {
        PageResult<DataPermLogVo> page = manageService.pageListLog(query);
        return R.ok(page);
    }

    /** 模拟解释（§3.8）：以任意账号身份完整求值，不执行业务查询、不留痕 */
    @GetMapping("/explain")
    @PreAuthorize("hasAuthority('system:dataPerm:list')")
    public R<DataPermExplainVo> explain(ExplainQuery query) {
        DataPermExplainVo vo = manageService.explain(query);
        return R.ok(vo);
    }

    /**
     * 我的数据范围（§3.9）：免 @PreAuthorize——语义=登录用户自查本人范围（非管理能力），
     * 网关鉴权已保登录态；挂 system:dataPerm:list 会把普通用户的 leave 页提示条变成管理入口，
     * 设计 D12 记档。留痕豁免同 explain（D7）。
     */
    @GetMapping("/my-scope")
    public R<MyScopeVo> myScope(MyScopeQuery query) {
        MyScopeVo vo = manageService.myScope(query);
        return R.ok(vo);
    }
}
