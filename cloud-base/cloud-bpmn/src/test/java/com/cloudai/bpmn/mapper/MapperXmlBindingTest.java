package com.cloudai.bpmn.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper XML 绑定冒烟：2 个 XML 可解析且 7 个语句与接口一一绑定。
 * resultType 写错/命名空间错位在编译期无捕获（Maven 不校验 XML 语义），此测试封住该回归面。
 * 不连库：直接以 XMLMapperBuilder 解析 XML 构建 SqlSessionFactory（配置与生产一致：MP MybatisConfiguration
 * + mapUnderscoreToCamelCase=true，见 application.yml 显式声明）；沿 system 版同款。
 */
class MapperXmlBindingTest {

    private static final String[] XML_LOCATIONS = {
            "mapper/BpmnApprovalMapper.xml",
            "mapper/BpmnBusinessTypeMapper.xml"
    };

    @Test
    void allXmlStatementsBound() throws IOException {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (String location : XML_LOCATIONS) {
            try (InputStream in = Resources.getResourceAsStream(location)) {
                new XMLMapperBuilder(in, configuration, location, configuration.getSqlFragments()).parse();
            }
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);

        Collection<String> mappings = factory.getConfiguration().getMappedStatementNames();
        // 6(Approval：findById/findByBusiness/pageList/listByBusinessKeys/save/updateStatusById)
        //   + 1(BusinessType：findByTypeCode) = 7 —— 语句计数算式，新增语句须同步
        assertThat(mappings.stream().filter(n -> n.startsWith("com.cloudai.bpmn.mapper")).count())
                .isEqualTo(7);
        // 全量点名校（7 语句本域全量，防 id 漂移）
        assertThat(mappings).contains(
                "com.cloudai.bpmn.mapper.BpmnApprovalMapper.findById",
                "com.cloudai.bpmn.mapper.BpmnApprovalMapper.findByBusiness",
                "com.cloudai.bpmn.mapper.BpmnApprovalMapper.pageList",
                "com.cloudai.bpmn.mapper.BpmnApprovalMapper.listByBusinessKeys",
                "com.cloudai.bpmn.mapper.BpmnApprovalMapper.save",
                "com.cloudai.bpmn.mapper.BpmnApprovalMapper.updateStatusById",
                "com.cloudai.bpmn.mapper.BpmnBusinessTypeMapper.findByTypeCode");
    }
}
