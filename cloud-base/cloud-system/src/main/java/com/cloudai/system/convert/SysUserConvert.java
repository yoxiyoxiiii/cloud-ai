package com.cloudai.system.convert;

import com.cloudai.system.entity.SysUser;
import com.cloudai.system.vo.SysUserVo;

/**
 * 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）
 */
public final class SysUserConvert {

    private SysUserConvert() {
    }

    public static SysUserVo toVo(SysUser user) {
        SysUserVo vo = new SysUserVo();
        vo.setId(user.getId());
        vo.setAccount(user.getAccount());
        vo.setNickname(user.getNickname());
        vo.setStatus(user.getStatus());
        vo.setCreateBy(user.getCreateBy());
        vo.setCreateTime(user.getCreateTime());
        vo.setUpdateBy(user.getUpdateBy());
        vo.setUpdateTime(user.getUpdateTime());
        vo.setBuiltin(Integer.valueOf(1).equals(user.getIsBuiltin()));
        return vo;
    }
}
