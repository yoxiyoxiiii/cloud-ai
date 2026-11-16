package com.cloudai.common.dataperm;

/**
 * 数据权限求值后端 SPI（切面经它取决策）：宿主服务注册一个 bean 即激活 @DataPermEvaluate 链路。
 * 双实现形态：规则库同库的提供方走本地求值（如 cloud-system 直接包 DataPermEvaluator）；
 * 跨库消费方走 Feign 求值 + fail-closed 胶水（如 cloud-bpmn 包 DataPermClient——求值失败
 * 一律抛异常拒绝访问，不降级无过滤/空集）。当前登录人由实现方自行解析。
 * @author jy-root
 */
public interface DataPermEvaluationProvider {

    /**
     * 真实决策求值（恒留痕——留痕义务在求值侧，本地求值器与 /inner/data-perm 面既有语义不变）。
     *
     * @param resource   资源标识（注册表常量）
     * @param operation  操作类型（list/detail）
     * @param businessKey 单据键（detail 类留痕定位；list 类 null）
     * @return 列 scope + 行过滤的终态决策
     */
    DataPermEvaluation evaluate(String resource, String operation, String businessKey);
}
