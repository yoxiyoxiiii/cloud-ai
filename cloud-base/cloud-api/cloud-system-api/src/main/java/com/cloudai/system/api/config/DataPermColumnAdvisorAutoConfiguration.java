package com.cloudai.system.api.config;

import com.cloudai.system.api.dataperm.DataPermColumnAdvice;
import com.cloudai.system.api.dataperm.DataPermColumnContextFilter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * 列 advisor 装配（设计 D51，独立类——不塞 SystemApiAutoConfiguration：该类现存降级工厂 bean
 * 无条件装配，混入需 servlet 条件的 advice 会让降级工厂一并被条件化，行为面外溢）：
 * SERVLET 条件——非 web 宿主引 api jar 零装配；@ConditionalOnMissingBean 用户可覆盖。
 *
 * <p><b>不设功能开关</b>（D57）：开关关闭 = advice 失效 = 上下文永不消费 = fail-open 明文返回，
 * 关闭本身就是越权面，与 fail-closed 总纲矛盾；紧急止损路径是回滚版本而非线上开关。
 * 装配条件的唯一「关」是宿主非 SERVLET（技术性，非功能开关）。</p>
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DataPermColumnAdvisorAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataPermColumnAdvice dataPermColumnAdvice() {
        return new DataPermColumnAdvice();
    }

    @Bean
    @ConditionalOnMissingBean
    public DataPermColumnContextFilter dataPermColumnContextFilter() {
        return new DataPermColumnContextFilter();
    }
}
