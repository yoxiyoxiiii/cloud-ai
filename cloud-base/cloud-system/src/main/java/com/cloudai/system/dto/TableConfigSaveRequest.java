package com.cloudai.system.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 保存本人表格列配置入参（契约 §2.2，upsert 整体覆盖语义）。
 * 无 userId 字段——userId 一律由服务端从登录态取（防改他人配置），编译期即防前端传参。
 */
@Data
public class TableConfigSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 有序列配置数组（顺序即列序；null → 1002 结构不符；空数组=全隐藏偏好，盲存放行） */
    private List<TableColumnConfigDto> columns;

    /** 列配置项（key/visible/width 三字段；逐项校验链不过 → 1002 列配置格式无效） */
    @Data
    public static class TableColumnConfigDto implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 列标识（VO 字段名约定，与数据权限 columnKey 同一命名空间；[A-Za-z0-9_]{1,32}） */
        private String key;

        /** true=显示 / false=用户自选隐藏（必填） */
        private Boolean visible;

        /** 列宽 px（可选，40-1000 整数；null=未自定义，用页面列定义默认宽） */
        private Integer width;
    }
}
