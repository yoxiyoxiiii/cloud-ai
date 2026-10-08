package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.service.DefinitionAppService;
import com.cloudai.bpmn.vo.DefinitionVo;
import com.cloudai.bpmn.vo.DefinitionXmlVo;
import com.cloudai.bpmn.vo.DeployResultVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程定义端点委托单测（契约 2026-10-08-bpmn-diagram-designer-api §2 两新端点 +
 * v1 §4.1 分页既有口径；注解存在性锁定 perms 与 multipart 消费类型）。
 */
@ExtendWith(MockitoExtension.class)
class DefinitionControllerTest {

    @Mock
    private DefinitionAppService definitionAppService;
    @InjectMocks
    private DefinitionController controller;

    @Test
    void page_delegates() {
        PageQuery query = new PageQuery();
        DefinitionVo vo = new DefinitionVo();
        vo.setKey("leave_approval");
        PageResult<DefinitionVo> page = PageResult.of(1, java.util.List.of(vo));
        when(definitionAppService.pageList(query)).thenReturn(page);

        R<PageResult<DefinitionVo>> result = controller.page(query);

        verify(definitionAppService).pageList(query);
        assertThat(result.getData().getTotal()).isEqualTo(1L);
        assertThat(result.getData().getRows()).extracting(DefinitionVo::getKey).containsExactly("leave_approval");
    }

    @Test
    void xml_delegates() throws Exception {
        DefinitionXmlVo vo = new DefinitionXmlVo();
        vo.setId("leave_approval:1:4");
        when(definitionAppService.findXmlById("leave_approval:1:4")).thenReturn(vo);

        R<DefinitionXmlVo> result = controller.xml("leave_approval:1:4");

        verify(definitionAppService).findXmlById("leave_approval:1:4");
        assertThat(result.getData()).isSameAs(vo);
        Method method = DefinitionController.class.getMethod("xml", String.class);
        assertThat(method.getAnnotation(GetMapping.class).value()).containsExactly("/{id}/xml");
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('bpmn:definition:list')");
    }

    @Test
    void deploy_delegates() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        DeployResultVo vo = new DeployResultVo();
        vo.setDeploymentId("dep-9");
        when(definitionAppService.saveDeployment(file)).thenReturn(vo);

        R<DeployResultVo> result = controller.deploy(file);

        verify(definitionAppService).saveDeployment(file);
        assertThat(result.getData()).isSameAs(vo);
        Method method = DefinitionController.class.getMethod("deploy", MultipartFile.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);
        assertThat(mapping.value()).containsExactly("/deploy");
        assertThat(mapping.consumes()).containsExactly(MediaType.MULTIPART_FORM_DATA_VALUE);
        assertThat(method.getAnnotation(PreAuthorize.class).value())
                .isEqualTo("hasAuthority('bpmn:definition:deploy')");
    }
}
