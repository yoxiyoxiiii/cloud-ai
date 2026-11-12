package com.cloudai.task.handler;

import com.cloudai.task.runtime.TaskCanceledException;
import com.cloudai.task.runtime.TaskContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 演示 handler 单测（种子场景）：参数范围校验、进度推进至 100、
 * failPercent 注入异常、取消检查点异常透传（协作式取消）。
 */
@ExtendWith(MockitoExtension.class)
class DemoBatchHandlerTest {

    private final DemoBatchHandler handler = new DemoBatchHandler();

    @Mock
    private TaskContext ctx;

    @Test
    void executesToEnd_reportsFullPath() throws Exception {
        when(ctx.paramsAs(DemoBatchHandler.DemoParams.class)).thenReturn(new DemoBatchHandler.DemoParams(25, 0));
        String result = handler.execute(ctx);
        assertThat(result).isEqualTo("{\"processed\": 25}");
        // total=25 两批（20+5）：进度 80 与 100 都上报
        org.mockito.Mockito.verify(ctx).reportProgress(80, "批处理中 20/25");
        org.mockito.Mockito.verify(ctx).reportProgress(100, "批处理中 25/25");
    }

    @Test
    void failPercentInjection_throwsWhenProgressReaches() {
        when(ctx.paramsAs(DemoBatchHandler.DemoParams.class)).thenReturn(new DemoBatchHandler.DemoParams(25, 50));
        // 首批进度 80 ≥ 50 即注入失败
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("failPercent=50");
    }

    @Test
    void outOfRangeParams_rejected() {
        when(ctx.paramsAs(DemoBatchHandler.DemoParams.class)).thenReturn(new DemoBatchHandler.DemoParams(0, 0));
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("total");

        when(ctx.paramsAs(DemoBatchHandler.DemoParams.class)).thenReturn(new DemoBatchHandler.DemoParams(10, 101));
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failPercent");
    }

    @Test
    void cancelCheckpoint_propagatesCanceledException() {
        when(ctx.paramsAs(DemoBatchHandler.DemoParams.class)).thenReturn(new DemoBatchHandler.DemoParams(25, 0));
        doThrow(new TaskCanceledException("canceled")).when(ctx).checkCanceled();
        assertThatThrownBy(() -> handler.execute(ctx))
                .isInstanceOf(TaskCanceledException.class);
        // 取消发生在首批进度上报之后（先推进再检查）
        org.mockito.Mockito.verify(ctx).reportProgress(anyInt(), anyString());
    }

    /** params 全缺省：默认 total=100 / failPercent=0 正常跑完 */
    @Test
    void defaultParams_completeFully() throws Exception {
        when(ctx.paramsAs(DemoBatchHandler.DemoParams.class)).thenReturn(new DemoBatchHandler.DemoParams(null, null));
        String result = handler.execute(ctx);
        assertThat(result).isEqualTo("{\"processed\": 100}");
    }
}
