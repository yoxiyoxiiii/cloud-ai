package com.cloudai.system.api.dataperm;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;

/**
 * 消费方列声明断言辅助（组件化设计 D22 第三层防线）：跨服务资源的「声明列 ⊆ VO 字段」断言
 * 下沉消费方——provider 侧 registerRemote 纯字符串注册（跨服务资源 VO 类对 provider 不可见，
 * D17 类断言仅对 provider 本地 register 生效），消费方启动期一行声明同列清单，漂移即启动失败。
 * 断言语义与 provider 侧 D17 单一实现同源（DataPermResources.assertDeclaredColumns 委托本类）：
 * ①是 voClass 的真实实例字段（排除 static，serialVersionUID 不干扰）；②字段类型为 String
 * （masked 整值替换 *** 的语义前提，D11）。violation → IllegalStateException。
 */
public final class DataPermColumns {

    private DataPermColumns() {
    }

    /**
     * 声明列断言（消费方启动期调用，如 ApplicationRunner；调用点不 try 包——断言失败即启动失败）：
     * 逐列校验真实实例字段 + String 类型，violation 抛 IllegalStateException（消息含列名/VO 类名/原因）。
     */
    public static void assertDeclared(Class<?> voClass, List<String> columns) {
        for (String column : columns) {
            Field field = findInstanceField(voClass, column);
            if (field == null) {
                throw new IllegalStateException(String.format(
                        "数据权限声明列 %s 不是 VO %s 的字段（列声明断言，D22/D17）",
                        column, voClass.getSimpleName()));
            }
            if (field.getType() != String.class) {
                throw new IllegalStateException(String.format(
                        "数据权限声明列 %s 在 VO %s 中非 String（可配列必须 String，D22/D17）",
                        column, voClass.getSimpleName()));
            }
        }
    }

    /** 按名取非 static 实例字段，无则 null */
    private static Field findInstanceField(Class<?> voClass, String column) {
        for (Field field : voClass.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) && field.getName().equals(column)) {
                return field;
            }
        }
        return null;
    }
}
