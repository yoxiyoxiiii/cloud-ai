package com.cloudai.bpmn.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/** 引擎时间 → 契约展示格式（yyyy-MM-dd HH:mm:ss）工具；引擎 API 返回 java.util.Date，本域统一转字符串出参 */
public final class BpmnDateUtil {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private BpmnDateUtil() {
    }

    /** Date（引擎 ACT_* 时间）→ 展示格式；null 安全 */
    public static String format(Date date) {
        if (date == null) {
            return null;
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(FORMATTER);
    }

    /** LocalDateTime（业务表时间）→ 展示格式；null 安全 */
    public static String format(LocalDateTime time) {
        return time == null ? null : time.format(FORMATTER);
    }
}
