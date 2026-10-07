package com.cloudai.bpmn.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 审批人选项 VO（契约 2026-10-07-bpmn-leave-api §2.5：id/account/nickname 三字段，String id——
 * 源自 system /inner/user/all 直通，含停用账号——宽松语义记档）。
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
