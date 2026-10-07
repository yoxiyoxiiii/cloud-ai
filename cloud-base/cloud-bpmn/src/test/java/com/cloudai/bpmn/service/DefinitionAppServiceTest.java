package com.cloudai.bpmn.service;

import com.cloudai.bpmn.vo.DefinitionVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程定义查询单测（契约 §4.1：latestVersion + key 升序 + 手写偏移分页 + 部署时间回查）。
 */
@ExtendWith(MockitoExtension.class)
class DefinitionAppServiceTest {

    @Mock
    private RepositoryService repositoryService;
    @InjectMocks
    private DefinitionAppService service;

    @Test
    void pageList_offsetsAndMapsFields() {
        ProcessDefinitionQuery listQuery = mock(ProcessDefinitionQuery.class);
        ProcessDefinitionQuery countQuery = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(listQuery, countQuery);
        when(listQuery.latestVersion()).thenReturn(listQuery);
        when(listQuery.orderByProcessDefinitionKey()).thenReturn(listQuery);
        when(listQuery.asc()).thenReturn(listQuery);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        lenient().when(definition.getId()).thenReturn("leave_approval:1:4");
        lenient().when(definition.getKey()).thenReturn("leave_approval");
        lenient().when(definition.getName()).thenReturn("leave_approval-process");
        lenient().when(definition.getVersion()).thenReturn(1);
        lenient().when(definition.getDeploymentId()).thenReturn("dep-1");
        when(listQuery.listPage(10, 10)).thenReturn(List.of(definition));
        when(countQuery.latestVersion()).thenReturn(countQuery);
        when(countQuery.count()).thenReturn(1L);
        DeploymentQuery deploymentQuery = mock(DeploymentQuery.class);
        when(repositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);
        when(deploymentQuery.deploymentId("dep-1")).thenReturn(deploymentQuery);
        Deployment deployment = mock(Deployment.class);
        Date deployedAt = new Date();
        when(deploymentQuery.singleResult()).thenReturn(deployment);
        lenient().when(deployment.getDeploymentTime()).thenReturn(deployedAt);

        PageQuery query = new PageQuery();
        query.setPageNum(2);
        query.setPageSize(10);
        PageResult<DefinitionVo> result = service.pageList(query);

        verify(listQuery).listPage(10, 10);
        assertThat(result.getTotal()).isEqualTo(1L);
        DefinitionVo vo = result.getRows().get(0);
        assertThat(vo.getId()).isEqualTo("leave_approval:1:4");
        assertThat(vo.getKey()).isEqualTo("leave_approval");
        assertThat(vo.getVersion()).isEqualTo("1");
        assertThat(vo.getDeploymentTime()).isNotBlank();
    }

    @Test
    void pageList_blankDeploymentYieldsNullTime() {
        ProcessDefinitionQuery listQuery = mock(ProcessDefinitionQuery.class);
        ProcessDefinitionQuery countQuery = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(listQuery, countQuery);
        when(listQuery.latestVersion()).thenReturn(listQuery);
        when(listQuery.orderByProcessDefinitionKey()).thenReturn(listQuery);
        when(listQuery.asc()).thenReturn(listQuery);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        lenient().when(definition.getKey()).thenReturn("other_process");
        lenient().when(definition.getVersion()).thenReturn(2);
        lenient().when(definition.getDeploymentId()).thenReturn(null);
        when(listQuery.listPage(0, 10)).thenReturn(List.of(definition));
        when(countQuery.latestVersion()).thenReturn(countQuery);
        when(countQuery.count()).thenReturn(1L);

        PageQuery query = new PageQuery();
        query.setPageNum(1);
        query.setPageSize(10);
        PageResult<DefinitionVo> result = service.pageList(query);

        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getDeploymentTime()).isNull();
        assertThat(result.getRows().get(0).getVersion()).isEqualTo("2");
    }
}
