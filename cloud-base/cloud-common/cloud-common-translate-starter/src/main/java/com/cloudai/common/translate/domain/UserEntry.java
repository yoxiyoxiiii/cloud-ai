package com.cloudai.common.translate.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户共享 DTO：Redis 缓存形态（account→nickname 翻译源；id 字符串化与全局 Long→String 约定一致——设计 D4/D5）。
 */
@Data
public class UserEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（JSON 中序列化为字符串） */
    private Long id;

    /** 登录账号 */
    private String account;

    /** 昵称（译文来源） */
    private String nickname;
}
