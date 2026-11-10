package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper XML 绑定冒烟：19 个 XML 全部可解析且 127 个语句与接口一一绑定。
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
            "mapper/SysLeaveMapper.xml",
            "mapper/SysDeptMapper.xml",
            "mapper/DataPermRuleMapper.xml",
            "mapper/DataPermColumnMapper.xml",
            "mapper/DataPermLogMapper.xml",
            "mapper/MqDlqLogMapper.xml",
            "mapper/SysTableConfigMapper.xml",
            "mapper/SysNotifyTemplateMapper.xml",
            "mapper/SysNotifyMessageMapper.xml",
            "mapper/SysNotifyUserMessageMapper.xml",
            "mapper/SysNotifyUserStatMapper.xml",
            "mapper/SysSharedSecretMapper.xml"
    };

    @Test
    void allXmlStatementsBound() throws IOException {
        SqlSessionFactory factory = buildFactory();

        Collection<String> mappings = factory.getConfiguration().getMappedStatementNames();
        // 16(User) + 9(Role) + 7(Menu) + 4(UserRole) + 4(RoleMenu) + 6(DictType) + 8(DictData) + 3(Leave)
        //   + 7(Dept) + 11(DataPermRule) + 7(DataPermColumn) + 2(DataPermLog) + 7(MqDlqLog) + 4(SysTableConfig)
        //   + 6(SysNotifyTemplate) + 2(SysNotifyMessage) + 8(SysNotifyUserMessage) = 112
        // （2026-10-09 投影轮：SysLeave 域 6→3 语句；2026-10-10 数据权限轮：+Dept 域、+数据权限三表、
        //   User +3（countByDeptId/listEnabledAccountsByDeptIds/listByIds）、Role +2（listEnabledRoleIdsByUserId/listByIds）；
        //   2026-10-10 注册表重构轮：DataPermRule +1 / DataPermColumn +1（D17 一致性检查 DISTINCT 两查询）；
        //   2026-10-11 谓词轮：DataPermRule +1（listAll——D39 一致性检查第三维度 predicates 逐条解析）；
        //   2026-10-11 死信治理轮：+MqDlqLog 域 7 语句（watch 落库/分页/单查/重投流转/状态流转/重置/过期清理）；
        //   2026-10-18 知识库权限轮：User +1（listRoleIdsByAccount——登录 roleIds 聚合），89 → 90；
        //   2026-10-23 表格配置轮：+SysTableConfig 域 4 语句（findConfig/saveConfig/updateConfig/deleteConfig），90 → 94；
        //   2026-11-08 通知中心轮：+notify 三表 16 语句（模板 6/消息主表 2/收件 8）、
        //   User +2（listActiveUserIds/listUserIdsByRoleIds——通知收件人两档展开），94 → 112；
        //   2026-11-08 统计表轮：+SysNotifyUserStat 4 语句（单行查询/批量递增/守卫递减/对账重建），112 → 116；
        //   2026-11-08 统计表修订轮：统计维护编排上 Java 层、SQL 只留简单单表单键语句——
        //   SysNotifyUserStat 换血（-updateIncreaseBatch/-resetFromReceipts，+save/saveBatch/updateIncrease/deleteAll，4→6）
        //   + 收件表 +listUnreadAgg（对账聚合只读），116 → 119；
        //   2026-11-08 审批通知轮：+SysNotifyTemplate findByCode（MQ 消费域按 code 定位模板），119 → 120；
        //   2026-11-09 密钥轮换轮：+SysSharedSecret 域 7 语句（单查/最新生效行/最大代数/分页/按状态列表/
        //   插入/状态推进——追加式流水无 deleted 列），120 → 127）
        assertThat(mappings.stream().filter(n -> n.startsWith("com.cloudai.system.mapper")).count())
                .isEqualTo(127);
        // 抽查关键语句存在（JOIN 聚合 / 插件分页 / 动态 SQL / 批量插入 / 字典域 / 翻译回源 / 请假域 / 部门与数据权限域）
        assertThat(mappings).contains(
                "com.cloudai.system.mapper.SysUserMapper.listPermsByAccount",
                "com.cloudai.system.mapper.SysUserMapper.listRoleIdsByAccount",
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
                "com.cloudai.system.mapper.SysLeaveMapper.findById",
                "com.cloudai.system.mapper.SysLeaveMapper.save",
                "com.cloudai.system.mapper.SysDeptMapper.listAll",
                "com.cloudai.system.mapper.SysDeptMapper.deleteById",
                "com.cloudai.system.mapper.DataPermRuleMapper.findBySubject",
                "com.cloudai.system.mapper.DataPermRuleMapper.deleteBySubject",
                "com.cloudai.system.mapper.DataPermColumnMapper.saveBatch",
                "com.cloudai.system.mapper.DataPermLogMapper.pageList",
                "com.cloudai.system.mapper.SysUserMapper.listEnabledAccountsByDeptIds",
                "com.cloudai.system.mapper.SysRoleMapper.listEnabledRoleIdsByUserId",
                "com.cloudai.system.mapper.DataPermRuleMapper.listDistinctResources",
                "com.cloudai.system.mapper.DataPermColumnMapper.listDistinctResourceColumns",
                "com.cloudai.system.mapper.MqDlqLogMapper.pageListDlqLog",
                "com.cloudai.system.mapper.MqDlqLogMapper.deleteExpiredDlqLog",
                "com.cloudai.system.mapper.SysTableConfigMapper.findConfig",
                "com.cloudai.system.mapper.SysTableConfigMapper.deleteConfig",
                "com.cloudai.system.mapper.SysNotifyTemplateMapper.pageList",
                "com.cloudai.system.mapper.SysNotifyTemplateMapper.findByCode",
                "com.cloudai.system.mapper.SysNotifyMessageMapper.deleteOrphanMessages",
                "com.cloudai.system.mapper.SysNotifyUserMessageMapper.countUnread",
                "com.cloudai.system.mapper.SysNotifyUserMessageMapper.pageList",
                "com.cloudai.system.mapper.SysNotifyUserMessageMapper.deleteReadBefore",
                "com.cloudai.system.mapper.SysNotifyUserMessageMapper.listUnreadAgg",
                "com.cloudai.system.mapper.SysNotifyUserStatMapper.updateIncrease",
                "com.cloudai.system.mapper.SysUserMapper.listActiveUserIds",
                "com.cloudai.system.mapper.SysUserMapper.listUserIdsByRoleIds",
                "com.cloudai.system.mapper.SysSharedSecretMapper.findLatestActive",
                "com.cloudai.system.mapper.SysSharedSecretMapper.findMaxSeq",
                "com.cloudai.system.mapper.SysSharedSecretMapper.updateStatus");
    }

    @Test
    void userPageListSelectsBuiltinWithVoAlias() throws IOException {
        // 2026-10-10 修复回归钉（e2e S12b/S14b 红，builtin=null）：pageList resultType=SysUserVo，
        // 裸列 u.is_builtin 的自动映射目标是 isBuiltin（实体字段名）——SysUserVo 无此属性
        // （VO 字段名 builtin，Boolean），映射断裂；AS builtin 别名经 BooleanTypeHandler 落
        // Boolean（builtin-protection 契约 §7.1 必返 boolean）
        SqlSessionFactory factory = buildFactory();
        MappedStatement statement = factory.getConfiguration()
                .getMappedStatement("com.cloudai.system.mapper.SysUserMapper.pageList");
        String sql = statement.getBoundSql(null).getSql();
        assertThat(sql).contains("u.is_builtin AS builtin");
    }

    @Test
    void listRoleIdsFiltersEnabledRoles() throws IOException {
        // 登录 roleIds 聚合的启用口径钉（与 listPermsByAccount 同源）：停用/已删角色不进身份快照，
        // 停用角色的知识库授权在消费侧自然失配（自愈语义）
        SqlSessionFactory factory = buildFactory();
        MappedStatement statement = factory.getConfiguration()
                .getMappedStatement("com.cloudai.system.mapper.SysUserMapper.listRoleIdsByAccount");
        String sql = statement.getBoundSql(null).getSql();
        assertThat(sql).contains("r.status = 0 AND r.deleted = 0");
    }

    /** 与生产一致的配置构建 SqlSessionFactory（13 个 XML 逐个解析，不连库） */
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
