package com.cloudai.system.entity;

import lombok.Data;
import lombok.ToString;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 共享密钥生命周期表（契约 2026-11-09-inner-secret-admin-api / 设计
 * docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md §7）。
 * 追加式流水：不可编辑、不可物理删、状态单调推进、无 deleted 列——沿 mq_tx_log 先例，
 * 不继承 BaseEntity（无逻辑删除语义；审计列由手写 SQL 显式维护）。
 */
@Data
public class SysSharedSecret implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long id;

    /** 密钥场景（inner=服务间 inner 面共享密钥；预留未来 MCP client-secret 等场景复用本表） */
    private String scene;

    /** 轮换代数（场景内单调递增，活跃集 version 由此取 MAX） */
    private Integer seq;

    /** 密钥真值（仅运行时分发到 Redis 活跃集与 Feign 头；任何接口永不返回，禁入日志） */
    @ToString.Exclude
    private String secretValue;

    /** 状态：1生效中 2重叠期（已有继任者，仍被接受） 3已过期 4已作废（字段保持 Integer 映射，Java 侧禁魔法数） */
    private Integer status;

    /** 来源：1定时任务 2手动操作 */
    private Integer source;

    /** 生效时间（创建即生效） */
    private LocalDateTime activateTime;

    /** 过期时间（=生效时间+有效期，创建时快照；改参数不追溯既有密钥） */
    private LocalDateTime expireTime;

    /** 退场时间（被继任者顶替时=轮换时刻+重叠窗；NULL=未被顶替；与 expireTime 取小者为实际退场时刻） */
    private LocalDateTime retireTime;

    /** 创建人（定时任务=handler 名，手动=操作账号） */
    private String createBy;

    /** 创建时间（SQL NOW()） */
    private LocalDateTime createTime;

    /** 更新人（状态推进时=handler 名或账号） */
    private String updateBy;

    /** 更新时间（SQL NOW()） */
    private LocalDateTime updateTime;

    /** 状态枚举（设计 §5 状态机：1→2→3 单调推进，4 可从 1/2 进入） */
    public enum StatusEnum {
        ACTIVE(1), OVERLAP(2), EXPIRED(3), REVOKED(4);

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
            throw new IllegalArgumentException("未知密钥状态: " + code);
        }
    }

    /** 来源枚举：定时任务=handler 名操作人，手动=登录账号操作人 */
    public enum SourceEnum {
        JOB(1), MANUAL(2);

        private final int code;

        SourceEnum(int code) {
            this.code = code;
        }

        public int getCode() {
            return code;
        }

        public static SourceEnum of(int code) {
            for (SourceEnum s : values()) {
                if (s.code == code) {
                    return s;
                }
            }
            throw new IllegalArgumentException("未知密钥来源: " + code);
        }
    }
}
