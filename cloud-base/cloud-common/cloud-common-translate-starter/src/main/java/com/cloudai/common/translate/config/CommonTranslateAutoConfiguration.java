package com.cloudai.common.translate.config;

import com.cloudai.common.redis.config.CommonRedisAutoConfiguration;
import com.cloudai.common.translate.core.TransFieldScanner;
import com.cloudai.common.translate.core.TranslateAdvisor;
import com.cloudai.common.translate.core.TranslationCacheService;
import com.cloudai.common.translate.provider.DictSourceProvider;
import com.cloudai.common.translate.provider.UserSourceProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 翻译组件自动装配：Advisor + 扫描器 + 缓存服务（@ConditionalOnMissingBean，用户同名 bean 可覆盖）。
 * Provider 为服务侧 @Service 实现，缺省不装——无实现时缓存未命中返回空 + log.warn 一次（翻译降级 null）。
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@AutoConfigureAfter(CommonRedisAutoConfiguration.class)
public class CommonTranslateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TransFieldScanner transFieldScanner() {
        return new TransFieldScanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public TranslationCacheService translationCacheService(RedisTemplate<String, Object> redisTemplate,
                                                           ObjectProvider<DictSourceProvider> dictSourceProvider,
                                                           ObjectProvider<UserSourceProvider> userSourceProvider) {
        return new TranslationCacheService(redisTemplate, dictSourceProvider, userSourceProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public TranslateAdvisor translateAdvisor(TransFieldScanner transFieldScanner,
                                             TranslationCacheService translationCacheService) {
        return new TranslateAdvisor(transFieldScanner, translationCacheService);
    }
}
