package com.cloudai.common.rocketmq.dao;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * mq_consume_dedup 表 SQL（JdbcTemplate 手写）：先插后消费/失败删行放行重试（L1 幂等口径，设计 D3）。
 */
@RequiredArgsConstructor
public class ConsumeDedupDao {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO mq_consume_dedup (consumer_group, msg_key) VALUES (?, ?)";
    private static final String SQL_DELETE =
            "DELETE FROM mq_consume_dedup WHERE consumer_group = ? AND msg_key = ?";
    private static final String SQL_DELETE_BEFORE =
            "DELETE FROM mq_consume_dedup WHERE create_time < ?";

    /** 消费前置插行（DuplicateKeyException=已消费，调用方 ACK 跳过） */
    public int insert(String consumerGroup, String msgKey) {
        return jdbcTemplate.update(SQL_INSERT, consumerGroup, msgKey);
    }

    /** 消费失败删行放行重试（防消息被 dedup 吞掉） */
    public int delete(String consumerGroup, String msgKey) {
        return jdbcTemplate.update(SQL_DELETE, consumerGroup, msgKey);
    }

    /** 清理任务范围删除（idx_create_time 命中） */
    public int deleteBefore(java.time.LocalDateTime cutoff) {
        return jdbcTemplate.update(SQL_DELETE_BEFORE, java.sql.Timestamp.valueOf(cutoff));
    }
}
