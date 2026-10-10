package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 数据权限规则主体选项（契约 §6.5）：label 后端拼好（角色=角色名；用户=昵称(账号)）。
 */
@Data
public class SubjectOptionVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主体 id（Long→String 序列化） */
    private Long id;

    /** 展示名（角色名 或 昵称(账号)） */
    private String label;
}
