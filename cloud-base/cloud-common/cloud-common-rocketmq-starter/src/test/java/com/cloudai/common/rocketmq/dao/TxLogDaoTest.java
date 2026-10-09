package com.cloudai.common.rocketmq.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** mq_tx_log DAO 单测：三语句 SQL 常量与参数逐位核对 + exists 计数语义 */
@ExtendWith(MockitoExtension.class)
class TxLogDaoTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void insert_bindsSixParams() {
        TxLogDao dao = new TxLogDao(jdbcTemplate);

        dao.insert("T1", "TX_TOPIC", "leave-create", "leave", "77", "{\"id\":77}");

        verify(jdbcTemplate).update(
                eq("INSERT INTO mq_tx_log (tx_no, topic, channel, business_type, business_key, result_digest) "
                        + "VALUES (?, ?, ?, ?, ?, ?)"),
                eq("T1"), eq("TX_TOPIC"), eq("leave-create"), eq("leave"), eq("77"), eq("{\"id\":77}"));
    }

    @Test
    void existsByTxNo_countPositive_returnsTrue() {
        when(jdbcTemplate.queryForObject(
                eq("SELECT COUNT(*) FROM mq_tx_log WHERE tx_no = ?"), eq(Long.class), eq("T1")))
                .thenReturn(1L);

        assertThat(new TxLogDao(jdbcTemplate).existsByTxNo("T1")).isTrue();
    }

    @Test
    void existsByTxNo_countZero_returnsFalse() {
        when(jdbcTemplate.queryForObject(any(String.class), eq(Long.class), eq("T1"))).thenReturn(0L);

        assertThat(new TxLogDao(jdbcTemplate).existsByTxNo("T1")).isFalse();
    }

    @Test
    void deleteBefore_bindsTimestampCutoff() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 1, 3, 0);

        new TxLogDao(jdbcTemplate).deleteBefore(cutoff);

        verify(jdbcTemplate).update(
                eq("DELETE FROM mq_tx_log WHERE create_time < ?"), eq(Timestamp.valueOf(cutoff)));
    }
}
