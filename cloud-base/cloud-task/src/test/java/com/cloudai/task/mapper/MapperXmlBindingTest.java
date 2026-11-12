package com.cloudai.task.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper XML 绑定冒烟：XML 可解析且 16 个语句与接口一一绑定。
 * resultType 写错/命名空间错位在编译期无捕获（Maven 不校验 XML 语义），此测试封住该回归面。
 * 不连库：直接以 XMLMapperBuilder 解析 XML 构建 SqlSessionFactory（配置与生产一致：
 * MP MybatisConfiguration + mapUnderscoreToCamelCase=true，见 application.yml 显式声明）。
 */
class MapperXmlBindingTest {

    private static final String[] XML_LOCATIONS = {
            "mapper/AsyncTaskMapper.xml"
    };

    @Test
    void allXmlStatementsBound() throws IOException {
        SqlSessionFactory factory = buildFactory();

        Collection<String> mappings = factory.getConfiguration().getMappedStatementNames();
        // 16 语句：findById/pageList/findByTypeBiz/save/listQueuedIds/claimTasks/listClaimed/
        // updateProgress/updateTerminal/cancelQueued/flagCancelRunning/retryTask/requeueFailed/
        // requeueRunning/listZombieRunning/deleteTerminalBefore
        assertThat(mappings.stream().filter(n -> n.startsWith("com.cloudai.task.mapper")).count())
                .isEqualTo(16);
        // 抽查关键语句存在（认领两步 CAS / 状态推进条件写 / 清扫两路）
        assertThat(mappings).contains(
                "com.cloudai.task.mapper.AsyncTaskMapper.listQueuedIds",
                "com.cloudai.task.mapper.AsyncTaskMapper.claimTasks",
                "com.cloudai.task.mapper.AsyncTaskMapper.listClaimed",
                "com.cloudai.task.mapper.AsyncTaskMapper.updateTerminal",
                "com.cloudai.task.mapper.AsyncTaskMapper.flagCancelRunning",
                "com.cloudai.task.mapper.AsyncTaskMapper.retryTask",
                "com.cloudai.task.mapper.AsyncTaskMapper.requeueFailed",
                "com.cloudai.task.mapper.AsyncTaskMapper.requeueRunning",
                "com.cloudai.task.mapper.AsyncTaskMapper.listZombieRunning",
                "com.cloudai.task.mapper.AsyncTaskMapper.deleteTerminalBefore");
    }

    @Test
    void claimAndUpdateStatementsAreConditionalWrites() throws IOException {
        // 状态机零事务口径钉：认领与终态推进必须是单语句条件写（WHERE 带 status 条件），
        // CAS 语义依赖——被改丢条件写即并发竞争破坏，构建期红
        SqlSessionFactory factory = buildFactory();
        // 含动态标签（foreach/<if>）的语句须传参 Map 才能求值 BoundSql
        Map<String, Object> claimParams = new HashMap<>();
        claimParams.put("ids", List.of(1L));
        claimParams.put("executorId", "test-executor");
        assertSqlContains(factory, "claimTasks", claimParams, "AND status = 0");
        Map<String, Object> terminalParams = new HashMap<>();
        terminalParams.put("id", 1L);
        terminalParams.put("status", 3);
        terminalParams.put("errorMsg", "e");
        terminalParams.put("result", "r");
        assertSqlContains(factory, "updateTerminal", terminalParams, "AND status = 1");
        assertSqlContains(factory, "cancelQueued", null, "AND status = 0");
        assertSqlContains(factory, "flagCancelRunning", null, "AND status = 1");
        assertSqlContains(factory, "retryTask", null, "AND status IN (3, 4)");
        assertSqlContains(factory, "requeueFailed", null, "AND status = 1");
    }

    private void assertSqlContains(SqlSessionFactory factory, String statement, Map<String, Object> params,
                                   String fragment) {
        String sql = factory.getConfiguration()
                .getMappedStatement("com.cloudai.task.mapper.AsyncTaskMapper." + statement)
                .getBoundSql(params).getSql();
        assertThat(sql).as("语句 %s 须含条件片段 %s（单语句条件写口径）", statement, fragment).contains(fragment);
    }

    /** 与生产一致的配置构建 SqlSessionFactory（逐个解析 XML，不连库） */
    private static SqlSessionFactory buildFactory() throws IOException {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (String location : XML_LOCATIONS) {
            try (InputStream in = Resources.getResourceAsStream(location)) {
                new XMLMapperBuilder(in, configuration, location, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }
}
