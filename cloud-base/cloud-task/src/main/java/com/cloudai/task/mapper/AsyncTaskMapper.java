package com.cloudai.task.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.task.entity.AsyncTask;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 异步任务表 SQL（XML：mapper/AsyncTaskMapper.xml）。
 * 流水式表无 deleted 列（终态行到期物理清理，沿 mq_tx_log 先例）——查询无 deleted=0 过滤、
 * 清理为物理 DELETE；审计时间由 SQL NOW() 取数据库时钟。
 * 状态推进全部单语句条件 UPDATE（WHERE status=...）：重投/并发竞争时空更新无害，
 * 整个任务域零 @Transactional（单表单语句语句级原子已足）。
 */
public interface AsyncTaskMapper {

    AsyncTask findById(@Param("id") Long id);

    /** 分页查询：无 LIMIT，由 PaginationInnerInterceptor 追加；动态筛选 status/taskType/submitBy */
    IPage<AsyncTask> pageList(Page<AsyncTask> page,
                              @Param("status") Integer status,
                              @Param("taskType") String taskType,
                              @Param("submitBy") String submitBy);

    /** 幂等键查重（uk_type_biz 等值）：返回既有行供 6003 msg 携带 taskId，null=不存在 */
    AsyncTask findByTypeBiz(@Param("taskType") String taskType, @Param("bizKey") String bizKey);

    int save(AsyncTask task);

    /** 认领候选（status=0 按 create_time 升序取前 n）：显式 LIMIT 是认领取数语义（非分页），注入面仅内部容量值 */
    List<Long> listQueuedIds(@Param("limit") int limit);

    /** 两步 CAS 认领第一步：条件写 status 0→1 并落 start_time/executor_id，affected 即实际认领数 */
    int claimTasks(@Param("ids") List<Long> ids, @Param("executorId") String executorId);

    /** 认领第二步回查本批行（executor_id+status=1 双条件，多实例竞争下只取到自己认领的） */
    List<AsyncTask> listClaimed(@Param("ids") List<Long> ids, @Param("executorId") String executorId);

    /** 进度上报（条件写：仅执行中行可更新，节流由 TaskContext 控制） */
    int updateProgress(@Param("id") Long id,
                       @Param("progress") int progress,
                       @Param("phase") String phase);

    /** 终态推进（2成功/3失败/4已取消）：动态列 + finish_time=NOW()，条件写仅执行中行可进终态 */
    int updateTerminal(@Param("id") Long id,
                       @Param("status") int status,
                       @Param("errorMsg") String errorMsg,
                       @Param("result") String result);

    /** 排队中取消：直接置已取消终态（条件写 0→4） */
    int cancelQueued(@Param("id") Long id);

    /** 执行中协作式取消：置 cancel_requested=1，handler 下一检查点响应（条件写仅执行中行） */
    int flagCancelRunning(@Param("id") Long id);

    /** 手动重试（仅失败/已取消可重试）：回排队并清进度/错误/结果，retry_count+1 */
    int retryTask(@Param("id") Long id);

    /** 执行异常自动重试（retry_count<max_retries 判定后）：执行中行原子重置回排队尾部 */
    int requeueFailed(@Param("id") Long id);

    /** 启动孤儿恢复：进程重启前仍执行中的行重置回排队重跑（at-least-once 投递语义） */
    int requeueRunning();

    /** 僵尸执行中行兜底（超时+300s 宽限仍 RUNNING=本地看门狗漏网，xxl-job 清扫判死） */
    List<AsyncTask> listZombieRunning(@Param("limit") int limit);

    /** 终态行保留期物理清理（分批 LIMIT，每分钟清扫任务推进） */
    int deleteTerminalBefore(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
