package com.cloudai.bpmn.service;

import com.cloudai.bpmn.vo.DefinitionVo;
import com.cloudai.bpmn.vo.DefinitionXmlVo;
import com.cloudai.bpmn.vo.DeployResultVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import org.flowable.common.engine.api.FlowableException;
import org.flowable.common.engine.api.FlowableObjectNotFoundException;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentBuilder;
import org.flowable.engine.repository.DeploymentQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.repository.ProcessDefinitionQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流程定义查询单测（契约 2026-10-08 §2：xml 原始资源读取 4008 语义 / deploy 校验链与引擎异常 4009；
 * v1 §4.1 分页既有口径）。
 */
@ExtendWith(MockitoExtension.class)
class DefinitionAppServiceTest {

    private static final String DEF_ID = "leave_approval:1:4";

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

    // ---- findXmlById（契约 2026-10-08 §2.1）----

    @Test
    void findXmlById_mapsFieldsWithRawModel() {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionId(DEF_ID)).thenReturn(query);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        when(query.singleResult()).thenReturn(definition);
        lenient().when(definition.getId()).thenReturn(DEF_ID);
        lenient().when(definition.getKey()).thenReturn("leave_approval");
        lenient().when(definition.getName()).thenReturn("请假审批");
        lenient().when(definition.getVersion()).thenReturn(1);
        String xml = "<?xml version=\"1.0\"?><process id=\"leave_approval\"/>";
        when(repositoryService.getProcessModel(DEF_ID))
                .thenReturn(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        DefinitionXmlVo vo = service.findXmlById(DEF_ID);

        assertThat(vo.getId()).isEqualTo(DEF_ID);
        assertThat(vo.getKey()).isEqualTo("leave_approval");
        assertThat(vo.getName()).isEqualTo("请假审批");
        assertThat(vo.getVersion()).isEqualTo("1");
        assertThat(vo.getXml()).isEqualTo(xml);
    }

    @Test
    void findXmlById_definitionMissing_4008() {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionId(DEF_ID)).thenReturn(query);
        when(query.singleResult()).thenReturn(null);

