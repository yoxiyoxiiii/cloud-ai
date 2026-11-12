package com.cloudai.task.runtime;

import com.cloudai.task.entity.AsyncTask;
import com.cloudai.task.mapper.AsyncTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 执行上下文单测（设计 §5.3）：进度节流（间隔内跳过、100% 恒写、钳位）、
 * 取消检查点四分支（行缺/标志/非执行中/健康）、params 解析默认值。
 */
@ExtendWith(MockitoExtension.class)
class TaskContextTest {

    /** 可拨动时钟：节流测试不真等 500ms */
    private static final class MutableClock extends Clock {
        private long millis;

        private MutableClock(long start) {
            this.millis = start;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.systemDefault();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        private void advance(long ms) {
            millis += ms;
        }
    }

    @Mock
    private AsyncTaskMapper mapper;

    private TaskContext context(long throttleMs, Clock clock) {
        return new TaskContext(7L, null, mapper, throttleMs, clock);
    }

    @Test
    void reportProgress_throttlesWithinInterval_butAlwaysWritesHundred() {
        MutableClock clock = new MutableClock(0);
        TaskContext ctx = context(500, clock);

        ctx.reportProgress(10, "第一批");
        clock.advance(100);
        ctx.reportProgress(20, "第二批");
        clock.advance(100);
        ctx.reportProgress(30, "第三批");
        // 500ms 内仅首写落库
        verify(mapper).updateProgress(7L, 10, "第一批");
        verifyNoMoreInteractions(mapper);

        clock.advance(400); // 距首写 600ms ≥ 节流间隔
        ctx.reportProgress(60, "第四批");
        verify(mapper).updateProgress(7L, 60, "第四批");

        clock.advance(10); // 不足间隔，但 100% 恒写（收尾进度不得丢）
        ctx.reportProgress(100, "完成");
        verify(mapper).updateProgress(7L, 100, "完成");
    }

    @Test
    void reportProgress_clampsOutOfRangePercent() {
        MutableClock clock = new MutableClock(0);
        TaskContext ctx = context(0, clock);
        ctx.reportProgress(150, "越界");
        ctx.reportProgress(-5, "负值");
        verify(mapper).updateProgress(7L, 100, "越界");
        verify(mapper).updateProgress(7L, 0, "负值");
    }

    @Test
    void checkCanceled_throwsOnRowMissing_flagged_orNotRunning() {
        when(mapper.findById(7L)).thenReturn(null);
        assertThatThrownBy(() -> context(0, Clock.systemDefaultZone()).checkCanceled())
                .isInstanceOf(TaskCanceledException.class);

        AsyncTask flagged = new AsyncTask();
        flagged.setCancelRequested(1);
        flagged.setStatus(AsyncTask.StatusEnum.RUNNING.getCode());
        when(mapper.findById(7L)).thenReturn(flagged);
        assertThatThrownBy(() -> context(0, Clock.systemDefaultZone()).checkCanceled())
                .isInstanceOf(TaskCanceledException.class);

        AsyncTask terminal = new AsyncTask();
        terminal.setCancelRequested(0);
        terminal.setStatus(AsyncTask.StatusEnum.SUCCESS.getCode());
        when(mapper.findById(7L)).thenReturn(terminal);
        assertThatThrownBy(() -> context(0, Clock.systemDefaultZone()).checkCanceled())
                .isInstanceOf(TaskCanceledException.class);
    }

    @Test
    void checkCanceled_passesOnHealthyRunningRow() {
        AsyncTask healthy = new AsyncTask();
        healthy.setCancelRequested(0);
        healthy.setStatus(AsyncTask.StatusEnum.RUNNING.getCode());
        when(mapper.findById(7L)).thenReturn(healthy);
        assertThatCode(() -> context(0, Clock.systemDefaultZone()).checkCanceled())
                .doesNotThrowAnyException();
    }

    /** params 为 null 时解析为全默认值实例（record 缺省字段语义） */
    @Test
    void paramsAs_nullParamsYieldsDefaults() {
        TaskContext ctx = new TaskContext(1L, null, mapper, 0, Clock.systemDefaultZone());
        DemoParams params = ctx.paramsAs(DemoParams.class);
        org.assertj.core.api.Assertions.assertThat(params.total()).isNull();
        org.assertj.core.api.Assertions.assertThat(params.failPercent()).isNull();
    }

    private record DemoParams(Integer total, Integer failPercent) {
    }
}
