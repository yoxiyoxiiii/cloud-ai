package com.cloudai.common.translate.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 字段级注解：按字典键翻译本字段值（字符串化匹配字典项 value），译文回填 labelField 声明的同 VO String 字段。
 *
 * <p>设计规则 0（红线）：处理器只写 labelField 一个字段，被标注的原字段绝不写——
 * 原值是前端编辑回填/行内判断/颜色映射的业务依据，必须原样返回。
 * labelField 目标字段缺失/非 String/不可写时该字段翻译作废并 log.warn 跳过（不抛错）。</p>
 */
@Documented
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DictTrans {

    /** 字典类型键（sys_dict_type.dict_key，消费口径：类型启用 ∧ 项启用） */
    String dictKey();

    /** 译文目标字段名（同 VO 内 String 字段；已非 null 时跳过回填——手翻优先） */
    String labelField();
}
