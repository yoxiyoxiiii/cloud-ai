package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.convert.NotifyConvert;
import com.cloudai.system.dto.NotifyTemplateAddRequest;
import com.cloudai.system.dto.NotifyTemplateEditRequest;
import com.cloudai.system.dto.NotifyTemplatePageQuery;
import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.mapper.SysNotifyTemplateMapper;
import com.cloudai.system.vo.NotifyTemplateVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 站内信模板管理（契约 2026-11-08-notify-api §1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyTemplateManageService {

    /** 错误码分段：3xxx system，通知域 3043-3049（契约 §4；3049 内置禁删、3044 模板不存在、3043 编码重复） */
    private static final int ERR_TEMPLATE_CODE_DUP = 3043;
    private static final int ERR_TEMPLATE_NOT_FOUND = 3044;
    private static final int ERR_TEMPLATE_BUILTIN = 3049;

    private final SysNotifyTemplateMapper templateMapper;

    /** 只读不加事务注解 */
    public PageResult<NotifyTemplateVo> pageList(NotifyTemplatePageQuery query) {
        IPage<SysNotifyTemplate> page = templateMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), query);
        List<NotifyTemplateVo> rows = page.getRecords().stream().map(NotifyConvert::toTemplateVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    public NotifyTemplateVo findById(Long id) {
        return NotifyConvert.toTemplateVo(requireTemplate(id));
    }

    /** 单表单语句不加事务：语句级原子已保证，唯一性并发由 uk_code + DuplicateKeyException 兜底 */
    public Long save(NotifyTemplateAddRequest req) {
        assertCodeFree(req.getCode(), null);
        SysNotifyTemplate template = new SysNotifyTemplate();
        template.setCode(req.getCode());
        template.setName(req.getName());
        template.setTitleTemplate(req.getTitleTemplate());
        template.setContentTemplate(req.getContentTemplate());
        template.setType(req.getType());
        template.setLevel(req.getLevel());
        template.setVariablesDesc(req.getVariablesDesc() == null ? "" : req.getVariablesDesc());
        // 管理端新增恒为用户创建模板；内置模板只随种子 SQL 落库
        template.setIsBuiltin(SysNotifyTemplate.BuiltinEnum.USER_CREATED.getCode());
        auditCreate(template);
        try {
            templateMapper.save(template);
        } catch (DuplicateKeyException e) {
            // 逻辑删除墓碑仍占用 uk_code，countByCode 查重看不到——捕获兜底转业务码
            log.error("模板编码唯一冲突：{}", e.getMessage());
            throw new BusinessException(ERR_TEMPLATE_CODE_DUP, "模板编码已存在: " + req.getCode());
        }
        return template.getId();
    }

    /** 单语句不加事务，理由同 save；内置模板内容可改（契约 §1.4，禁删保护在 delete） */
    public void update(NotifyTemplateEditRequest req) {
        requireTemplate(req.getId());
        assertCodeFree(req.getCode(), req.getId());
        SysNotifyTemplate template = new SysNotifyTemplate();
        template.setId(req.getId());
        template.setCode(req.getCode());
        template.setName(req.getName());
        template.setTitleTemplate(req.getTitleTemplate());
        template.setContentTemplate(req.getContentTemplate());
        template.setType(req.getType());
        template.setLevel(req.getLevel());
        template.setVariablesDesc(req.getVariablesDesc());
        template.setUpdateBy(SecurityUtils.currentAccount());
        try {
            templateMapper.update(template);
        } catch (DuplicateKeyException e) {
            log.error("模板编码唯一冲突：{}", e.getMessage());
            throw new BusinessException(ERR_TEMPLATE_CODE_DUP, "模板编码已存在: " + req.getCode());
        }
    }

    /** 单语句不加事务；内置模板禁删（契约 §1.5：isBuiltin=1 → 3049，内容可改仅禁删） */
    public void delete(Long id) {
        SysNotifyTemplate template = requireTemplate(id);
        if (Integer.valueOf(SysNotifyTemplate.BuiltinEnum.BUILT_IN.getCode()).equals(template.getIsBuiltin())) {
            throw new BusinessException(ERR_TEMPLATE_BUILTIN, "内置模板禁止删除");
        }
        templateMapper.deleteById(id, SecurityUtils.currentAccount());
    }

    /** 预检（编辑时排除自身）；墓碑占 uk_code 场景由 DuplicateKey 兜底 */
    private void assertCodeFree(String code, Long excludeId) {
        Long count = templateMapper.countByCode(code, excludeId);
        if (count > 0) {
            throw new BusinessException(ERR_TEMPLATE_CODE_DUP, "模板编码已存在: " + code);
        }
    }

    /** 手写 SQL 无自动填充：操作人显式构造；审计时间由 XML 的 NOW() 落库（插入时 create=update 同值） */
    private void auditCreate(SysNotifyTemplate template) {
        String operator = SecurityUtils.currentAccount();
        template.setCreateBy(operator);
        template.setUpdateBy(operator);
    }

    private SysNotifyTemplate requireTemplate(Long id) {
        SysNotifyTemplate template = templateMapper.findById(id);
        if (template == null) {
            throw new BusinessException(ERR_TEMPLATE_NOT_FOUND, "模板不存在");
        }
        return template;
    }
}
