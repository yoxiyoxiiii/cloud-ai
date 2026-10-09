package com.cloudai.common.rocketmq.consume;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * L2 业务 uk 幂等路径豁免标注：不继承 DedupRocketMQListener 的 @RocketMQMessageListener 消费者
 * 必须标注本注解（守护规则：服务模块 MQ 消费者幂等路径必须显式声明 L1 去重或 L2 uk，二选一）。
 * 标注即承诺：消费幂等由业务唯一键（如 bpmn_approval uk_business）+ DuplicateKey/域码幂等吸收兜底。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.SOURCE)
public @interface UkIdempotentListener {

    /** 幂等依据说明（uk 名或业务键语义，守护报错时随行展示） */
    String value();
}
