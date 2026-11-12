package com.cloudai.task.runtime;

/**
 * 协作式取消异常：TaskContext.checkCanceled() 命中取消标志（或行已不在执行中）时抛出，
 * TaskEngine 捕获后转已取消终态（4）。handler 无需自行处理——检查点调用即可。
 */
public class TaskCanceledException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public TaskCanceledException(String message) {
        super(message);
    }
}
