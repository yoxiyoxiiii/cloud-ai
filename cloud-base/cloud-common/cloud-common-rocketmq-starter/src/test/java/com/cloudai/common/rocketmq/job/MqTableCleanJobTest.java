package com.cloudai.common.rocketmq.job;

import com.cloudai.common.rocketmq.config.CommonRocketMqProperties;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.cloudai.common.rocketmq.dao.TxLogDao;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 两表清理任务单测：dedup dao 在场双清（cutoff 按各自保留期）、缺席跳过 dedup 段
 * （bpmn 库无 mq_consume_dedup 表——自动装配传 null）。
 */
@ExtendWith(MockitoExtension.class)
class MqTableCleanJobTest {

    @Mock
    private TxLogDao txLogDao;
    @Mock
    private ConsumeDedupDao dedupDao;

    @Test
    void clean_withDedupDao_cleansBothTables() {
        CommonRocketMqProperties properties = new CommonRocketMqProperties();
        properties.setTxlogRetentionDays(30);
        properties.setDedupRetentionDays(7);
        MqTableCleanJob job = new MqTableCleanJob(txLogDao, dedupDao, properties);

        job.cleanExpired();

        // tx_log cutoff = now - 30 天；dedup cutoff = now - 7 天（秒级容忍）
        ArgumentCaptor<LocalDateTime> txCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> dedupCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(txLogDao).deleteBefore(txCutoff.capture());
        verify(dedupDao).deleteBefore(dedupCutoff.capture());
        assertThat(txCutoff.getValue())
                .isCloseTo(LocalDateTime.now().minusDays(30), within(60, ChronoUnit.SECONDS));
        assertThat(dedupCutoff.getValue())
                .isCloseTo(LocalDateTime.now().minusDays(7), within(60, ChronoUnit.SECONDS));
    }

    @Test
    void clean_withoutDedupDao_skipsDedupSegment() {
        MqTableCleanJob job = new MqTableCleanJob(txLogDao, null, new CommonRocketMqProperties());

        job.cleanExpired();

        verify(txLogDao).deleteBefore(any(LocalDateTime.class));
        verifyNoInteractions(dedupDao);
    }
}
