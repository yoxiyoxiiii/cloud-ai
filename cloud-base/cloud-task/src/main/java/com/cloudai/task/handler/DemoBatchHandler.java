package com.cloudai.task.handler;

import com.cloudai.task.runtime.TaskContext;
import com.cloudai.task.runtime.annotation.TaskHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 演示性批量任务（种子场景）：params {"total":100, "failPercent":0}。
 * 模拟分批处理（每批 20 条 sleep 200ms）并按批上报进度/检查取消；
 * failPercent>0 时在约该进度处抛异常，用于验证重试/失败/告警链路。
 * 纯模拟无副作用——天然幂等（投递语义 at-least-once 的要求）。
 */
@Slf4j
@Component
@TaskHandler(DemoBatchHandler.TYPE)
public class DemoBatchHandler implements com.cloudai.task.runtime.TaskHandler {

    public static final String TYPE = "demo-batch";

    /** 每批处理条数 */
    private static final int BATCH_SIZE = 20;

    /** 每批耗时（毫秒），模拟真实批处理节奏 */
    private static final long BATCH_SLEEP_MS = 200;

    /** total 上限（越界抛异常落 error_msg，提交端同款范围校验 6002） */
    private static final int TOTAL_MAX = 10000;

    /** 演示参数（Jackson 反序列化目标；字段可缺省） */
    public record DemoParams(Integer total, Integer failPercent) {
    }

    @Override
    public String taskType() {
        return TYPE;
    }

    @Override
    public String execute(TaskContext ctx) throws Exception {
        DemoParams params = ctx.paramsAs(DemoParams.class);
        int total = params.total() == null ? 100 : params.total();
        int failPercent = params.failPercent() == null ? 0 : params.failPercent();
        if (total < 1 || total > TOTAL_MAX) {
            throw new IllegalArgumentException("total 越界（1-" + TOTAL_MAX + "）: " + total);
        }
        if (failPercent < 0 || failPercent > 100) {
            throw new IllegalArgumentException("failPercent 越界（0-100）: " + failPercent);
        }
        int processed = 0;
        while (processed < total) {
            Thread.sleep(BATCH_SLEEP_MS);
            processed = Math.min(processed + BATCH_SIZE, total);
            int progress = processed * 100 / total;
            ctx.reportProgress(progress, "批处理中 " + processed + "/" + total);
            ctx.checkCanceled();
            if (failPercent > 0 && progress >= failPercent) {
                throw new RuntimeException("模拟处理失败（注入 failPercent=" + failPercent + "）");
            }
        }
        return "{\"processed\": " + total + "}";
    }
}
