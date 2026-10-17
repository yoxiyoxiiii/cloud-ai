package com.cloudai.common.mcp;

import com.cloudai.common.security.props.JwtProperties;
import com.cloudai.common.security.util.JwtUtil;
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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * starter 全链路集成测试（真 HTTP，RANDOM_PORT）：验 401 body、权限拒绝文本、放行与登录即可档。
 * 同时是「组件可被任意服务消费」的常驻证明——bpmn/sso 接入前的长期回归资产。
 */
@SpringBootTest(classes = CloudMcpStarterIntegrationTest.ItBootstrap.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "cloud.mcp.enabled=true",
        "cloud.mcp.client-secret=it-secret",
        "cloud.jwt.secret=it-jwt-secret-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
        "spring.ai.mcp.server.name=starter-it",
        "spring.ai.mcp.server.version=1.0.0",
        "spring.ai.mcp.server.protocol=streamable"
})
class CloudMcpStarterIntegrationTest {

    private static final String JWT_SECRET =
            "it-jwt-secret-0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private static final String INIT_BODY = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
            + "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
            + "\"clientInfo\":{\"name\":\"it\",\"version\":\"0\"}}}";

    @org.springframework.beans.factory.annotation.Autowired
    private TestRestTemplate rest;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @Test
    void noCredential_401_withContractBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));

        ResponseEntity<String> resp = rest.postForEntity("/mcp",
                new HttpEntity<>(INIT_BODY, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isEqualTo("{\"code\":401,\"msg\":\"MCP 认证失败\",\"data\":null}");
    }

    @Test
    void permSufficient_guardedTool_returnsResult() {
        String body = callTool("it_guarded", "jti-ok", "[\"probe:perm:use\"]");
        assertThat(body).contains("guarded-ok");
        assertThat(body).doesNotContain("PERMISSION_DENIED");
    }

    @Test
    void permMissing_guardedTool_returnsPermissionDeniedText() {
        String body = callTool("it_guarded", "jti-no", "[]");
        assertThat(body).contains("\"isError\":true");
        assertThat(body).contains("PERMISSION_DENIED: probe:perm:use");
    }

    @Test
    void loginOnly_openTool_allowedWithEmptyPerms() {
        String body = callTool("it_open", "jti-no", "[]");
        assertThat(body).contains("open-ok");
    }

    /** 三步协议走真 HTTP：initialize → 拿会话头 → tools/call，返回 SSE 响应体文本 */
    private String callTool(String toolName, String jti, String permsJsonArray) {
        stubOnlineSession(jti, permsJsonArray);
        String jwt = JwtUtil.createToken(JWT_SECRET, 1L, "it", jti, 600);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(List.of(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON));
        headers.set("X-MCP-Client-Secret", "it-secret");
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + jwt);

        ResponseEntity<String> init = rest.postForEntity("/mcp",
                new HttpEntity<>(INIT_BODY, headers), String.class);
        assertThat(init.getStatusCode()).isEqualTo(HttpStatus.OK);
        headers.set("Mcp-Session-Id", init.getHeaders().getFirst("Mcp-Session-Id"));

        String callBody = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + toolName + "\",\"arguments\":{}}}";
        ResponseEntity<String> call = rest.postForEntity("/mcp",
                new HttpEntity<>(callBody, headers), String.class);
        assertThat(call.getStatusCode()).isEqualTo(HttpStatus.OK);
        return call.getBody();
    }

    private void stubOnlineSession(String jti, String permsJsonArray) {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(ops);
        given(ops.get("sso:online:" + jti)).willReturn("{\"permissions\":" + permsJsonArray + "}");
    }

    /** 测试工具面：一个 perms 硬档 + 一个登录即可档（注解空值显式标注）。
     * 类必须 public：包私有声明类经代理反射调用会 IllegalAccessException（运行时实证结论）。 */
    public static class ItTool {

        @Tool(name = "it_guarded", description = "perm-guarded probe tool")
        @McpRequirePerm("probe:perm:use")
        public String guarded() {
            return "guarded-ok";
        }

        @Tool(name = "it_open", description = "login-only probe tool")
        @McpRequirePerm
        public String open() {
            return "open-ok";
        }
    }

    @Configuration
    static class ItToolConfig {

        @Bean
        public ItTool itTool() {
            return new ItTool();
        }

        @Bean
        public ToolCallbackProvider itToolCallbackProvider(ItTool tool) {
            return MethodToolCallbackProvider.builder().toolObjects(tool).build();
        }
    }

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
    @Import(ItToolConfig.class)
    @EnableConfigurationProperties(JwtProperties.class)
    static class ItBootstrap {
    }
}
