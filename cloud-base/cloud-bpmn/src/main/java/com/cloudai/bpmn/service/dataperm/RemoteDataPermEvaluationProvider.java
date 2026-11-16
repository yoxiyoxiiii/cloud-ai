package com.cloudai.bpmn.service.dataperm;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.api.client.DataPermClient;
import com.cloudai.system.api.dataperm.DataPermEvaluation;
import com.cloudai.system.api.dataperm.DataPermEvaluationProvider;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 远端求值后端（规则库在 cloud_system——跨库消费方走 Feign）：求值失败 fail-closed 全路径
 * 拒绝访问（任何分支不得在求值失败时返回数据）；胶水自 ApprovalQueryService.evalScope
 * 原样迁入（语义逐字不变）。当前登录人由 SecurityUtils 解析（网关注入 X-User-* 头）。
 * @author jy-root
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RemoteDataPermEvaluationProvider implements DataPermEvaluationProvider {

    private final DataPermClient dataPermClient;

    @Override
    public DataPermEvaluation evaluate(String resource, String operation, String businessKey) {
        String account = SecurityUtils.currentAccount();
        R<DataPermScopeVo> resp;
        try {
            resp = dataPermClient.evaluate(
                    new DataPermEvaluateRequest(account, resource, operation, businessKey));
        } catch (Exception e) {
            log.error("数据权限求值 Feign 调用失败（fail-closed 拒绝访问）: account={}, resource={}, operation={}",
                    account, resource, operation, e);
            throw new BusinessException("数据权限服务不可用，请稍后重试");
        }
        if (resp == null || resp.getCode() != ErrorCode.SUCCESS.getCode() || resp.getData() == null) {
            log.error("数据权限求值返回失败（fail-closed 拒绝访问）: account={}, resource={}, operation={}, code={}",
                    account, resource, operation, resp == null ? null : resp.getCode());
            throw new BusinessException(resp == null || resp.getMsg() == null
                    ? "数据权限服务不可用，请稍后重试" : resp.getMsg());
        }
        DataPermScopeVo scopeVo = resp.getData();
        return new DataPermEvaluation(resource, scopeVo.toColumnScope(), scopeVo.toRowFilter());
    }
}
