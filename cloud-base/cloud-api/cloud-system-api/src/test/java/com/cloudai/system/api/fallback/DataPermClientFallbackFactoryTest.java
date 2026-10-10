package com.cloudai.system.api.fallback;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.DataPermClient;
import com.cloudai.system.api.domain.DataPermDenyRequest;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限求值降级单测（设计 D21 fail-closed）：两端点均 R.fail(1002) + data=null——
 * evaluate 1002「数据权限服务不可用，请稍后重试」（消费方 code!=SUCCESS 分支拒绝访问，
 * 任何分支不得在求值失败时返回数据）；deny 降级不阻断已发生的拒绝（R.fail 仅供消费方记日志）。
 */
class DataPermClientFallbackFactoryTest {

    private final DataPermClientFallbackFactory factory = new DataPermClientFallbackFactory();

    @Test
    void evaluate_degradesFailClosed_noDataReturned() {
        DataPermClient client = factory.create(new RuntimeException("connection refused"));

        R<DataPermScopeVo> resp = client.evaluate(
                new DataPermEvaluateRequest("userA", "bpmn_approval", "list", null));

        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getMsg()).isEqualTo("数据权限服务不可用，请稍后重试");
        assertThat(resp.getData()).isNull();
    }

    @Test
    void deny_degradesFailClosed_noScopeData() {
        DataPermClient client = factory.create(new RuntimeException("timeout"));

        R<Void> resp = client.deny(new DataPermDenyRequest("userA", "bpmn_approval", "5"));

        assertThat(resp.getCode()).isEqualTo(1002);
        assertThat(resp.getMsg()).isEqualTo("数据权限服务不可用");
        assertThat(resp.getData()).isNull();
    }
}
