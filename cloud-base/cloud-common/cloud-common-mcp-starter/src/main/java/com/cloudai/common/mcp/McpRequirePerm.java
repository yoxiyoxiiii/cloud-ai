package com.cloudai.common.mcp;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * MCP 工具方法权限档位声明（starter 切面读取并强制执行，语义对齐 REST 面 @PreAuthorize）。
 * value = 所需 perms 标识（sys_menu.perms，如 system:user:list）；空值 = 登录即可。
 * 每个 @Tool 方法必须显式标注（含空值档）——cloud-system 守护测试强制，漏标注构建期即红。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface McpRequirePerm {

    /** 所需权限标识；空字符串 = 登录即可（认证过滤器三关已保证登录） */
    String value() default "";
}
