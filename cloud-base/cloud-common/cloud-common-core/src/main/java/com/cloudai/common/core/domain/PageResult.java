package com.cloudai.common.core.domain;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用分页返回
 */
@Data
public class PageResult<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 总记录数 */
    private long total;

    /** 当前页数据 */
    private List<T> rows;

    public static <T> PageResult<T> of(long total, List<T> rows) {
        PageResult<T> r = new PageResult<>();
        r.setTotal(total);
        r.setRows(rows == null ? new ArrayList<>() : rows);
        return r;
    }

    public boolean isEmpty() {
        return rows == null || rows.isEmpty();
    }
}
