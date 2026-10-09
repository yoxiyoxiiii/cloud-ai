package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnBusinessTypeMapper;
import com.cloudai.common.core.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 业务类型配置读取单测（设计 D2：4014 强校验 / 容忍版直通 / detail_route 渲染校验——
 * 以 / 开头且无 //，失败置 null 隐藏跳转）。
 */
@ExtendWith(MockitoExtension.class)
class BusinessTypeRegistryTest {

    private static final int ERR_BUSINESS_TYPE_NOT_FOUND = 4014;

    @Mock
    private BpmnBusinessTypeMapper businessTypeMapper;
    @InjectMocks
    private BusinessTypeRegistry registry;

    @Test
    void findByTypeCode_found() {
        when(businessTypeMapper.findByTypeCode("leave")).thenReturn(config());

        BpmnBusinessType found = registry.findByTypeCode("leave");

        assertThat(found.getProcessKey()).isEqualTo("leave_approval");
    }

    @Test
    void findByTypeCode_missing_throws4014() {
        when(businessTypeMapper.findByTypeCode("ghost")).thenReturn(null);

        assertThatThrownBy(() -> registry.findByTypeCode("ghost"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(ERR_BUSINESS_TYPE_NOT_FOUND))
                .hasMessageContaining("业务类型不存在");
    }

    @Test
    void findByTypeCodeOrNull_missing_returnsNull() {
        when(businessTypeMapper.findByTypeCode("ghost")).thenReturn(null);

        assertThat(registry.findByTypeCodeOrNull("ghost")).isNull();
    }

    @Test
    void renderDetailPath_replacesPlaceholder() {
        String rendered = registry.renderDetailPath("/system/leave?approval={businessKey}", "7");

        assertThat(rendered).isEqualTo("/system/leave?approval=7");
    }

    @Test
    void renderDetailPath_blankRoute_returnsNull() {
        assertThat(registry.renderDetailPath(null, "7")).isNull();
        assertThat(registry.renderDetailPath("  ", "7")).isNull();
    }

    @Test
    void renderDetailPath_relativeRoute_rejectedToNull() {
        // 不以 / 开头：开放重定向形态，置 null 隐藏跳转
        assertThat(registry.renderDetailPath("system/leave?approval={businessKey}", "7")).isNull();
    }

    @Test
    void renderDetailPath_doubleSlashRejectedToNull() {
        assertThat(registry.renderDetailPath("//system/leave", "7")).isNull();
        assertThat(registry.renderDetailPath("/system//leave", "7")).isNull();
    }

    private BpmnBusinessType config() {
        BpmnBusinessType config = new BpmnBusinessType();
        config.setTypeCode("leave");
        config.setTypeName("请假申请");
        config.setProcessKey("leave_approval");
        config.setDetailRoute("/system/leave?approval={businessKey}");
        return config;
    }
}
