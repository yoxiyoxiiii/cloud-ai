package com.cloudai.system.mcp;

import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.translate.domain.DictItemEntry;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * MCP 业务工具测试（契约 §2-§4）：
 * 有 perms 查用户透传分页结果 / 无 perms 权限拒绝 / 字典工具免 perms / 字典空结果透传。
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
    }

    private void withPrincipal(List<String> perms) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (perms != null) {
            request.setAttribute(McpPrincipal.ATTR, new McpPrincipal(1L, "admin", perms));
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test
    void queryUsers_withPerm_returnsPageResult() {
        withPrincipal(List.of("system:user:list"));
        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        PageResult<SysUserVo> expected = PageResult.of(1L, List.of(new SysUserVo()));
        given(userManageService.pageList(captor.capture())).willReturn(expected);

        PageResult<SysUserVo> result = tools.queryUsers(2, 5);

        assertThat(result).isSameAs(expected);
        assertThat(captor.getValue().getPageNum()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
    }

    @Test
    void queryUsers_withoutPerm_throwsPermissionDenied() {
        withPrincipal(List.of());
        assertThatThrownBy(() -> tools.queryUsers(1, 10))
                .isInstanceOf(McpPermissionDeniedException.class)
                .hasMessageContaining("PERMISSION_DENIED: system:user:list");
    }

    @Test
    void queryUsers_nullPagingArgs_usesDefaults() {
        withPrincipal(List.of("system:user:list"));
        ArgumentCaptor<PageQuery> captor = ArgumentCaptor.forClass(PageQuery.class);
        PageResult<SysUserVo> expected = PageResult.of(0L, List.of());
        given(userManageService.pageList(captor.capture())).willReturn(expected);

        tools.queryUsers(null, null);

        assertThat(captor.getValue().getPageNum()).isEqualTo(1);
        assertThat(captor.getValue().getPageSize()).isEqualTo(10);
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
