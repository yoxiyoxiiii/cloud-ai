package com.cloudai.bpmn.api.fallback;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.common.core.domain.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

import java.util.List;

/**
 * BpmnApprovalClient 降级工厂（设计 D3/D4）：log.error 根因（cause）后返回中性失败 R
 * （code=1002）——调用方走既有 code!=SUCCESS 分支转译域码（system 侧 3022「审批服务不可用」），
 * 端到端与现行 catch 路径逐字等价。bean 经 BpmnApiAutoConfiguration 注册（引 jar 即生效）。
 */
@Slf4j
public class BpmnApprovalClientFallbackFactory implements FallbackFactory<BpmnApprovalClient> {

    /** 中性降级码：1xxx 通用业务失败（语义演进的中性下游码记移交备忘，本轮等价迁移） */
    static final int DEGRADED_CODE = 1002;

    static final String DEGRADED_MSG = "cloud-bpmn 服务不可用";

    @Override
    public BpmnApprovalClient create(Throwable cause) {
        return new BpmnApprovalClient() {

            @Override
            public R<InnerApprovalCreateVo> create(ApprovalCreateInnerRequest req) {
                log.error("审批服务降级（create）: businessKey={}", req.getBusinessKey(), cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }

            @Override
            public R<List<InnerApprovalStatusVo>> statusList(ApprovalStatusQueryInnerRequest req) {
                log.error("审批服务降级（statusList）: keys={}", req.getBusinessKeys() == null
                        ? 0 : req.getBusinessKeys().size(), cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }

            @Override
            public R<Void> cancel(ApprovalCancelInnerRequest req) {
                log.error("审批服务降级（cancel）: businessKey={}", req.getBusinessKey(), cause);
                return R.fail(DEGRADED_CODE, DEGRADED_MSG);
            }
        };
    }
}
