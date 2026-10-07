package com.cloudai.bpmn.service;

import com.cloudai.bpmn.util.BpmnDateUtil;
import com.cloudai.bpmn.vo.DefinitionVo;
import com.cloudai.common.core.domain.PageQuery;
import com.cloudai.common.core.domain.PageResult;
import lombok.RequiredArgsConstructor;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 流程定义只读查询（契约 §4.1）：latestVersion 过滤 + key 升序，手写偏移分页
 * （引擎 Query 的 listPage(firstResult, maxResults)；只读服务不加事务注解）。
 * 部署时间不在 ProcessDefinition 投影上——经 deploymentId 回查 Deployment（定义量级个位数，N+1 可容忍）。
 */
@Service
@RequiredArgsConstructor
public class DefinitionAppService {

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

    private int offset(PageQuery query) {
        return (query.getPageNum() - 1) * query.getPageSize();
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
