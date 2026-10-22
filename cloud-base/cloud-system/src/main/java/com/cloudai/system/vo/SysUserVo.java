package com.cloudai.system.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import com.cloudai.system.api.dataperm.DataPermVo;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 用户视图对象（Controller 出参隔离 DB 实体：不含 password/deleted）。
 * 翻译红线（契约 2026-10-07-translation-api §1/§3）：译文字段只落配对新增字段，
 * 原字段（status/createBy/updateBy）语义与取值零变化——前端编辑回填/行内判断/颜色映射仍用原字段。
 * 数据权限列载体（@DataPermVo）：mobile/email 是资源 user 的可配列——出参树含本 VO 的响应
 * 必须先经求值接线列上下文，缺失即 fail-closed 拒绝（漏接线显性炸，不静默明文）。
 */
@DataPermVo
@TranslateVO
@Data
public class SysUserVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private String account;

    private String nickname;

    /** 手机号（null=未填或被列规则隐藏，两态不可区分；***=被列规则脱敏——改写由列处理 advice 统一应用） */
    private String mobile;

    /** 邮箱（取值语义同 mobile） */
    private String email;

    /** 部门ID（数据权限轮契约 §5.2；null=未挂部门） */
    private Long deptId;

    /** 部门名称（pageList LEFT JOIN sys_dept 派生——D14 VO 直出；挂靠部门被删后 null 不阻断） */
    private String deptName;

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

    /** 内置标记（保护契约 2026-10-07-builtin-protection §7.1）：is_builtin=1 → true；0/null → false（null-safe），只读无写出口 */
    private Boolean builtin;
}
