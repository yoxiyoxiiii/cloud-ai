package com.cloudai.system.mapper;

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
 * Mapper XML 绑定冒烟：8 个 XML 全部可解析且 52 个语句与接口一一绑定。
 * resultType 写错/命名空间错位在编译期无捕获（Maven 不校验 XML 语义），此测试封住该回归面。
 * 不连库：本模块无嵌入式数据库驱动（仅 mysql-connector-j），@MybatisTest 无法启动嵌入式数据源，
 * 故直接以 XMLMapperBuilder 逐个解析 XML 构建 SqlSessionFactory（配置与生产一致：MP MybatisConfiguration
 * + mapUnderscoreToCamelCase=true，见 application.yml 显式声明）。
 */
class MapperXmlBindingTest {

    private static final String[] XML_LOCATIONS = {
            "mapper/SysUserMapper.xml",
            "mapper/SysRoleMapper.xml",
            "mapper/SysMenuMapper.xml",
            "mapper/SysUserRoleMapper.xml",
            "mapper/SysRoleMenuMapper.xml",
            "mapper/SysDictTypeMapper.xml",
            "mapper/SysDictDataMapper.xml",
            "mapper/SysLeaveMapper.xml"
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
        // 11(User) + 7(Role) + 7(Menu) + 4(UserRole) + 4(RoleMenu) + 6(DictType) + 8(DictData) + 6(Leave) = 53
        // （2026-10-09 RocketMQ 事务消息化：SysLeave 域 5→6 语句——updateApprovalId 退役，
        //   +updateApprovalIdIfAbsent/updateStatusIfApproving 条件 UPDATE 双保险，覆盖扩张同先例）
        assertThat(mappings.stream().filter(n -> n.startsWith("com.cloudai.system.mapper")).count())
                .isEqualTo(53);
        // 抽查关键语句存在（JOIN 聚合 / 插件分页 / 动态 SQL / 批量插入 / 字典域 / 翻译回源 / 请假域）
        assertThat(mappings).contains(
                "com.cloudai.system.mapper.SysUserMapper.listPermsByAccount",
                "com.cloudai.system.mapper.SysUserMapper.pageList",
                "com.cloudai.system.mapper.SysUserMapper.listEnabledOptions",
                "com.cloudai.system.mapper.SysMenuMapper.listNavByAccount",
                "com.cloudai.system.mapper.SysRoleMapper.countByRoleKey",
                "com.cloudai.system.mapper.SysUserRoleMapper.saveBatch",
                "com.cloudai.system.mapper.SysDictTypeMapper.countByDictKey",
                "com.cloudai.system.mapper.SysDictDataMapper.pageListByTypeId",
                "com.cloudai.system.mapper.SysDictDataMapper.countByTypeValue",
                "com.cloudai.system.mapper.SysUserMapper.listTransAll",
                "com.cloudai.system.mapper.SysDictDataMapper.listEnabledByDictKey",
                "com.cloudai.system.mapper.SysLeaveMapper.pageList",
                "com.cloudai.system.mapper.SysLeaveMapper.updateStatusById",
                "com.cloudai.system.mapper.SysLeaveMapper.updateApprovalIdIfAbsent",
                "com.cloudai.system.mapper.SysLeaveMapper.updateStatusIfApproving");
    }
}
