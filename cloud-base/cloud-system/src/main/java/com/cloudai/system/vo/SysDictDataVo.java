package com.cloudai.system.vo;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 字典项视图对象（契约 2026-10-07-dict-api §4.2；Controller 出参隔离 DB 实体：不含 deleted）。
 * 翻译红线（契约 2026-10-07-translation-api §1/§8.1）：译文字段只落配对新增字段，
 * 原字段（status/createBy/updateBy）语义与取值零变化；status 译文键为 common_status。
 */
@TranslateVO
@Data
public class SysDictDataVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 归属类型 id */
    private Long typeId;

    private String label;

    private String value;

    /** 排序号（DDL NOT NULL DEFAULT 0，恒有值） */
    private Integer sort;

    /** 0正常 1停用（原字段：行内逻辑判断/tag 颜色映射依据；字典见 SysDictData.StatusEnum） */
    @DictTrans(dictKey = "common_status", labelField = "statusLabel")
    private Integer status;

    /** 创建人账号（原字段，回显用；种子 createBy='system' 非真实账号 → 译文 null 属正确降级） */
    @UserTrans(labelField = "createByName")
    private String createBy;

    private LocalDateTime createTime;

    /** 更新人账号（原字段，回显用） */
    @UserTrans(labelField = "updateByName")
    private String updateBy;

    private LocalDateTime updateTime;

    /** status 的字典译文（common_status 消费口径 label，契约 translation-api §8.1）；null=未命中/降级，前端走降级链 */
    private String statusLabel;

    /** createBy(account) 对应昵称（契约 translation-api §8.1）；null 同上 */
    private String createByName;

    /** updateBy(account) 对应昵称（契约 translation-api §8.1）；null 同上 */
    private String updateByName;

    /** 内置标记（保护契约 2026-10-07-builtin-protection §7.1）：is_builtin=1 → true；0/null → false（null-safe），只读无写出口 */
    private Boolean builtin;
}
