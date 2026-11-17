package com.cloudai.system.service.dataperm;

import com.cloudai.common.dataperm.evaluate.DataPermEvaluation;
import com.cloudai.common.dataperm.evaluate.DataPermEvaluationProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 本地求值后端（规则库同库——提供方不自我 Feign，沿组件化设计既有口径）：包一层
 * DataPermEvaluator.evaluate（当前登录人解析与留痕均在求值器内），供 @DataPermEvaluate
 * 切面消费。留痕、fail-safe 语义与 Service 手工调用时期逐字一致。
 * @author jy-root
 */
@Service
@RequiredArgsConstructor
public class LocalDataPermEvaluationProvider implements DataPermEvaluationProvider {

    private final DataPermEvaluator evaluator;

    @Override
    public DataPermEvaluation evaluate(String resource, String operation, String businessKey) {
        DataPermDecision decision = evaluator.evaluate(resource, operation, businessKey);
        return new DataPermEvaluation(resource, decision.getColumnScope(), decision.getRowFilter());
    }
}
