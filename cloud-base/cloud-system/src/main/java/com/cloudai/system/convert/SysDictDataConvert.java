package com.cloudai.system.convert;

import com.cloudai.system.entity.SysDictData;
import com.cloudai.system.vo.SysDictDataVo;

/**
 * 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）
 */
public final class SysDictDataConvert {

    private SysDictDataConvert() {
    }

    public static SysDictDataVo toVo(SysDictData dictData) {
        SysDictDataVo vo = new SysDictDataVo();
        vo.setId(dictData.getId());
        vo.setTypeId(dictData.getDictTypeId());
        vo.setLabel(dictData.getLabel());
        vo.setValue(dictData.getValue());
        vo.setSort(dictData.getSort());
        vo.setStatus(dictData.getStatus());
        vo.setCreateBy(dictData.getCreateBy());
        vo.setCreateTime(dictData.getCreateTime());
        vo.setUpdateBy(dictData.getUpdateBy());
        vo.setUpdateTime(dictData.getUpdateTime());
        return vo;
    }
}
