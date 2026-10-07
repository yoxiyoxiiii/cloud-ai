package com.cloudai.common.translate.core;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Class → 翻译字段元数据缓存（反射每类一次，设计 §3.3）。
 *
 * <p>设计规则 0（红线防呆）：解析 labelField 时校验目标字段——存在、类型为 String、非 final（可写），
 * 不满足则该字段元数据作废并 log.warn 跳过（不抛错，不影响业务响应）。</p>
 */
@Slf4j
public class TransFieldScanner {

    private final Map<Class<?>, List<TransFieldMeta>> metaCache = new ConcurrentHashMap<>();

    /** 取类翻译字段元数据（按类缓存，无翻译字段返回空列表） */
    public List<TransFieldMeta> findTransFields(Class<?> type) {
        return metaCache.computeIfAbsent(type, this::scanFields);
    }

    private List<TransFieldMeta> scanFields(Class<?> type) {
        List<TransFieldMeta> metas = new ArrayList<>();
        for (Field field : allFields(type)) {
            DictTrans dict = field.getAnnotation(DictTrans.class);
            if (dict != null) {
                appendMeta(metas, type, field, dict.dictKey(), dict.labelField(), TransKind.DICT);
                continue;
            }
            UserTrans user = field.getAnnotation(UserTrans.class);
            if (user != null) {
                appendMeta(metas, type, field, null, user.labelField(), TransKind.USER);
            }
        }
        return List.copyOf(metas);
    }

    /** 目标字段三重校验（存在/String/可写），失败作废该字段 + log.warn（不抛错） */
    private void appendMeta(List<TransFieldMeta> metas, Class<?> type, Field source,
                            String dictKey, String labelFieldName, TransKind kind) {
        Field target = findField(type, labelFieldName);
        if (target == null) {
            log.warn("translate meta dropped: labelField '{}' not found on {}, field '{}'",
                    labelFieldName, type.getName(), source.getName());
            return;
        }
        if (!String.class.equals(target.getType())) {
            log.warn("translate meta dropped: labelField '{}' on {} is not String, field '{}'",
                    labelFieldName, type.getName(), source.getName());
            return;
        }
        if (Modifier.isFinal(target.getModifiers())) {
            log.warn("translate meta dropped: labelField '{}' on {} is final, field '{}'",
                    labelFieldName, type.getName(), source.getName());
            return;
        }
        source.setAccessible(true);
        target.setAccessible(true);
        metas.add(new TransFieldMeta(source, target, dictKey, kind));
    }

    /** 沿类层级收集字段（VO 继承场景），到 Object 为止 */
    private List<Field> allFields(Class<?> type) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            fields.addAll(Arrays.asList(c.getDeclaredFields()));
        }
        return fields;
    }

    private Field findField(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // 继续向上找
            }
        }
        return null;
    }

    /** 翻译种类：字典（value→label）/ 用户（account→nickname） */
    public enum TransKind {
        DICT, USER
    }

    /** 单字段翻译元数据：源字段 + 译文目标字段（已校验）+ 字典键/种类 */
    @Getter
    @RequiredArgsConstructor
    public static final class TransFieldMeta {

        private final Field sourceField;
        private final Field labelField;
        private final String dictKey;
        private final TransKind kind;
    }
}
