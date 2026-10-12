package com.cloudai.system.api.dataperm;

import lombok.Data;

import java.io.Serializable;

/**
 * advice 单测脚手架：详情包装形态（同形 SysLeaveDetailVo = {leave: SysLeaveVo}）——
 * 自身不标 @DataPermVo，列载体在 com.cloudai 字段下一层，验证树遍历下钻（D55 嵌套红态核心）。
 */
@Data
public class TestDetailWrapperVo implements Serializable {

    private static final long serialVersionUID = 1L;

    private TestColumnVo inner;
}
