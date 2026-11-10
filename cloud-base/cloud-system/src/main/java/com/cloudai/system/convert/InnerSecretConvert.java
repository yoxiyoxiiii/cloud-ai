package com.cloudai.system.convert;

import com.cloudai.system.entity.SysSharedSecret;
import com.cloudai.system.vo.InnerSecretVo;

/**
 * 实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）。
 * 唯一的掩码生成点：secretValue 永不外泄，仅保留尾 4 字符供肉眼比对。
 */
public final class InnerSecretConvert {

    /** 掩码保留尾长：与契约 §0 的 `****`+尾4字符 口径一致 */
    private static final int MASK_TAIL_LEN = 4;

    private InnerSecretConvert() {
    }

    public static InnerSecretVo toVo(SysSharedSecret secret) {
        InnerSecretVo vo = new InnerSecretVo();
        vo.setId(secret.getId());
        vo.setSeq(secret.getSeq());
        vo.setStatus(secret.getStatus());
        vo.setSecretMask(mask(secret.getSecretValue()));
        vo.setSource(secret.getSource());
        vo.setActivateTime(secret.getActivateTime());
        vo.setExpireTime(secret.getExpireTime());
        vo.setRetireTime(secret.getRetireTime());
        vo.setCreateBy(secret.getCreateBy());
        vo.setCreateTime(secret.getCreateTime());
        return vo;
    }

    /** 掩码：短值（异常防御）全 *，常规值 ****+尾4字符 */
    private static String mask(String secretValue) {
        if (secretValue == null || secretValue.length() <= MASK_TAIL_LEN) {
            return "****";
        }
        return "****" + secretValue.substring(secretValue.length() - MASK_TAIL_LEN);
    }
}
