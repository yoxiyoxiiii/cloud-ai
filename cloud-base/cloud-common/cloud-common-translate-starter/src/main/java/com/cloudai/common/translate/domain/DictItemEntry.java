package com.cloudai.common.translate.domain;

import lombok.Data;

import java.io.Serializable;

/**
 * 字典项共享 DTO：Redis 缓存形态与消费端点出参复用（跨服务投影，禁 @class 类型头——设计 D5）。
 */
@Data
public class DictItemEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 存库值（表单提交用原值） */
    private String value;

    /** 展示标签 */
    private String label;

    /** 排序号 */
    private Integer sort;
}
