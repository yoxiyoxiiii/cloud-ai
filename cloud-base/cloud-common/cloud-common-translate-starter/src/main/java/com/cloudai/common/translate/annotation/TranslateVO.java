package com.cloudai.common.translate.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 类级注解：启用该 VO 的翻译扫描（Advisor supports 快速判定 + 对象树遍历白名单）。
 * 未标注类零成本跳过；字段级翻译注解见 {@link DictTrans}/{@link UserTrans}。
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TranslateVO {
}
