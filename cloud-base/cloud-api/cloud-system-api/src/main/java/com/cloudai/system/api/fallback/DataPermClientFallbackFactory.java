package com.cloudai.system.api.fallback;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.client.DataPermClient;
import com.cloudai.system.api.domain.DataPermDenyRequest;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.openfeign.FallbackFactory;

/**
 * DataPermClient 降级工厂（组件化设计 D21 fail-closed）：evaluate 返回 1002 +「数据权限服务不可用」
 * ——消费方统一 {@code code != SUCCESS} 分支转 BusinessException 拒绝访问，不降级为无过滤或空集
 * 冒充正常（数据权限降级放行=越权泄露；空集会伪装成「没有数据」误导用户与排查，报错是诚实信号）。
 * deny 补痕降级仅 log.error（审计行丢失记日志，不阻断 4018/域码已定的拒绝语义）。
 * 中性降级码演进沿 SystemUserClientFallbackFactory 同款移交记档。
 */
@Slf4j
public class DataPermClientFallbackFactory implements FallbackFactory<DataPermClient> {

    @Override
    public DataPermClient create(Throwable cause) {
        return new DataPermClient() {

            @Override
            public R<DataPermScopeVo> evaluate(DataPermEvaluateRequest request) {
                log.error("数据权限求值降级（fail-closed，拒绝访问）: account={}, resource={}",
                        request.getAccount(), request.getResource(), cause);
                return R.fail(1002, "数据权限服务不可用，请稍后重试");
            }

            @Override
            public R<Void> deny(DataPermDenyRequest request) {
                log.error("数据权限 deny 补痕降级（审计丢失风险记日志）: account={}, resource={}, businessKey={}",
                        request.getAccount(), request.getResource(), request.getBusinessKey(), cause);
                return R.fail(1002, "数据权限服务不可用");
            }
        };
    }
}
