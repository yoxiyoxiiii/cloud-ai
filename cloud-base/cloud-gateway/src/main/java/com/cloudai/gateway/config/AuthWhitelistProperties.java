package com.cloudai.gateway.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关鉴权白名单（cloud.gateway.auth.whitelist），AuthGlobalFilter 以 startsWith 前缀匹配放行。
 * 固化在本工程 application.yml 随 jar 打包、刻意不进 Nacos——白名单是安全边界，不开放配置中心
 * 热改；且 Nacos import 的优先级高于包内 yml，云上误配同名键会静默覆盖，以包内文件为唯一真相源。
 * 条目语义与坑（inner 项尾斜杠段感知、MCP 屏蔽项无尾斜杠）见 application.yml 内注释。
 * @author jy-root
 */
@Data
@ConfigurationProperties(prefix = "cloud.gateway.auth")
public class AuthWhitelistProperties {

    /** 前缀白名单；空列表 = 全量鉴权 = 登录端点一并 401（自锁），启动即失败 */
    private List<String> whitelist = new ArrayList<>();

    /** fail-fast 校验：漏配/空配直接拒绝启动（放行业务在 AuthGlobalFilter） */
    @PostConstruct
    public void requireWhitelist() {
        if (whitelist.isEmpty()) {
            throw new IllegalStateException(
                    "cloud.gateway.auth.whitelist 未配置或为空——空白名单会把登录/刷新端点一并 401 造成自锁");
        }
    }
}
