package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.exception.BusinessException;

import java.util.List;
import java.util.Map;

/**
 * 数据权限资源注册表（设计 D11）：resource 取值与每资源可配列清单的唯一真源——
 * 配置与求值前均经本注册表校验（3034），防自由字符串配错永不命中。
 * 新资源接入 = 本表加一行 + 读路径接求值器（接入模板设计 §7.3 三步）。
 * 列 key 语义 = VO 字段名（camelCase），列表与详情共用同一列规则（一致治理）。
 */
public final class DataPermResources {

    /** 请假资源（试点）：可配列 title（隐藏示例）/ reason（脱敏示例） */
    public static final String LEAVE = "leave";

    /** leave 可配列标识（读路径列动作应用引用，禁散落字符串字面量） */
    public static final String LEAVE_COLUMN_TITLE = "title";
    public static final String LEAVE_COLUMN_REASON = "reason";

    private static final Map<String, List<String>> CONFIGURABLE_COLUMNS = Map.of(
            LEAVE, List.of(LEAVE_COLUMN_TITLE, LEAVE_COLUMN_REASON));

    /** 无效的资源或列标识（契约 §8 错误码 3034） */
    public static final int ERR_INVALID_RESOURCE = 3034;

    private DataPermResources() {
    }

    /** 资源是否已注册 */
    public static boolean isRegistered(String resource) {
        return resource != null && CONFIGURABLE_COLUMNS.containsKey(resource);
    }

    /** 全部已注册资源标识（资源注册表端点数据源，契约 §3.5） */
    public static List<String> listRegisteredResources() {
        return List.copyOf(CONFIGURABLE_COLUMNS.keySet());
    }

    /** 资源的可配列清单（未注册资源返回空列表——调用方先经 assertResource） */
    public static List<String> getConfigurableColumns(String resource) {
        return CONFIGURABLE_COLUMNS.getOrDefault(resource, List.of());
    }

    /** 校验资源已注册，未注册抛 3034 */
    public static void assertResource(String resource) {
        if (!isRegistered(resource)) {
            throw new BusinessException(ERR_INVALID_RESOURCE, "无效的资源或列标识: " + resource);
        }
    }

    /** 校验列在资源的可配清单内，不在抛 3034（调用方先经 assertResource） */
    public static void assertColumn(String resource, String columnKey) {
        if (!getConfigurableColumns(resource).contains(columnKey)) {
            throw new BusinessException(ERR_INVALID_RESOURCE, "无效的资源或列标识: " + columnKey);
        }
    }
}
