package com.cloudai.system.api.dataperm;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * api 模块测试夹具 VO（原 cloud-system SysLeaveVo 同形：title/reason String + createTime 非 String）
 * ——api 模块不可见业务 VO，搬家用例集（DataPermColumnApplierTest）与列声明断言红绿态共用。
 * 真类不 mock（D14 教训：反射映射类缺陷 mock 不可见，必须走真实字段解析路径）。
 */
@Data
public class SampleVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private String title;

    private String reason;

    private LocalDateTime createTime;
}
