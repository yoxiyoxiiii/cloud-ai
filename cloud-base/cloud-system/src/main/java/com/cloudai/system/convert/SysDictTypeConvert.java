package com.cloudai.system.convert;

import com.cloudai.system.entity.SysDictType;
import com.cloudai.system.vo.SysDictTypeVo;

/**
 * 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）
 */
public final class SysDictTypeConvert {

    private SysDictTypeConvert() {
    }

    public static SysDictTypeVo toVo(SysDictType dictType) {
        SysDictTypeVo vo = new SysDictTypeVo();
        vo.setId(dictType.getId());
        vo.setDictName(dictType.getDictName());
        vo.setDictKey(dictType.getDictKey());
        vo.setStatus(dictType.getStatus());
        vo.setCreateBy(dictType.getCreateBy());
        vo.setCreateTime(dictType.getCreateTime());
        vo.setUpdateBy(dictType.getUpdateBy());
        vo.setUpdateTime(dictType.getUpdateTime());
        return vo;
    }
}
