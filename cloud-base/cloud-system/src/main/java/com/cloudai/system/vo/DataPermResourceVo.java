package com.cloudai.system.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 数据权限资源注册表项（契约 §6.4 + 谓词轮 §2.4 增量）：配置弹窗「资源」下拉与
 * 列配置动态渲染的数据源；filterColumns 驱动条件编辑器的列/类型/操作符联动。
 */
@Data
public class DataPermResourceVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 资源标识 */
    private String resource;

    /** 可配列清单（如 ["title","reason"]——列级动作，VO 字段名锚点） */
    private List<String> columns;

    /** 是否列级-only（行档位与谓词不参与求值；配置台据此禁用行档区与条件段） */
    private boolean columnOnly;

    /** 可过滤列清单（行级条件，entity 字段名锚点——契约 §5.2） */
    private List<FilterColumnVo> filterColumns = new ArrayList<>();
}
