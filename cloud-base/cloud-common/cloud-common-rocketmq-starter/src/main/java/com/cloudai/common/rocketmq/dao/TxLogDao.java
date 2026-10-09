package com.cloudai.common.rocketmq.dao;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * mq_tx_log 表 SQL（JdbcTemplate 手写，starter 通用表零侵入宿主 mybatis——设计 D1 取舍）。
 * 行与业务写同本地事务：行在=COMMIT、行无=ROLLBACK（回查唯一依据）。
 */
@RequiredArgsConstructor
public class TxLogDao {

    private final JdbcTemplate jdbcTemplate;

    private static final String SQL_INSERT =
            "INSERT INTO mq_tx_log (tx_no, topic, channel, business_type, business_key, result_digest) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SQL_EXISTS =
            "SELECT COUNT(*) FROM mq_tx_log WHERE tx_no = ?";
    private static final String SQL_DELETE_BEFORE =
            "DELETE FROM mq_tx_log WHERE create_time < ?";

    /** 落本地事务流水（listener 事务内调用；business_type/business_key 由 executor 回执，NOT NULL 强制） */
    public int insert(String txNo, String topic, String channel, String businessType,
                      String businessKey, String resultDigest) {
        return jdbcTemplate.update(SQL_INSERT, txNo, topic, channel, businessType, businessKey, resultDigest);
    }

    /** 回查点查（uk_tx_no 命中；行在=COMMIT） */
    public boolean existsByTxNo(String txNo) {
        Long count = jdbcTemplate.queryForObject(SQL_EXISTS, Long.class, txNo);
        return count != null && count > 0;
    }

    /** 清理任务范围删除（无 create_time 索引，低峰全表扫——D8 取舍） */
    public int deleteBefore(java.time.LocalDateTime cutoff) {
        return jdbcTemplate.update(SQL_DELETE_BEFORE, java.sql.Timestamp.valueOf(cutoff));
    }
}
