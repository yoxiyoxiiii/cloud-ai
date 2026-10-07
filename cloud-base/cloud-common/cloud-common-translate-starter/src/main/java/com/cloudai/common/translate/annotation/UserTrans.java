package com.cloudai.common.translate.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级注解：将本字段的账号值（account）译为用户昵称（nickname），译文回填 labelField 声明的同 VO String 字段。
 *
 * <p>设计规则 0（红线）同 {@link DictTrans}：只写 labelField，原字段绝不写；
 * 目标字段校验失败作废跳过不抛错。id→昵称形态暂未提供（设计 D4 预留）。</p>
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface UserTrans {

    /** 译文目标字段名（同 VO 内 String 字段；已非 null 时跳过回填——手翻优先） */
    String labelField();
}
