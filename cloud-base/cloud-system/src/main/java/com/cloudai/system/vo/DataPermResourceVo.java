package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 数据权限资源注册表项（契约 §6.4）：配置弹窗「资源」下拉与列配置动态渲染的数据源。
 */
@Data
public class DataPermResourceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源标识 */
    private String resource;

    /** 可配列清单（如 ["title","reason"]） */
    private List<String> columns;
}
