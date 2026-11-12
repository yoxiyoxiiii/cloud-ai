package com.cloudai.task.runtime;

/**
 * 任务处理器 SPI（设计 §5.3）：一个 taskType 一个实现，Spring bean 注册（@TaskHandler 注解含
 * @Component 元注解，标注即入容器）。跨语言派发的未来演进（远程执行器分支）收敛在
 * TaskHandlerRegistry 单一入口，本接口不动。
 */
public interface TaskHandler {

    /** 任务类型码（须与类上 @TaskHandler 注解值一致——Registry 启动校验，不一致 fail-fast） */
    String taskType();

    /**
     * 执行任务。实现必须幂等（投递语义 at-least-once：认领后进程崩溃会重跑）；
     * 响应协作式取消=在检查点调 ctx.checkCanceled() 命中即抛 TaskCanceledException；
     * 返回值=成功结果 JSON 文本（可空）。
     */
    String execute(TaskContext ctx) throws Exception;
}
