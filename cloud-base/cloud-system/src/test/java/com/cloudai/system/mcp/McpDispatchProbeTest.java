package com.cloudai.system.mcp;

import com.cloudai.common.mcp.CloudMcpAutoConfiguration;
import com.cloudai.common.mcp.McpPrincipal;
import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.DispatcherServletAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.ServletWebServerFactoryAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * MCP dispatch 运行时探针：验证技术分析文档的三条静态结论。
 * 1) 工具方法在 Tomcat 请求线程同步执行（非异步 dispatch）；
 * 2) 工具方法内 RequestContextHolder 可达且能读到过滤器放入的 McpPrincipal；
 * 3) @Around 切面对 @Tool 方法命中（AOP 权限方案可行性的运行时终审）。
 * 最小上下文设计：只装配 servlet+MCP server+被测过滤器，不启用数据源/MQ 等无关自动配置。
 */
@SpringBootTest(classes = McpDispatchProbeTest.ProbeBootstrap.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cloud.mcp.enabled=true",
        "cloud.mcp.client-secret=probe-secret",
        "cloud.jwt.secret=probe-jwt-secret-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
})
class McpDispatchProbeTest {

    /** HS512 测试密钥（>=64 字节），与上面 properties 同值 */
    private static final String JWT_SECRET =
            "probe-jwt-secret-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final String INIT_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"0\"}}}";
    private static final String CALL_BODY = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
            + "\"params\":{\"name\":\"probe_echo\",\"arguments\":{}}}";

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    /** 探针捕获器（静态供切面与方法体双端写入；测试前 reset） */
    static final class ProbeCapture {
        static final AtomicReference<String> toolThread = new AtomicReference<>();
        static final AtomicReference<String> aspectThread = new AtomicReference<>();
        static final AtomicInteger aspectHits = new AtomicInteger();
        static volatile boolean requestContextPresent;
        static volatile List<String> principalPerms;

        static void reset() {
            toolThread.set(null);
            aspectThread.set(null);
            aspectHits.set(0);
            requestContextPresent = false;
            principalPerms = null;
        }
    }

    @Test
    void dispatch_synchronous_and_aspectFires_onRequestThread() {
        ProbeCapture.reset();
        stubOnlineSession("probe-jti", "[\"probe:perm\"]");
        String jwt = JwtUtil.createToken(JWT_SECRET, 1L, "probe", "probe-jti", 600);

        HttpHeaders headers = mcpHeaders(jwt);
        ResponseEntity<String> init = rest.postForEntity("/mcp",
                new HttpEntity<>(INIT_BODY, headers), String.class);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);

        headers.set("Mcp-Session-Id", init.getHeaders().getFirst("Mcp-Session-Id"));
        ResponseEntity<String> call = rest.postForEntity("/mcp",
                new HttpEntity<>(CALL_BODY, headers), String.class);
        assertThat(call.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(call.getBody()).contains("probe-ok");

        System.out.println("[PROBE] toolThread=" + ProbeCapture.toolThread.get()
                + " aspectThread=" + ProbeCapture.aspectThread.get()
                + " aspectHits=" + ProbeCapture.aspectHits.get()
                + " rhc=" + ProbeCapture.requestContextPresent
                + " perms=" + ProbeCapture.principalPerms);

        assertThat(ProbeCapture.toolThread.get()).as("工具方法应跑在 Tomcat 工作线程").startsWith("http-nio-");
        assertThat(ProbeCapture.aspectThread.get()).as("切面线程应与工具方法同线程（全链路同步）")
                .isEqualTo(ProbeCapture.toolThread.get());
        assertThat(ProbeCapture.aspectHits.get()).as("切面应命中至少一次").isGreaterThanOrEqualTo(1);
        assertThat(ProbeCapture.requestContextPresent).as("工具方法内 RequestContextHolder 应可达").isTrue();
        assertThat(ProbeCapture.principalPerms).as("应读到过滤器放入的 principal.perms")
                .containsExactly("probe:perm");
    }

    @Test
    void noCredential_returns401() {
        ResponseEntity<String> resp = rest.postForEntity("/mcp",
                new HttpEntity<>(INIT_BODY, plainJsonHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("\"code\":401");
    }

    private void stubOnlineSession(String jti, String permsJsonArray) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(ops);
        given(ops.get("sso:online:" + jti)).willReturn("{\"permissions\":" + permsJsonArray + "}");
    }

    /** MCP POST 头三件：双 Accept（协议硬性要求）+ 双主体认证头 */
    private HttpHeaders mcpHeaders(String jwt) {
        HttpHeaders headers = plainJsonHeaders();
        headers.set("X-MCP-Client-Secret", "probe-secret");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);
        return headers;
    }

    private HttpHeaders plainJsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        return headers;
    }

    /** 探针工具：方法体记录线程与上下文证据。
     * 必须 public：包私有声明类上的公有方法经代理反射调用会因未 setAccessible 抛
     * IllegalAccessException（真实工具类均为 public @Component，此处对齐真实形态）。 */
    public static class ProbeEchoTool {

        @Tool(name = "probe_echo", description = "dispatch probe tool")
        public String echo() {
            ProbeCapture.toolThread.set(Thread.currentThread().getName());
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            ProbeCapture.requestContextPresent = attrs != null;
            if (attrs != null) {
                McpPrincipal principal = (McpPrincipal) attrs.getRequest().getAttribute(McpPrincipal.ATTR);
                ProbeCapture.principalPerms = principal == null ? null : principal.perms();
            }
            return "probe-ok";
        }
    }

    /** 探针切面：@annotation(Tool) 切点——与正式切面同拦截机制，命中即可行 */
    @Aspect
    static class ProbeAspect {

        @Around("@annotation(org.springframework.ai.tool.annotation.Tool)")
        public Object aroundTool(ProceedingJoinPoint pjp) throws Throwable {
            ProbeCapture.aspectThread.set(Thread.currentThread().getName());
            ProbeCapture.aspectHits.incrementAndGet();
            return pjp.proceed();
        }
    }

    /** 最小装配：servlet 栈 + Spring AI MCP server 链 + starter 过滤器自动装配（验证回接后的真实装配链） */
    @SpringBootConfiguration
    @ImportAutoConfiguration({
            DispatcherServletAutoConfiguration.class,
            ServletWebServerFactoryAutoConfiguration.class,
            WebMvcAutoConfiguration.class,
            HttpMessageConvertersAutoConfiguration.class,
            AopAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.McpServerObjectMapperAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.McpServerAutoConfiguration.class,
            org.springframework.ai.mcp.server.common.autoconfigure.ToolCallbackConverterAutoConfiguration.class,
            org.springframework.ai.mcp.server.autoconfigure.McpServerStreamableHttpWebMvcAutoConfiguration.class,
            CloudMcpAutoConfiguration.class
    })
    @Import(ProbeToolConfig.class)
    @EnableConfigurationProperties(JwtProperties.class)
    static class ProbeBootstrap {
    }

    @Configuration
    static class ProbeToolConfig {

        @Bean
        public ProbeEchoTool probeEchoTool() {
            return new ProbeEchoTool();
        }

        @Bean
        public ToolCallbackProvider probeToolCallbackProvider(ProbeEchoTool tool) {
            return MethodToolCallbackProvider.builder().toolObjects(tool).build();
        }

        @Bean
        public ProbeAspect probeAspect() {
            return new ProbeAspect();
        }
    }
}
