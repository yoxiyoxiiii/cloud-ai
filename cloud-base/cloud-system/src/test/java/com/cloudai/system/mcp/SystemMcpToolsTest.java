package com.cloudai.system.mcp;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.mcp.auth.McpPrincipal;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataPermColumnContext;
import com.cloudai.system.service.SysDictDataManageService;
import com.cloudai.system.service.SysUserManageService;
import com.cloudai.system.vo.SysUserVo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * MCP 业务工具测试（契约 §2-§4）：
 * 有 perms 查用户透传分页结果 / 分页缺省值 / 字典工具免 perms / 字典空结果透传。
 * 权限拒绝已注解化，由 starter 切面全链路强制（starter 集成测试覆盖），直调方法不在切面路径上。
 * 列动作应用增量：真实链路 pageList 会写列上下文，此处 mock 不写——用例各自显式搭设
 * （空 scope=透传不改写 / mask+hidden scope=逐行改写 / 上下文缺失=fail-closed 3042）。
 */
@ExtendWith(MockitoExtension.class)
class SystemMcpToolsTest {

    @Mock
    private SysUserManageService userManageService;

    @Mock
    private SysDictDataManageService dictDataManageService;

    @InjectMocks
    private SystemMcpTools tools;

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        DataPermColumnContext.clear();
    }

    private void withPrincipal(List<String> perms) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (perms != null) {
            request.setAttribute(McpPrincipal.ATTR, new McpPrincipal(1L, "admin", perms));
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    /** 真实 pageList 的接线语义：无论有无列动作都 set——空 scope 表示列权限已求值且无动作 */
    private void withEmptyColumnContext() {
        DataPermColumnContext.set("user", ColumnScope.of(Set.of(), Set.of()));
    }

    @Test
    void queryUsers_withPerm_returnsPageResult() {
        withPrincipal(List.of("system:user:list"));
        withEmptyColumnContext();
        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        PageResult<SysUserVo> expected = PageResult.of(1L, List.of(new SysUserVo()));
        given(userManageService.pageList(captor.capture())).willReturn(expected);

        PageResult<SysUserVo> result = tools.queryUsers(2, 5);

        assertThat(result).isSameAs(expected);
        assertThat(captor.getValue().getPageNum()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void queryUsers_nullPagingArgs_usesDefaults() {
        withPrincipal(List.of("system:user:list"));
        withEmptyColumnContext();
        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        PageResult<SysUserVo> expected = PageResult.of(0L, List.of());
        given(userManageService.pageList(captor.capture())).willReturn(expected);

        tools.queryUsers(null, null);

        assertThat(captor.getValue().getPageNum()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(10);
    }

    // ---- 列动作应用增量（设计 §12 D68）：工具体直调 applier ----

    @Test
    void queryUsers_columnScopeApplied_maskAndHiddenRewritten() {
        withPrincipal(List.of("system:user:list"));
        SysUserVo row = new SysUserVo();
        row.setMobile("13800000001");
        row.setEmail("admin@example.com");
        given(userManageService.pageList(any())).willReturn(PageResult.of(1L, List.of(row)));
        DataPermColumnContext.set("user",
                ColumnScope.of(Set.of("email"), Set.of("mobile")));

        PageResult<SysUserVo> result = tools.queryUsers(1, 10);

        // 与 REST 面同规则同改写：masked 整值 ***、hidden 置 null（序列化前完成，converter 消费已改写对象）
        assertThat(result.getRows().get(0).getMobile()).isEqualTo("***");
        assertThat(result.getRows().get(0).getEmail()).isNull();
    }

    @Test
    void queryUsers_contextMissing_failClosed3042() {
        withPrincipal(List.of("system:user:list"));
        given(userManageService.pageList(any())).willReturn(PageResult.of(0L, List.of()));
        // 不搭列上下文（mock pageList 不写真实值）：防御分支——对齐列 advice 的 3042 冻结口径

        assertThatThrownBy(() -> tools.queryUsers(1, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("数据权限列处理异常")
                .extracting("code")
                .isEqualTo(3042);
    }

    @Test
    void queryDictItems_noPermCheck_emptyPermsStillWorks() {
        withPrincipal(List.of());
        List<DictItemEntry> expected = List.of(new DictItemEntry());
        given(dictDataManageService.listByDictKey("user_status")).willReturn(expected);

        List<DictItemEntry> result = tools.queryDictItems("user_status");

        assertThat(result).isSameAs(expected);
    }

    @Test
    void queryDictItems_unknownKey_returnsEmptyList() {
        withPrincipal(List.of());
        given(dictDataManageService.listByDictKey("no_such_key")).willReturn(List.of());

        List<DictItemEntry> result = tools.queryDictItems("no_such_key");

        assertThat(result).isEmpty();
    }
}
