package com.cloudai.task.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 任务引擎基础设施配置：Clock 统一注入（看门狗超时判定/进度节流），单测可替换固定时钟。
 */
@Configuration
public class TaskEngineConfig {

    @Bean
    public Clock taskClock() {
        return Clock.systemDefaultZone();
    }
}
