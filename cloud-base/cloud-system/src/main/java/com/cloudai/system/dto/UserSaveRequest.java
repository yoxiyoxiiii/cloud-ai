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
    /** 新增必填（明文，服务端 BCrypt）；修改忽略 */
    @ToString.Exclude
    private String password;
    /** 0正常 1停用 */
    private Integer status;
}
