package com.cloudai.system.api.dataperm;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * detail 行属性取值器（设计 D34，2026-10-11 谓词轮）：{@code of(bean)} 返回
 * {@code field -> 行值} 的函数——{@link RowFilter#allowsDetail} 的取值面，兼容 typed 形态
 * （entity：Integer/LocalDateTime）与字符串形态（VO：投影轮 CAST 直出的 "1"/"2026-01-01"），
 * 归一在 RowPredicate.test 内完成。
 *
 * <p>字段缓存（D16 同款惰性）：ConcurrentHashMap&lt;Class, Map&lt;field, Field&gt;&gt; 首次触达
 * 一次展开，<b>遍历类层级含父类</b>（createTime 等审计字段在 BaseEntity——与
 * {@link DataPermColumnApplier} 仅 getDeclaredFields 的差异记档：VO 无继承前提，entity 恒有）。
 * 未知字段 → null（→ 谓词不满足 → 组失败 → 拒绝，fail-closed 方向，D34）。
 */
@Slf4j
public final class EntityFieldResolver {

    /** 类 → (字段名 → Field) 惰性缓存（类层级一次展开） */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    private EntityFieldResolver() {
    }

    /** 取值函数（不可变视角；bean 引用保持可变——分页行逐行构造） */
    public static Function<String, Object> of(Object bean) {
        return field -> read(bean, field);
    }

    private static Object read(Object bean, String field) {
        Field resolved = FIELD_CACHE
                .computeIfAbsent(bean.getClass(), EntityFieldResolver::expandFields)
                .get(field);
        if (resolved == null) {
            return null;
        }
        try {
            return resolved.get(bean);
        } catch (IllegalAccessException e) {
            // setAccessible 后理论不可达的终态兜底：按未知字段处理（拒绝方向，D34）
            log.error("数据权限行属性取值失败（按不满足处理）：{}.{}", bean.getClass().getSimpleName(), field, e);
            return null;
        }
    }

    /** 类层级一次展开（至 Object 前；排除 static；子类同名字段优先 putIfAbsent） */
    private static Map<String, Field> expandFields(Class<?> beanClass) {
        Map<String, Field> fields = new HashMap<>();
        for (Class<?> current = beanClass; current != null && current != Object.class;
                current = current.getSuperclass()) {
            for (Field declared : current.getDeclaredFields()) {
                if (!Modifier.isStatic(declared.getModifiers())) {
                    declared.setAccessible(true);
                    fields.putIfAbsent(declared.getName(), declared);
                }
            }
        }
        return fields;
    }
}
