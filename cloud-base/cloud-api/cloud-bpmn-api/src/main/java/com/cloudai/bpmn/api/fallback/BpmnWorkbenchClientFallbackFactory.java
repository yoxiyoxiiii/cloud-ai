package com.cloudai.bpmn.api.fallback;

import com.cloudai.bpmn.api.client.BpmnWorkbenchClient;
import com.cloudai.bpmn.api.domain.ApprovalVo;
import com.cloudai.bpmn.api.domain.TaskVo;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * BpmnWorkbenchClient 降级工厂：log.error 根因（cause）后返回中性失败 R（code=1002），
 * 聚合服务按契约 §2 转译为段状态 7002（msg 取本降级文案）。bean 经 BpmnApiAutoConfiguration 注册。
 */
@Slf4j
public class BpmnWorkbenchClientFallbackFactory implements FallbackFactory<BpmnWorkbenchClient> {

    /** 中性降级码：1xxx 通用业务失败（与 BpmnApprovalClientFallbackFactory 同口径） */
    static final int DEGRADED_CODE = 1002;

    static final String DEGRADED_MSG = "cloud-bpmn 服务不可用";

    @Override
    public BpmnWorkbenchClient create(Throwable cause) {
        return new BpmnWorkbenchClient() {

            @Override
            public R<PageResult<TaskVo>> todo(Integer pageNum, Integer pageSize) {
                log.error("审批平台工作台降级（todo）: pageNum={}, pageSize={}", pageNum, pageSize, cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }

            @Override
            public R<PageResult<ApprovalVo>> page(Integer pageNum, Integer pageSize) {
                log.error("审批平台工作台降级（page）: pageNum={}, pageSize={}", pageNum, pageSize, cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }
        };
    }
}
