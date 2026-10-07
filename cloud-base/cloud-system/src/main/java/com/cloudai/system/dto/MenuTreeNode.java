package com.cloudai.system.dto;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 菜单树节点（dto 包；树表出参载体）。
 * 翻译红线（契约 2026-10-07-translation-api §1/§8.1）：译文字段只落配对新增字段，
 * 原字段（status/createBy/updateBy）语义与取值零变化；status 译文键为 common_status。
 */
@TranslateVO
@Data
public class MenuTreeNode implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    private Long parentId;

    private String name;

    private String perms;

    private String type;

    /** 路由路径；M/F 通常空串（前端约定不采编），C 为 / 开头或空串（契约 2026-10-07-menu-nav-api.md §5.1 增补） */
    private String path;

    /** 图标名，空串 = 默认图标（契约 v2 增补） */
    private String icon;

    private Integer sort;

    /** 0 正常 1 停用（原字段：行内逻辑判断/tag 颜色映射依据；契约 v2 新增） */
    @DictTrans(dictKey = "common_status", labelField = "statusLabel")
    private Integer status;

    /** 创建人账号（原字段，回显用；种子数据可能为 null；契约 v2 新增） */
    @UserTrans(labelField = "createByName")
    private String createBy;

    /** 创建时间（全局 Jackson 序列化为 yyyy-MM-dd HH:mm:ss；契约 v2 新增） */
    private LocalDateTime createTime;

    /** 更新人账号（原字段，回显用；契约 v2 新增） */
    @UserTrans(labelField = "updateByName")
    private String updateBy;

    /** 更新时间（同 createTime 格式；契约 v2 新增） */
    private LocalDateTime updateTime;

    /** status 的字典译文（common_status 消费口径 label，契约 translation-api §8.1）；null=未命中/降级，前端走降级链 */
    private String statusLabel;

    /** createBy(account) 对应昵称（契约 translation-api §8.1）；null 同上 */
    private String createByName;

    /** updateBy(account) 对应昵称（契约 translation-api §8.1）；null 同上 */
    private String updateByName;

    /** 内置标记（保护契约 2026-10-07-builtin-protection §7.1）：is_builtin=1 → true；0/null → false（null-safe），只读无写出口 */
    private Boolean builtin;

    private List<MenuTreeNode> children = new ArrayList<>();
}
