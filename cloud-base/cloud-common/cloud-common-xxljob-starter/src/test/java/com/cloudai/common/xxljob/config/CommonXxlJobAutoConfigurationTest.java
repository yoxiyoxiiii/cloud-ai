package com.cloudai.common.xxljob.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 门控单测（设计 D8，对齐 ApprovalProjectionAutoConfigurationTest 形态）：
 * ①cloud.common.xxljob.enabled 未配 → 默认关，无 XxlJobSpringExecutor bean（同时覆盖 imports 注册路径生效）；
 * ②enabled=true + xxl.job.* 样例值 → bean 存在且 Properties→setter 映射断言。
 * 用例②显式置 xxl.job.executor.enabled=false（官方内层启动旗标）：XxlJobSpringExecutor 实现
 * SmartInitializingSingleton，全量 refresh 会真调 start() 绑端口/注册——置 false 使其早退跳过
 * （start 仅日志即返回，3.5.0 源码核实），映射断言不受影响，单测零网络/端口副作用。
 */
class CommonXxlJobAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CommonXxlJobAutoConfiguration.class));

    @Test
    void disabledByDefault_noExecutorBean() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(XxlJobSpringExecutor.class);
            assertThat(context).doesNotHaveBean(CommonXxlJobProperties.class);
        });
    }

    @Test
    void enabled_registersExecutorWithMappedProperties() {
        runner.withPropertyValues(
                        "cloud.common.xxljob.enabled=true",
                        "xxl.job.executor.enabled=false",
                        "xxl.job.admin.addresses=http://127.0.0.1:18081",
                        "xxl.job.executor.appname=cloud-system",
                        "xxl.job.executor.port=19202",
                        "xxl.job.executor.accessToken=default_token")
                .run(context -> {
                    assertThat(context).hasSingleBean(XxlJobSpringExecutor.class);
                    XxlJobSpringExecutor executor = context.getBean(XxlJobSpringExecutor.class);
                    assertThat(executor.getAppname()).isEqualTo("cloud-system");
                    assertThat(executor.getPort()).isEqualTo(19202);
                    assertThat(executor.getAccessToken()).isEqualTo("default_token");
                    assertThat(executor.getGlueEnabled()).isFalse();
                    // adminAddresses 无官方 getter——经 Properties 绑定断言（键名官方零转译直证）
                    CommonXxlJobProperties properties = context.getBean(CommonXxlJobProperties.class);
                    assertThat(properties.getAdmin().getAddresses()).isEqualTo("http://127.0.0.1:18081");
                });
    }
}
