package com.cloudai.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 通知中心配置（通知中心设计 §6：架构口径非密钥，仓库化默认值、Nacos 可覆盖，
 * 沿 CloudSystemMqDlqProperties 形态；规范同前缀多值对象封装）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cloud.system.notify")
public class CloudSystemNotifyProperties {

    /** 已读收件行清理保留天数（未读行永不删——删未读=丢用户消息） */
    private int retentionDays = 30;
}
