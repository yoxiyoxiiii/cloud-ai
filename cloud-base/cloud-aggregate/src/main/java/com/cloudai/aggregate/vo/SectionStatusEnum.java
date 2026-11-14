package com.cloudai.aggregate.vo;

/**
 * 段状态枚举（契约 2026-11-14-aggregate-workbench-api §2）：段状态码与整体 code 同语义空间，
 * 前端判断口径统一为 section.status === 200（msg 仅展示不做逻辑判断）。
 */
public enum SectionStatusEnum {

    /** 正常（下游返回 code=200） */
    OK(200),

    /** 无权限（聚合层段准入预判不通过；或下游 @PreAuthorize 拒绝 code=403） */
    NO_PERM(7001),

    /** 下游失败（降级/其它非 200、403 code/Feign 调用异常） */
    DOWNSTREAM_ERROR(7002);

    private final int code;

    SectionStatusEnum(int code) {
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static SectionStatusEnum of(int code) {
        for (SectionStatusEnum s : values()) {
            if (s.code == code) {
                return s;
            }
        }
        throw new IllegalArgumentException("未知段状态: " + code);
    }
}
