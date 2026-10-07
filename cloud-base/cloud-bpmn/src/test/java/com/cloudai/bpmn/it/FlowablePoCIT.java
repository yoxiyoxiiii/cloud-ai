package com.cloudai.bpmn.it;

import org.flowable.engine.HistoryService;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.TaskService;
import org.flowable.engine.history.HistoricProcessInstance;
import org.flowable.engine.repository.ProcessDefinition;
import org.flowable.engine.runtime.ProcessInstance;
import org.flowable.engine.task.Comment;
import org.flowable.task.api.Task;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flowable 接入 PoC 门禁（阶段4 设计 D1 三条通过标准，MySQL 5.7.24 一票否决项）。
 * <p>@Tag("it")：连本机真实 MySQL cloud_bpmn 库（经 Nacos cloud-bpmn.yaml 数据源），
 * 默认构建不执行——触发命令 mvn test -pl cloud-bpmn -Dgroups=it -Dsurefire.excluded.groups= 。
 * webEnvironment 取默认 MOCK（servlet mock 环境）：security/translate 两 starter 的
 * servlet 条件装配在 NONE 下会退避，与通过标准第 1 条"自动配置并存"矛盾，故不按计划原文用 NONE（记档）。</p>
 * <ul>
 *   <li>标准 1：上下文加载成功——Flowable + MP + security + translate 两 starter 并存，唯一 DataSourceTransactionManager</li>
 *   <li>标准 2：空库 cloud_bpmn 启动后 ACT_* 自动建表且落在 cloud_bpmn（nullCatalogMeansCurrent 防跨 catalog 误检）</li>
 *   <li>标准 3：leave_approval 定义已部署 + 启动实例 → assignee 任务 → complete(approve) → 实例结束、ACT_HI 有痕、endActivityId 正确</li>
 * </ul>
 */
@Tag("it")
@SpringBootTest
class FlowablePoCIT {

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 标准 1：四家自动配置并存 + 唯一 DataSourceTransactionManager（单数据源单事务管理器，D3 同事务前提） */
    @Test
    void contextLoads_flowableAndCommonStartersCoexist() {
        // Flowable 引擎四服务就位
        assertThat(processEngine).isNotNull();
        assertThat(repositoryService).isNotNull();
        assertThat(runtimeService).isNotNull();
        assertThat(taskService).isNotNull();
        assertThat(historyService).isNotNull();
        // security-starter（HeaderAuthFilter）/ translate-starter（TranslateAdvisor）/ translate-remote-starter（SystemTranslateClient）
        assertThat(applicationContext.getBeansOfType(org.springframework.security.web.SecurityFilterChain.class))
                .isNotEmpty();
        assertThat(applicationContext.getBeansOfType(
                        com.cloudai.common.translate.core.TranslateAdvisor.class))
                .isNotEmpty();
        assertThat(applicationContext.getBeansOfType(
                        com.cloudai.common.translate.remote.client.SystemTranslateClient.class))
                .isNotEmpty();
        // MP 分页插件在场（MybatisPlusInterceptor 由 common-mybatis-starter 注册）
        assertThat(applicationContext.getBeansOfType(com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor.class))
                .isNotEmpty();
        // 唯一事务管理器且为 DataSourceTransactionManager（业务写与引擎写可同一本地事务）
        Map<String, PlatformTransactionManager> managers =
                applicationContext.getBeansOfType(PlatformTransactionManager.class);
        assertThat(managers).hasSize(1);
        assertThat(managers.values().iterator().next())
                .isInstanceOf(org.springframework.jdbc.datasource.DataSourceTransactionManager.class);
        assertThat(transactionTemplate).isNotNull();
    }

    /** 标准 2：ACT_* 自动建表落在 cloud_bpmn 库（连接 catalog + information_schema 双断言） */
    @Test
    void actTablesAutoCreated_inCloudBpmnCatalog() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("cloud_bpmn");
            try (PreparedStatement ps = connection.prepareStatement(
                    "SELECT COUNT(*) FROM information_schema.tables "
                            + "WHERE table_schema = 'cloud_bpmn' AND table_name LIKE 'ACT_%'")) {
                ResultSet rs = ps.executeQuery();
                assertThat(rs.next()).isTrue();
                long actTables = rs.getLong(1);
                // Process 引擎全家（RE/RU/HI/GE/ID/ACT_HI 周边）实测数十张；阈值宽松只做存在性门禁
                assertThat(actTables).isGreaterThanOrEqualTo(20L);
            }
        }
    }

    /** 标准 3：leave_approval 全链路——部署 → 启动 → assignee 任务 → 办理 → 终态/endActivityId/意见留痕 */
    @Test
    void leaveApproval_lifecycleEndToEnd() {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("leave_approval")
                .latestVersion()
                .singleResult();
        assertThat(definition).as("classpath processes/ 自动部署的 leave_approval 定义").isNotNull();

        Map<String, Object> vars = new HashMap<>();
        vars.put("leaveId", 999901L);
        vars.put("applyUser", "pocApplyUser");
        vars.put("approver", "pocApprover");
        vars.put("title", "poc leave");
        ProcessInstance instance = runtimeService.startProcessInstanceByKey(
                "leave_approval", "999901", vars);
        assertThat(instance).isNotNull();
        assertThat(instance.getBusinessKey()).isEqualTo("999901");

        Task task = taskService.createTaskQuery()
                .processInstanceId(instance.getId())
                .taskAssignee("pocApprover")
                .singleResult();
        assertThat(task).as("assignee=${approver} 任务已生成").isNotNull();

        taskService.addComment(task.getId(), instance.getId(), "poc approve comment");
        taskService.complete(task.getId(), Map.of("approve", Boolean.TRUE));

        assertThat(runtimeService.createProcessInstanceQuery()
                .processInstanceId(instance.getId()).singleResult()).as("实例已结束").isNull();

        HistoricProcessInstance historic = historyService.createHistoricProcessInstanceQuery()
                .processInstanceId(instance.getId())
                .finished()
                .singleResult();
        assertThat(historic).as("ACT_HI_PROCINST 有痕").isNotNull();
        assertThat(historic.getEndActivityId()).isEqualTo("endApprove");
        assertThat(historic.getBusinessKey()).isEqualTo("999901");

        assertThat(taskService.getProcessInstanceComments(instance.getId()))
                .extracting(Comment::getFullMessage)
                .contains("poc approve comment");
    }
}
