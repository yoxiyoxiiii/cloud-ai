package com.cloudai.system.controller;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.dto.TableConfigSaveRequest;
import com.cloudai.system.service.TableConfigService;
import com.cloudai.system.vo.TableConfigVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 表格列配置三端点委托转发单测（契约 2026-10-23-table-config-api §2）：
 * controller 只转发 Service；另钉「三端点免 @PreAuthorize」契约（沿 my-scope 先例——
 * 登录用户自理本人偏好非管理能力，权限注解会把普通用户基本功能变成管理入口）。
 */
@ExtendWith(MockitoExtension.class)
class TableConfigControllerTest {

    @Mock
    private TableConfigService tableConfigService;
    @InjectMocks
    private TableConfigController controller;

    @Test
    void getMyConfig_returnsVoData() {
        TableConfigVo vo = new TableConfigVo();
        vo.setColumns(List.of());
        when(tableConfigService.findMyConfig("system:user")).thenReturn(vo);

        R<TableConfigVo> result = controller.getMyConfig("system:user");

        verify(tableConfigService).findMyConfig("system:user");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isSameAs(vo);
    }

    @Test
    void getMyConfig_noConfig_returnsNullData() {
        when(tableConfigService.findMyConfig("system:user")).thenReturn(null);

        R<TableConfigVo> result = controller.getMyConfig("system:user");

        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isNull();
    }

    @Test
    void saveMyConfig_delegatesWithBody() {
        TableConfigSaveRequest req = new TableConfigSaveRequest();
        req.setColumns(List.of());

        R<Void> result = controller.saveMyConfig("system:user", req);

        verify(tableConfigService).saveMyConfig("system:user", req);
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isNull();
    }

    @Test
    void deleteMyConfig_delegates() {
        R<Void> result = controller.deleteMyConfig("system:user");

        verify(tableConfigService).deleteMyConfig("system:user");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isNull();
    }

    @Test
    void threeEndpoints_freeOfPreAuthorize() throws Exception {
        // 免权限口径契约钉：挂了权限注解即把个人偏好端点变成管理入口（翻案须动契约）
        for (String name : List.of("getMyConfig", "saveMyConfig", "deleteMyConfig")) {
            Method method = TableConfigController.class.getMethod(name,
                    name.equals("saveMyConfig")
                            ? new Class<?>[]{String.class, TableConfigSaveRequest.class}
                            : new Class<?>[]{String.class});
            assertThat(method.getAnnotation(PreAuthorize.class))
                    .as("端点 %s 须免 @PreAuthorize（登录即可，沿 my-scope 先例）", name)
                    .isNull();
        }
    }
}
