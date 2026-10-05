package com.cloudai.system.convert;

import com.cloudai.system.entity.SysRole;
import com.cloudai.system.vo.SysRoleVo;

/**
 * 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）
 */
public final class SysRoleConvert {

    private SysRoleConvert() {
    }

    public static SysRoleVo toVo(SysRole role) {
        SysRoleVo vo = new SysRoleVo();
        vo.setId(role.getId());
        vo.setName(role.getName());
        vo.setRoleKey(role.getRoleKey());
        vo.setStatus(role.getStatus());
        vo.setCreateBy(role.getCreateBy());
        vo.setCreateTime(role.getCreateTime());
        vo.setUpdateBy(role.getUpdateBy());
        vo.setUpdateTime(role.getUpdateTime());
        return vo;
    }
}
