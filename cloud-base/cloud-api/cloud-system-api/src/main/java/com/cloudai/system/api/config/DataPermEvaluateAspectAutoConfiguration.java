package com.cloudai.system.api.config;

import com.cloudai.system.api.dataperm.DataPermEvaluateAspect;
import com.cloudai.system.api.dataperm.DataPermEvaluationProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * 求值切面装配（独立于列 advisor 装配——单一装配条件不互相牵连，沿 DataPermColumnAdvisorAutoConfiguration
 * 拆类先例）：SERVLET 条件（REST 读路径所在宿主）；@ConditionalOnMissingBean 用户可覆盖。
 * <b>不设功能开关</b>（与列 advisor 同口径）：关闭 = 标注方法静默跳过求值 = 行级无过滤返回
 * = fail-open，紧急止损路径是回滚版本而非线上开关。求值后端由宿主注册
 * {@link DataPermEvaluationProvider} bean 提供，缺失时切面调用即拒绝（fail-closed，见切面）。
 * @author jy-root
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DataPermEvaluateAspectAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataPermEvaluateAspect dataPermEvaluateAspect(
            ObjectProvider<DataPermEvaluationProvider> provider) {
        return new DataPermEvaluateAspect(provider);
    }
}
