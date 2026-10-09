package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 审批人选项 VO（契约 2026-10-08-approval-platform-api §5.5：id/account/nickname 三字段——
 * 本库直查仅启用账号，v1 含停用的宽松语义随迁移收紧）。
 */
@Data
public class UserOptionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID（Jackson Long→String） */
    private Long id;

    /** 登录账号 */
    private String account;

    /** 昵称 */
    private String nickname;
}
