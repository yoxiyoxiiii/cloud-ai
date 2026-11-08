package com.cloudai.system.convert;

import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.vo.NotifyTemplateVo;

/**
 * 通知域实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）。
 * 用户消息 VO 由 mapper JOIN 直出（派生列 send_time 无实体承载），本类只承载模板域转换。
 */
public final class NotifyConvert {

    private NotifyConvert() {
    }

    public static NotifyTemplateVo toTemplateVo(SysNotifyTemplate template) {
        NotifyTemplateVo vo = new NotifyTemplateVo();
        vo.setId(template.getId());
        vo.setCode(template.getCode());
        vo.setName(template.getName());
        vo.setTitleTemplate(template.getTitleTemplate());
        vo.setContentTemplate(template.getContentTemplate());
        vo.setType(template.getType());
        vo.setLevel(template.getLevel());
        vo.setVariablesDesc(template.getVariablesDesc());
        vo.setIsBuiltin(template.getIsBuiltin());
        vo.setCreateBy(template.getCreateBy());
        vo.setCreateTime(template.getCreateTime());
        vo.setUpdateTime(template.getUpdateTime());
        return vo;
    }
}
