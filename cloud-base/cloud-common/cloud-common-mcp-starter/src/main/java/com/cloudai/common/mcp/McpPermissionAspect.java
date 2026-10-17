package com.cloudai.common.mcp;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;

/**
 * 工具方法权限拦截：按方法上的权限档位注解声明做 perms 判定，判定内核复用 McpPermGuard。
 * 可行性依据：docs/superpowers/specs/2026-10-17-mcp-communication-internals.md 第 5 章——
 * 工具调用终态是 Method.invoke(代理 bean)，切面经 CGLIB 虚分派必命中（第 8 章已运行时实证）；
 * 切面抛出的权限拒绝异常在反射边界内的传播路径与手工首行调用等价，线上 PERMISSION_DENIED
 * 文本逐字不变。
 * 切点用字面量形式（不用参数名绑定形式）：绑定式需 -parameters 编译参数解析通知方法参数名，
 * 本工程未启用该编译参数，绑定式切点会静默不命中（运行时实证）；注解值改为反射读取。
 */
@Aspect
public class McpPermissionAspect {

    @Around("@annotation(com.cloudai.common.mcp.McpRequirePerm)")
    public Object enforce(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        McpRequirePerm requirePerm = method.getAnnotation(McpRequirePerm.class);
        String perm = requirePerm.value();
        if (!perm.isEmpty()) {
            McpPermGuard.require(perm);
        }
        return pjp.proceed();
    }
}
