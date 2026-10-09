package com.cloudai.bpmn.api.projection;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 审批投影组件配置（契约 2026-10-09-approval-projection-api §1.1 / 设计 D3）。
 * <p>默认关（enabled=false）：cloud-bpmn 自身也引本 api 模块（提供方复用 domain/client），
 * 默认开会使其消费自己发的事件写自己库——消费方（如 cloud-system）两行配置显式启用。</p>
 */
@Data
@ConfigurationProperties(prefix = "cloud.bpmn.projection")
public class ApprovalProjectionProperties {

    /** 投影组件总开关（默认关；开启 = 装配唯一监听对象 + 定时对账） */
    private boolean enabled = false;

    /** 事件消费组名（必配：与 broker 端消费 offset 延续绑定——如 g_system_approval_event 沿用 Round D 组名；
     *  缺失启动报错防组名漂移导致 offset 重置重放，装配处校验） */
    private String consumerGroup;

    /** 定时对账间隔 ms（默认 60s；首轮启动后延迟一个间隔执行，避免启动风暴） */
    private long reconcileIntervalMs = 60000L;

    /** 对账单批上限（对齐平台 /inner/approval/status-list businessKeys ≤100，契约 §4.2） */
    private int reconcileBatchSize = 100;
}
