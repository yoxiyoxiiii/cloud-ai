package com.cloudai.aggregate.config;

import com.cloudai.aggregate.security.UserHeaderForwardInterceptor;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JCircuitBreakerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 聚合服务 Feign 与段编排装配：用户头转发拦截器 + 段并行线程池 + 熔断执行线程定制。
 * 线程池 core=max（取 cloud.aggregate.pool-size，默认 8）、SynchronousQueue 不堆积、
 * CallerRuns 池满时退化调用线程串行执行——正确性无损只损时延。
 */
@Configuration
public class AggregateFeignConfig {

    /** 用户头转发拦截器 bean：注册后对全服务所有 Feign client 出站生效 */
    @Bean
    public UserHeaderForwardInterceptor userHeaderForwardInterceptor() {
        return new UserHeaderForwardInterceptor();
    }

    /**
     * 熔断器执行线程改为调用线程直跑：resilience4j 默认把被熔断包装的 Feign 调用提交到
     * 独立线程池（timeLimiter/bulkhead 两条路径皆如此），用户头 ThreadLocal 上下文跨不过
     * 线程切换，出站请求会丢身份头。同线程执行后超时保护由 Feign 自身 connect/read 超时承担。
     */
    @Bean
    public Customizer<Resilience4JCircuitBreakerFactory> sameThreadCircuitBreakerCustomizer() {
        return factory -> factory.configureExecutorService(new SameThreadExecutorService());
    }

    /** 段并行线程池：三段互不依赖，并行提交摊平下游时延（串行=三段之和，与聚合动机相悖） */
    @Bean
    public ThreadPoolExecutor sectionExecutor(AggregateProperties properties) {
        int size = properties.getPoolSize();
        return new ThreadPoolExecutor(size, size, 0L, TimeUnit.MILLISECONDS,
                new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /** 提交即当前线程执行的无服务周期 ExecutorService（熔断包装的调用不换线程） */
    static final class SameThreadExecutorService extends AbstractExecutorService {
        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            // 无后台资源，无需处理
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            return java.util.Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
