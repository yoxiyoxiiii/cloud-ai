package com.cloudai.bpmn.controller;

import com.cloudai.bpmn.service.DefinitionAppService;
import com.cloudai.bpmn.vo.DefinitionVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程定义端点委托单测（契约 2026-10-07-bpmn-leave-api §4.1）。
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
}
