package com.cloudai.common.dataperm;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.ObjectProvider;

import java.lang.reflect.Method;

/**
 * 数据权限求值切面（@DataPermEvaluate 方法入口执行）：后端求值 → 无条件 set 列处理上下文
 * （空 scope 也 set，接线声明语义沿用列 advisor 设计）→ 决策入 {@link DataPermEvaluation}
 * → proceed。取代 Service 侧「evaluate + DataPermColumnContext.set」手工两行样板；
 * 列上下文生命周期仍归 DataPermColumnContextFilter（请求级双层清理），本切面只管
 * 自己的求值 holder（finally 恢复嵌套前值——同请求多标注方法串行调用不串值）。
 *
 * <p>切点用字面量形式（不用参数名绑定形式）：绑定式需 -parameters 编译参数解析通知方法参数名，
 * 本工程未启用该编译参数，绑定式切点会静默不命中（MCP 切面运行时实证的同源结论）；注解值
 * 改为反射读取。businessKey 同理仅支持 #pN 位置引用（名字引用同因静默失效）。</p>
 *
 * <p>fail-closed：宿主未注册 {@link DataPermEvaluationProvider} 时标注方法调用即拒绝
 * （IllegalStateException）——静默跳过求值 = 行级无过滤返回 = fail-open，与列 advisor
 * 总纲方向相反，不存在该分支。</p>
 * @author jy-root
 */
@Aspect
@RequiredArgsConstructor
public class DataPermEvaluateAspect {

    private final ObjectProvider<DataPermEvaluationProvider> provider;

    @Around("@annotation(com.cloudai.common.dataperm.DataPermEvaluate)")
    public Object evaluateAndWire(ProceedingJoinPoint pjp) throws Throwable {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        DataPermEvaluate annotation = method.getAnnotation(DataPermEvaluate.class);
        DataPermEvaluationProvider evaluationProvider = provider.getIfAvailable();
        if (evaluationProvider == null) {
            throw new IllegalStateException(
                    "未注册 DataPermEvaluationProvider——@" + "DataPermEvaluate 标注方法无法求值（fail-closed）: "
                            + method);
        }
        String businessKey = resolveBusinessKey(annotation.businessKey(), pjp.getArgs(), method);
        DataPermEvaluation evaluation = evaluationProvider.evaluate(
                annotation.resource(), annotation.operation(), businessKey);
        DataPermColumnContext.set(annotation.resource(), evaluation.columnScope());
        DataPermEvaluation previous = DataPermEvaluation.current();
        DataPermEvaluation.set(evaluation);
        try {
            return pjp.proceed();
        } finally {
            if (previous != null) {
                DataPermEvaluation.set(previous);
            } else {
                DataPermEvaluation.clear();
            }
        }
    }

    /**
     * businessKey 位置引用解析：空 = list 类无单据键；仅支持 #pN（N=参数下标，String.valueOf）。
     * 名字引用（#id）与常量串不支持——无 -parameters 下参数名不可发现、常量串无需求，
     * 非法表达式首调用即抛（配置错误显性化，不留静默 null 分支）。
     */
    private String resolveBusinessKey(String expression, Object[] args, Method method) {
        if (expression == null || expression.isBlank()) {
            return null;
        }
        if (!expression.matches("#p\\d+")) {
            throw new IllegalArgumentException(
                    "businessKey 仅支持 #pN 位置引用（如 #p0=首参单据 id）: \"" + expression + "\" @ " + method);
        }
        int index = Integer.parseInt(expression.substring(2));
        if (index >= args.length) {
            throw new IllegalArgumentException(
                    "businessKey 位置引用越界: \"" + expression + "\"，实参 " + args.length + " 个 @ " + method);
        }
        Object value = args[index];
        return value == null ? null : String.valueOf(value);
    }
}
