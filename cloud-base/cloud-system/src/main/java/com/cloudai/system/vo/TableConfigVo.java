package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 本人表格列配置出参 VO（契约 §2.1）：columns 有序数组（顺序即列序）；
 * 无配置时整个 data=null（非空数组——前端按页面默认列序渲染）。
 */
@Data
public class TableConfigVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 列配置有序数组（顺序即列序；锁列永不出现——保存时前端已过滤） */
    private List<TableColumnConfigItemVo> columns;

    /** 列配置项出参（key/visible/width；width null=未自定义） */
    @Data
    public static class TableColumnConfigItemVo implements Serializable {

        private static final long serialVersionUID = 1L;

        /** 列标识（VO 字段名约定，如 account/mobile） */
        private String key;

        /** true=显示 / false=用户自选隐藏 */
        private Boolean visible;

        /** 列宽 px；null=未自定义（用页面列定义默认宽） */
        private Integer width;
    }
}
