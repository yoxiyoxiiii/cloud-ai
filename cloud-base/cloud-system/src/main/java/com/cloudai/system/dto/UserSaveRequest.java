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
    /** 部门ID（可选；null=不挂部门；传入时部门不存在/已删 → 3027；修改 null 不更新该列——契约 §5.1） */
    private Long deptId;
    /** 新增必填（明文，服务端 BCrypt）；修改忽略 */
    @ToString.Exclude
    private String password;
    /** 0正常 1停用 */
    private Integer status;
}
