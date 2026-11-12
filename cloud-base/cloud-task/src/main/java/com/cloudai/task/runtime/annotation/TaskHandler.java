package com.cloudai.task.runtime.annotation;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 任务处理器注册注解：value=任务类型码（handler 注册键，提交时校验存在性）。
 * 与 SPI 接口同名（设计 §5.3 原名保留），故独立 annotation 子包避开同包类名冲突。
 * 含 @Component 元注解——标注即成 Spring bean，被 TaskHandlerRegistry 收集建 type→handler 表；
 * 处理器类必须落 handler/ 包（守护测试强制）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface TaskHandler {

    /** 任务类型码（非空，如 demo-batch） */
    String value();
}
