package com.cloudai.task.entity;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 异步任务表（契约 2026-11-10-task-center-api §3/§4；设计
 * docs/superpowers/specs/2026-11-10-task-center-design.md §4）。
 * 流水式表：任务行无软删语义，终态行到期物理清理（沿 mq_tx_log/sys_shared_secret 先例）——
 * 无 deleted 列、不继承 BaseEntity；审计时间由手写 SQL 的 NOW() 显式维护。
 */
@Data
public class AsyncTask implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 任务类型码（handler 注册键，如 demo-batch） */
    private String taskType;

    /** 业务幂等键（NULL=不查重；非空时与 taskType 库级唯一） */
    private String bizKey;

    /** 状态：0排队 1执行中 2成功 3失败(含超时) 4已取消（字段保持 Integer 映射，Java 侧禁魔法数） */
    private Integer status;

    /** 进度百分比 0-100 */
    private Integer progress;

    /** 执行阶段描述（handler 上报，如 批处理中） */
    private String phase;

    /** 任务参数（提交方 JSON 对象文本，handler 按类型自解析） */
    private String params;

    /** 成功结果摘要（handler 返回，JSON 文本） */
    private String result;

    /** 失败原因（异常摘要或超时说明；成功/取消为 NULL） */
    private String errorMsg;

    /** 协作式取消标志：1=已请求取消（handler 检查点生效后转取消终态） */
    private Integer cancelRequested;

    /** 已重试次数（自动+手动共计数） */
    private Integer retryCount;

    /** 最大自动重试次数（提交可指定；耗尽后失败为终态） */
    private Integer maxRetries;

    /** 执行超时秒数（提交可指定；超时判失败） */
    private Integer timeoutSeconds;

    /** 认领执行器标识 host:port（多实例演进预留，CAS 认领写入口径） */
    private String executorId;

    /** 提交人账号（业务方从登录态显式传入，审计用） */
    private String submitBy;

    /** 提交来源服务名（如 cloud-system，列表筛选用） */
    private String submitSource;

    /** 开始执行时间（超时判定基准） */
    private LocalDateTime startTime;

    /** 终态时间 */
    private LocalDateTime finishTime;

    /** 提交时间 */
    private LocalDateTime createTime;

    /** 状态/进度更新时间 */
    private LocalDateTime updateTime;

    /** 状态枚举（设计 §5.1 状态机：排队→执行中→成功/失败/已取消；失败/已取消可经重试端点回排队） */
    public enum StatusEnum {
        QUEUED(0), RUNNING(1), SUCCESS(2), FAILED(3), CANCELED(4);

        private final int code;

        StatusEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static StatusEnum of(int code) {
            for (StatusEnum s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知任务状态: " + code);
        }
    }
}
