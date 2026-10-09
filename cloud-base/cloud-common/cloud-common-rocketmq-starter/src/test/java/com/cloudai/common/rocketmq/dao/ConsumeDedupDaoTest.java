package com.cloudai.common.rocketmq.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** mq_consume_dedup DAO 单测：三语句 SQL 常量与参数逐位核对 */
@ExtendWith(MockitoExtension.class)
class ConsumeDedupDaoTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void insert_bindsGroupAndKey() {
        new ConsumeDedupDao(jdbcTemplate).insert("g_system", "leave:77:TERMINAL");

        verify(jdbcTemplate).update(
                eq("INSERT INTO mq_consume_dedup (consumer_group, msg_key) VALUES (?, ?)"),
                eq("g_system"), eq("leave:77:TERMINAL"));
    }

    @Test
    void delete_bindsGroupAndKey() {
        new ConsumeDedupDao(jdbcTemplate).delete("g_system", "leave:77:TERMINAL");

        verify(jdbcTemplate).update(
                eq("DELETE FROM mq_consume_dedup WHERE consumer_group = ? AND msg_key = ?"),
                eq("g_system"), eq("leave:77:TERMINAL"));
    }

    @Test
    void deleteBefore_bindsTimestampCutoff() {
        LocalDateTime cutoff = LocalDateTime.of(2026, 10, 2, 3, 0);

        new ConsumeDedupDao(jdbcTemplate).deleteBefore(cutoff);

        verify(jdbcTemplate).update(
                eq("DELETE FROM mq_consume_dedup WHERE create_time < ?"), eq(Timestamp.valueOf(cutoff)));
    }
}
