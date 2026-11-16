package com.cloudai.system.api.dataperm;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 数据权限求值声明（标注在 Service 读方法上，由 {@link DataPermEvaluateAspect} 在方法入口执行）：
 * 求值当前登录人对 resource 的决策 → 无条件 set 列处理上下文（空 scope 也 set，接线声明语义
 * 与 Service 手工 set 一致）→ 决策存入 {@link DataPermEvaluation} 供方法体取行级过滤。
 *
 * <p>方法体经 {@link DataPermEvaluation#currentRowFilter()} 取行集判定权威（list SQL 参数 /
 * allowsDetail 判定）；返回树中含 @DataPermVo 白名单 VO 的列动作仍由列 advice 在响应边界统一应用。
 * 标注方法必须经 Spring 代理调用（自调用绕过切面——currentRowFilter 会以 IllegalStateException
 * fail-fast 暴露）。</p>
 *
 * <p>businessKey 仅支持 {@code #pN} 位置引用（如 {@code "#p0"}=首参单据 id，String.valueOf 落留痕）：
 * 本工程未启用 -parameters 编译参数，方法参数名不可反射发现，名字引用会静默失效（与 MCP 切面
 * 字面量切点同源结论）；list 类无单据键留空。设计记档：
 * docs/superpowers/specs/2026-11-16-dataperm-aop-evaluate-design.md。</p>
 * @author jy-root
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DataPermEvaluate {

    /** 数据权限资源标识（注册表常量，如 DataPermResources.LEAVE / "bpmn_approval"） */
    String resource();

    /** 操作类型（DataPermOperation.LIST/DETAIL——留痕 operation 列） */
    String operation();

    /** 留痕 businessKey 的参数位置引用（#p0 起）；空 = list 类查询无单据键 */
    String businessKey() default "";
}
