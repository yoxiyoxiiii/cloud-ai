package com.cloudai.system.service.dataperm;

/**
 * 数据权限决策操作类型（契约 §6.6 operation 字段域；String 域沿 constant/LeaveStatus 先例）。
 * explain 模拟与 my-scope 自查不落 operation（不留痕，设计 D7）。
 */
public final class DataPermOperation {

    /** 分页查询 */
    public static final String LIST = "list";

    /** 详情查询 */
    public static final String DETAIL = "detail";

    /** 详情被拒（安全审计事件，设计 D7） */
    public static final String DENY = "deny";

    private DataPermOperation() {
    }
}
