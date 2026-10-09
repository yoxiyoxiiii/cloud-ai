package com.cloudai.bpmn.api.fallback;

import com.cloudai.bpmn.api.client.BpmnApprovalClient;
import com.cloudai.bpmn.api.domain.ApprovalCancelInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalCreateInnerRequest;
import com.cloudai.bpmn.api.domain.ApprovalStatusQueryInnerRequest;
import com.cloudai.bpmn.api.domain.InnerApprovalCreateVo;
import com.cloudai.bpmn.api.domain.InnerApprovalStatusVo;
import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 降级返回中性失败（设计 D4 等价表）：三方法 code/msg 固定，data=null */
class BpmnApprovalClientFallbackFactoryTest {

    private final BpmnApprovalClientFallbackFactory factory = new BpmnApprovalClientFallbackFactory();

    @Test
    void create_degradesToNeutralFail() {
        BpmnApprovalClient client = factory.create(new RuntimeException("connection refused"));
        R<InnerApprovalCreateVo> resp = client.create(new ApprovalCreateInnerRequest());
        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getMsg()).isEqualTo("cloud-bpmn 服务不可用");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void statusList_degradesToNeutralFail() {
        BpmnApprovalClient client = factory.create(new RuntimeException("timeout"));
        R<List<InnerApprovalStatusVo>> resp = client.statusList(new ApprovalStatusQueryInnerRequest());
        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getData()).isNull();
    }

    @Test
    void cancel_degradesToNeutralFail() {
        BpmnApprovalClient client = factory.create(new RuntimeException("timeout"));
        R<Void> resp = client.cancel(new ApprovalCancelInnerRequest());
        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getMsg()).isEqualTo("cloud-bpmn 服务不可用");
    }
}
