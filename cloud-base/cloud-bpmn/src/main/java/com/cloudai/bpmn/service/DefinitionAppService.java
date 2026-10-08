package com.cloudai.bpmn.service;

import com.cloudai.bpmn.util.BpmnDateUtil;
import com.cloudai.bpmn.vo.DefinitionVo;
import com.cloudai.bpmn.vo.DefinitionXmlVo;
import com.cloudai.bpmn.vo.DeployResultVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.DeploymentQuery;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 流程定义查询与部署（契约 2026-10-08-bpmn-diagram-designer-api §2 + v1 §4.1）：
 * 分页 latestVersion 过滤 + key 升序手写偏移分页；xml 取部署时原始资源（getProcessModel，
 * 非 BpmnModel 往返——注释零丢失）；deploy 为 multipart 部署链（安全模型契约 §1）。
 * 只读/单引擎操作不加事务注解（部署 D4 记档：规范「多表写」口径不适用）。
 * 部署时间不在 ProcessDefinition 投影上——经 deploymentId 回查 Deployment（定义量级个位数，N+1 可容忍）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefinitionAppService {

    private static final int ERR_DEFINITION_NOT_FOUND = 4008;
    private static final int ERR_DEPLOY_INVALID = 4009;

    /** 部署业务上限 2MB（契约 §2.2 三段式第 1 段；解析层 3MB/4MB 只兜极端值，异常归口同码） */
    private static final long DEPLOY_MAX_BYTES = 2L * 1024 * 1024;

    /** 上传文件名缺失时的资源名兜底（部署名与资源名同源，契约 §2.2） */
    private static final String DEFAULT_RESOURCE_NAME = "process.bpmn20.xml";

    private final RepositoryService repositoryService;

    /** 定义分页（latestVersion，key 升序） */
    public PageResult<DefinitionVo> pageList(PageQuery query) {
        List<ProcessDefinition> definitions = repositoryService.createProcessDefinitionQuery()
                .latestVersion()
                .orderByProcessDefinitionKey().asc()
                .listPage(offset(query), query.getPageSize());
        long total = repositoryService.createProcessDefinitionQuery()
                .latestVersion()
                .count();
        List<DefinitionVo> rows = definitions.stream().map(this::toVo).toList();
        return PageResult.of(total, rows);
    }

    /** 定义 XML（契约 §2.1）：key/name/version 取定义投影，xml 读部署时原始资源（UTF-8） */
    public DefinitionXmlVo findXmlById(String id) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(id)
                .singleResult();
        if (definition == null) {
            throw new BusinessException(ERR_DEFINITION_NOT_FOUND, "流程定义不存在");
        }
        DefinitionXmlVo vo = new DefinitionXmlVo();
        vo.setId(definition.getId());
        vo.setKey(definition.getKey());
        vo.setName(definition.getName());
        vo.setVersion(String.valueOf(definition.getVersion()));
        vo.setXml(readModel(id));
        return vo;
    }

    /** 部署流程（契约 §2.2）：前置校验空文件/2MB 业务上限 → 4009；引擎部署链异常 → 4009；
     *  无 process 定义（非 BPMN 资源名文件被引擎当普通资源存储，部署"成功"但零定义）→ 4009
     *  统一归此码；成功后按 deploymentId 反查本次产生的定义（不做 latestVersion 过滤——
     *  同 key 原样重部署 version+1 亦在此列） */
    public DeployResultVo saveDeployment(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ERR_DEPLOY_INVALID, "流程文件无效或部署失败");
        }
        if (file.getSize() > DEPLOY_MAX_BYTES) {
            throw new BusinessException(ERR_DEPLOY_INVALID, "流程文件无效或部署失败");
        }
        String deploymentId = deploy(file);
        List<DefinitionVo> definitions = listByDeployment(deploymentId);
        if (definitions.isEmpty()) {
            log.error("部署未产生流程定义（文件名非 BPMN 资源模式或内容无 process 定义）: fileName={} deploymentId={}",
                    fileName(file), deploymentId);
            throw new BusinessException(ERR_DEPLOY_INVALID, "流程文件无效或部署失败");
        }
        DeployResultVo vo = new DeployResultVo();
        vo.setDeploymentId(deploymentId);
        vo.setDefinitions(definitions);
        return vo;
    }

    private int offset(PageQuery query) {
        return (query.getPageNum() - 1) * query.getPageSize();
    }

    /** 读原始资源流（R2 已验 7.2 形态 InputStream，缺失抛 FlowableObjectNotFoundException）；
     *  引擎异常/空流/空内容统一 4008（契约语义：定义不在库） */
    private String readModel(String id) {
        String xml;
        try (InputStream stream = repositoryService.getProcessModel(id)) {
            xml = stream == null ? null : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("流程定义资源读取失败: {}", id, e);
            throw new BusinessException(ERR_DEFINITION_NOT_FOUND, "流程定义不存在");
        }
        if (xml == null || xml.isBlank()) {
            log.error("流程定义资源缺失或内容为空: {}", id);
            throw new BusinessException(ERR_DEFINITION_NOT_FOUND, "流程定义不存在");
        }
        return xml;
    }

    /** 引擎部署链：部署名与资源名取上传文件名；FlowableException（解析/schema/无 process 定义）
     *  与流异常统一 log.error 后 4009——msg 固定文案，不透传引擎栈与英文文案（契约 §2.2） */
    private String deploy(MultipartFile file) {
        String resourceName = fileName(file);
        try (InputStream stream = file.getInputStream()) {
            Deployment deployment = repositoryService.createDeployment()
                    .name(resourceName)
                    .addInputStream(resourceName, stream)
                    .deploy();
            return deployment.getId();
        } catch (Exception e) {
            log.error("流程部署失败: {}", resourceName, e);
            throw new BusinessException(ERR_DEPLOY_INVALID, "流程文件无效或部署失败");
        }
    }

    /** 上传文件名（null 兜底常量；部署名与资源名同源） */
    private String fileName(MultipartFile file) {
        return file.getOriginalFilename() == null ? DEFAULT_RESOURCE_NAME : file.getOriginalFilename();
    }

    /** 本次部署产生的定义列表（deploymentId 反查） */
    private List<DefinitionVo> listByDeployment(String deploymentId) {
        return repositoryService.createProcessDefinitionQuery()
                .deploymentId(deploymentId)
                .list()
                .stream().map(this::toVo).toList();
    }

    private DefinitionVo toVo(ProcessDefinition definition) {
        DefinitionVo vo = new DefinitionVo();
        vo.setId(definition.getId());
        vo.setKey(definition.getKey());
        vo.setName(definition.getName());
        vo.setVersion(String.valueOf(definition.getVersion()));
        vo.setDeploymentTime(findDeploymentTime(definition.getDeploymentId()));
        return vo;
    }

    /** 部署时间回查：经 RepositoryService 而非注入定义（deploymentId 是唯一锚点） */
    private String findDeploymentTime(String deploymentId) {
        if (deploymentId == null) {
            return null;
        }
        Deployment deployment = repositoryService.createDeploymentQuery()
                .deploymentId(deploymentId)
                .singleResult();
        return deployment == null ? null : BpmnDateUtil.format(deployment.getDeploymentTime());
    }
}
