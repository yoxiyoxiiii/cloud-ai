package com.cloudai.bpmn.api.mq;

/**
 * 审批 MQ 契约常量（契约 2026-10-09-rocketmq-tx-approval-api §1.1）：topic/tag 跨服务对齐物，
 * 与消息模型同归提供方 api 模块（设计 D4）。生产/消费 group 与 executor channel 属服务内部实现，
 * 各服务自持不进本类。
 */
public final class ApprovalMqTopics {

    /** 流1 发起审批事务半消息 topic（system → bpmn） */
    public static final String TOPIC_TX_APPROVAL_CREATE = "TX_APPROVAL_CREATE";

    /** 流2 审批事件通知 topic（bpmn → system；tag 区分 CREATE_RESULT/TERMINAL） */
    public static final String TOPIC_APPROVAL_EVENT_NOTIFY = "APPROVAL_EVENT_NOTIFY";

    /** 流2 tag：发起结果（result=SUCCESS/FAILED） */
    public static final String TAG_CREATE_RESULT = "CREATE_RESULT";

    /** 流2 tag：终态（terminalStatus=1/2/3） */
    public static final String TAG_TERMINAL = "TERMINAL";

    /** 流2 CREATE_RESULT result 值：发起成功（approvalId 回填） */
    public static final String RESULT_SUCCESS = "SUCCESS";

    /** 流2 CREATE_RESULT result 值：发起失败（消费端确定性失败白名单，status 置 4） */
    public static final String RESULT_FAILED = "FAILED";

    private ApprovalMqTopics() {
    }
}
