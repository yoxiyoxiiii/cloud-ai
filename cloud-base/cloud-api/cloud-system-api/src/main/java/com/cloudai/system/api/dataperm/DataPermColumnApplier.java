package com.cloudai.system.api.dataperm;

import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反射通用列动作应用工具（重构轮 D16；2026-10-10 组件化 D18 自 cloud-system 搬家至 api 模块，
 * 机制与防御零变更——消费方引 jar 即得，新资源接入列应用零手写）：hidden → 字段置 null /
 * masked → 整值替换 {@code ***}（复用 {@link ColumnScope#mask} 的 null→null 语义），
 * 与手写 setter 分支逐字段等价。
 *
 * <p>三层纵深防御的第三层（运行期读路径永不因列应用炸，D7 口径延伸）：第一层注册断言拦
 * 声明错（provider 本地 register / 消费方 DataPermColumns.assertDeclared，启动失败）；
 * 第二层 3034 校验链拦配置错；本工具兜底运行期残余的唯一来源 = DB 脏数据——
 * 字段不存在 / masked 指非 String 字段 → log.warn（按 class#field 去重防大分页刷屏）跳过，
 * 其余列照常应用；IllegalAccessException 为 setAccessible 后理论不可达的终态兜底（log.error 跳过）。
 *
 * <p>性能记档（设计 D16）：FIELD_CACHE 惰性缓存后字段查找 O(1)，分页 ≤200 行 × ≤几列的反射 set
 * 开销可忽略——求值本身每请求 4-5 次索引查询才是读路径大头（D10），反射非热点，不做启动预热。
 * 不设批量重载：消费方 forEach 一行等价，批量方法是签名膨胀无行为增益。
 */
@Slf4j
public final class DataPermColumnApplier {

    /** 类 → (字段名 → Field) 惰性缓存：首次触达该类 getDeclaredFields 一次展开（VO 均为本工程无继承 POJO，覆盖完备） */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();

    /** 字段 miss/类型防御 warn 去重键集合（class#field），防大分页同键刷屏 */
    private static final Set<String> MISSING_WARNED = ConcurrentHashMap.newKeySet();

    private DataPermColumnApplier() {
    }

    /**
     * 列动作应用（D16）：vo/columnScope 为 null 或 scope 无任何列动作时短路零处理；
     * hidden 逐列置 null，masked 逐列整值替换 ***（null 保持 null）。
     */
    public static void apply(Object vo, ColumnScope columnScope) {
        if (vo == null || columnScope == null || columnScope.isEmpty()) {
            return;
        }
        for (String columnKey : columnScope.getHiddenColumns()) {
            setFieldNull(vo, columnKey);
        }
        for (String columnKey : columnScope.getMaskedColumns()) {
            applyMask(vo, columnKey, columnScope);
        }
    }

    /** hidden 动作：字段置 null；字段 miss 双防御跳过（warn 去重） */
    private static void setFieldNull(Object vo, String columnKey) {
        Field field = resolve(vo.getClass(), columnKey);
        if (field == null) {
            return;
        }
        try {
            field.set(vo, null);
        } catch (IllegalAccessException e) {
            log.error("数据权限列动作应用失败（终态兜底，跳过）：{}.{}",
                    vo.getClass().getSimpleName(), columnKey, e);
        }
    }

    /** masked 动作：整值替换 ***（复用 ColumnScope.mask，null 保持 null）；非 String 字段双防御跳过 */
    private static void applyMask(Object vo, String columnKey, ColumnScope columnScope) {
        Field field = resolve(vo.getClass(), columnKey);
        if (field == null) {
            return;
        }
        if (field.getType() != String.class) {
            warnOnce(vo.getClass(), columnKey, "masked 指向非 String 字段，跳过脱敏");
            return;
        }
        try {
            String value = (String) field.get(vo);
            field.set(vo, columnScope.mask(value));
        } catch (IllegalAccessException e) {
            log.error("数据权限列动作应用失败（终态兜底，跳过）：{}.{}",
                    vo.getClass().getSimpleName(), columnKey, e);
        }
    }

    /** 缓存解析字段：miss → warn 去重后返回 null（读路径不炸，其余列照常） */
    private static Field resolve(Class<?> voClass, String columnKey) {
        Map<String, Field> fields = FIELD_CACHE.computeIfAbsent(voClass, DataPermColumnApplier::expandFields);
        Field field = fields.get(columnKey);
        if (field == null) {
            warnOnce(voClass, columnKey, "列不在 VO 字段中（疑似 DB 脏列），跳过");
        }
        return field;
    }

    /** getDeclaredFields 一次展开：排除 static 字段并 setAccessible */
    private static Map<String, Field> expandFields(Class<?> voClass) {
        Map<String, Field> fields = new ConcurrentHashMap<>();
        for (Field field : voClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                fields.put(field.getName(), field);
            }
        }
        return fields;
    }

    /** 按 class#field 去重的 warn（首次出现输出一次） */
    private static void warnOnce(Class<?> voClass, String columnKey, String reason) {
        if (MISSING_WARNED.add(voClass.getName() + "#" + columnKey)) {
            log.warn("数据权限列动作应用防御触发：{}.{} —— {}",
                    voClass.getSimpleName(), columnKey, reason);
        }
    }
}
