package com.cloudai.system.dto;

import lombok.Data;
import lombok.ToString;

import java.io.Serializable;

@Data
public class UserSaveRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 修改必填 */
    private Long id;
    /** 新增必填；修改忽略 */
    private String account;
    private String nickname;
    /**
     * 手机号（可选）：null/空白=未填（新增）或不更新该列（修改）；非空才校验格式，
     * 无效 → 1002「手机号格式无效」；修改时值 *** 是脱敏标记，按不修改处理（库值保持原值——
     * 契约 2026-10-22-dataperm-column-visibility-api.md §4.3）；新增路径 *** 会被格式校验拦截。
     */
    private String mobile;
    /** 邮箱（可选）：语义同 mobile，格式无效文案「邮箱格式无效」 */
    private String email;
    /** 部门ID（可选；null=不挂部门；传入时部门不存在/已删 → 3027；修改 null 不更新该列——契约 §5.1） */
    private Long deptId;
    /** 新增必填（明文，服务端 BCrypt）；修改忽略 */
    @ToString.Exclude
    private String password;
    /** 0正常 1停用 */
    private Integer status;
}
