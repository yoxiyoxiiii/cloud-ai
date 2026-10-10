package com.cloudai.bpmn.job;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * xxl-job demo 任务（hello world 模板，2026-10-09 整合验收后保留——拍板 D7）。
 * 新增 xxl-job 任务参照本模板：本包（job/）建类 + 方法标 {@code @XxlJob("handler 名")}
 * （bean 交容器扫描，XxlJobSpringExecutor 启动时收集容器全部 @XxlJob 方法；
 * 两组同名 handler 合法——handler 名作用域=执行器组）
 * → admin 任务管理建任务（执行器组=本服务 appname、handler 名一致、路由 FIRST、
 * 调度 NONE=纯手工触发或 CRON 定时）→ 任务参数经 {@link XxlJobHelper#getJobParam()} 获取。
 */
@Slf4j
@Component
public class CloudDemoJobHandler {

    /** 参数回显（admin 执行日志 + 服务日志双证取证）；不抛异常即成功（xxl 缺省成功口径，不显式 handleFail） */
    @XxlJob("cloudDemoJobHandler")
    public void cloudDemoJobHandler() {
        String param = XxlJobHelper.getJobParam();
        String message = "xxl-job demo handler executed, param=" + param + ", app=cloud-bpmn";
        XxlJobHelper.log(message);
        log.info(message);
    }
}