        assertThatThrownBy(() -> service.findXmlById(DEF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程定义不存在")
                .extracting("code")
                .isEqualTo(4008);
    }

    @Test
    void findXmlById_modelStreamFailure_4008() {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionId(DEF_ID)).thenReturn(query);
        when(query.singleResult()).thenReturn(mock(ProcessDefinition.class));
        when(repositoryService.getProcessModel(DEF_ID))
                .thenThrow(new FlowableObjectNotFoundException("no model"));

        assertThatThrownBy(() -> service.findXmlById(DEF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程定义不存在")
                .extracting("code")
                .isEqualTo(4008);
    }

    @Test
    void findXmlById_blankContent_4008() {
        ProcessDefinitionQuery query = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(query);
        when(query.processDefinitionId(DEF_ID)).thenReturn(query);
        when(query.singleResult()).thenReturn(mock(ProcessDefinition.class));
        when(repositoryService.getProcessModel(DEF_ID))
                .thenReturn(new ByteArrayInputStream(new byte[0]));

        assertThatThrownBy(() -> service.findXmlById(DEF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程定义不存在")
                .extracting("code")
                .isEqualTo(4008);
    }

    // ---- saveDeployment（契约 2026-10-08 §2.2 校验链）----

    @Test
    void saveDeployment_successAssemblesDefinitions() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("leave.bpmn20.xml");
        when(file.getInputStream())
                .thenReturn(new ByteArrayInputStream("<definitions/>".getBytes(StandardCharsets.UTF_8)));
        DeploymentBuilder builder = mock(DeploymentBuilder.class);
        when(repositoryService.createDeployment()).thenReturn(builder);
        when(builder.name("leave.bpmn20.xml")).thenReturn(builder);
        when(builder.addInputStream(org.mockito.ArgumentMatchers.eq("leave.bpmn20.xml"),
                any(InputStream.class))).thenReturn(builder);
        Deployment deployment = mock(Deployment.class);
        when(builder.deploy()).thenReturn(deployment);
        when(deployment.getId()).thenReturn("dep-9");
        stubDefinitionsByDeployment("dep-9", 2);

        DeployResultVo vo = service.saveDeployment(file);

        assertThat(vo.getDeploymentId()).isEqualTo("dep-9");
        assertThat(vo.getDefinitions()).hasSize(1);
        assertThat(vo.getDefinitions().get(0).getVersion()).isEqualTo("2");
        // 部署名与资源名均取上传文件名（契约 §2.2「部署名取文件名」）
        verify(builder).name("leave.bpmn20.xml");
        verify(builder).addInputStream(org.mockito.ArgumentMatchers.eq("leave.bpmn20.xml"),
                any(InputStream.class));
    }

    @Test
    void saveDeployment_emptyFileRejected_4009() {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(true);

        assertThatThrownBy(() -> service.saveDeployment(file))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程文件无效或部署失败")
                .extracting("code")
                .isEqualTo(4009);
    }

    @Test
    void saveDeployment_oversizeRejected_4009() {
        // 2.5MB：业务上限 2MB 之内拦截（三段式第 1 段——不到解析层 3MB）
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn((2L * 1024 * 1024) + 512 * 1024);

        assertThatThrownBy(() -> service.saveDeployment(file))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程文件无效或部署失败")
                .extracting("code")
                .isEqualTo(4009);
    }

    @Test
    void saveDeployment_engineFailureRejected_4009() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("bad.xml");
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream("not bpmn".getBytes(StandardCharsets.UTF_8)));
        DeploymentBuilder builder = mock(DeploymentBuilder.class);
        when(repositoryService.createDeployment()).thenReturn(builder);
        when(builder.name("bad.xml")).thenReturn(builder);
        when(builder.addInputStream(org.mockito.ArgumentMatchers.eq("bad.xml"),
                any(InputStream.class))).thenReturn(builder);
        when(builder.deploy()).thenThrow(new FlowableException("invalid schema"));

        assertThatThrownBy(() -> service.saveDeployment(file))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程文件无效或部署失败")
                .extracting("code")
                .isEqualTo(4009);
    }

    @Test
    void saveDeployment_noDefinitionsRejected_4009() throws Exception {
        // 契约 §2.2「无 process 定义统一归此码」：非 BPMN 资源名文件（如 plain.txt）被引擎
        // 当普通资源存储——部署链不抛异常但零定义，B5 实测 4009-d 缺口的修复回归
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(1024L);
        when(file.getOriginalFilename()).thenReturn("plain.txt");
        when(file.getInputStream())
                .thenReturn(new ByteArrayInputStream("not bpmn".getBytes(StandardCharsets.UTF_8)));
        DeploymentBuilder builder = mock(DeploymentBuilder.class);
        when(repositoryService.createDeployment()).thenReturn(builder);
        when(builder.name("plain.txt")).thenReturn(builder);
        when(builder.addInputStream(org.mockito.ArgumentMatchers.eq("plain.txt"),
                any(InputStream.class))).thenReturn(builder);
        Deployment deployment = mock(Deployment.class);
        when(builder.deploy()).thenReturn(deployment);
        when(deployment.getId()).thenReturn("dep-10");
        ProcessDefinitionQuery definitionQuery = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(definitionQuery.deploymentId("dep-10")).thenReturn(definitionQuery);
        when(definitionQuery.list()).thenReturn(List.of());

        assertThatThrownBy(() -> service.saveDeployment(file))
                .isInstanceOf(BusinessException.class)
                .hasMessage("流程文件无效或部署失败")
                .extracting("code")
                .isEqualTo(4009);
        // 部署确实发生（引擎不解析非 BPMN 名资源），拦截点在组装前校验
        verify(builder).deploy();
    }

    /** 部署反查链：deploymentId → 定义列表（version 递增语义由引擎保证，mock 回 v2） */
    private void stubDefinitionsByDeployment(String deploymentId, int version) {
        ProcessDefinitionQuery definitionQuery = mock(ProcessDefinitionQuery.class);
        when(repositoryService.createProcessDefinitionQuery()).thenReturn(definitionQuery);
        when(definitionQuery.deploymentId(deploymentId)).thenReturn(definitionQuery);
        ProcessDefinition definition = mock(ProcessDefinition.class);
        lenient().when(definition.getId()).thenReturn("leave_approval:2:10");
        lenient().when(definition.getKey()).thenReturn("leave_approval");
        lenient().when(definition.getName()).thenReturn("请假审批");
        lenient().when(definition.getVersion()).thenReturn(version);
        lenient().when(definition.getDeploymentId()).thenReturn(deploymentId);
        when(definitionQuery.list()).thenReturn(List.of(definition));
        // toVo 部署时间回查链
        DeploymentQuery deploymentQuery = mock(DeploymentQuery.class);
        when(repositoryService.createDeploymentQuery()).thenReturn(deploymentQuery);
        when(deploymentQuery.deploymentId(deploymentId)).thenReturn(deploymentQuery);
        Deployment deployment = mock(Deployment.class);
        when(deploymentQuery.singleResult()).thenReturn(deployment);
        lenient().when(deployment.getDeploymentTime()).thenReturn(new Date());
    }
}
