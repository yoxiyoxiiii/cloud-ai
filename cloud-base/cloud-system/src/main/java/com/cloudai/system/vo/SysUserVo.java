package com.cloudai.system.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图对象（Controller 出参隔离 DB 实体：不含 password/deleted）。
 * 翻译红线（契约 2026-10-07-translation-api §1/§3）：译文字段只落配对新增字段，
 * 原字段（status/createBy/updateBy）语义与取值零变化——前端编辑回填/行内判断/颜色映射仍用原字段。
 */
@TranslateVO
@Data
public class SysUserVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String account;

    private String nickname;

    /** 0正常 1停用（原字段：行内逻辑判断/tag 颜色映射/筛选依据；字典见 SysUser.StatusEnum） */
    @DictTrans(dictKey = "user_status", labelField = "statusLabel")
    private Integer status;

    /** 创建人账号（原字段，回显用） */
    @UserTrans(labelField = "createByName")
    private String createBy;

    private LocalDateTime createTime;

    /** 更新人账号（原字段，回显用） */
    @UserTrans(labelField = "updateByName")
    private String updateBy;

    private LocalDateTime updateTime;

    /** status 的字典译文（user_status 消费口径 label）；null=未命中/降级，前端走降级链 */
    private String statusLabel;

    /** createBy(account) 对应昵称；null 同上 */
    private String createByName;

    /** updateBy(account) 对应昵称；null 同上 */
    private String updateByName;
}
