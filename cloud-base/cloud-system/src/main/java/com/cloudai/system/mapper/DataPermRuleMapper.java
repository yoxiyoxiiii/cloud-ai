package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.entity.SysDataPermRule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 数据权限行级规则表 SQL（XML：mapper/DataPermRuleMapper.xml）。
 * 配置态数据物理删除（设计 D9）：无 deleted 列，删除=物理 DELETE；
 * uk_resource_subject 兼查重（findBySubject）/求值（resource+subject 最左前缀）/upsert 判存。
 */
public interface DataPermRuleMapper {

    /** 求值查询：角色行规则（subject_type=0）；roleIds 空集由调用方（求值器）跳过，防 IN () */
    List<SysDataPermRule> listByRoleIds(@Param("resource") String resource,
                                        @Param("roleIds") List<Long> roleIds);

    /** 求值查询：用户直绑行规则（subject_type=1） */
    List<SysDataPermRule> listByUserId(@Param("resource") String resource,
                                       @Param("userId") Long userId);

    /** upsert 判存 / 查重（uk 等值） */
    SysDataPermRule findBySubject(@Param("resource") String resource,
                                  @Param("subjectType") Integer subjectType,
                                  @Param("subjectId") Long subjectId);

    /** 注册表一致性检查（设计 D17）：全表 DISTINCT resource（物理删表无 deleted 条件；启动一次性全扫配置态小表） */
    List<String> listDistinctResources();

    /** 删除前置存在性读取（契约 §3.4：不存在 → 3031） */
    SysDataPermRule findById(@Param("id") Long id);

    /** 规则分页（动态筛选组合，无 deleted 条件——物理删表）；无 LIMIT 由分页插件追加 */
    IPage<SysDataPermRule> pageList(Page<SysDataPermRule> page,
                                    @Param("resource") String resource,
                                    @Param("subjectType") Integer subjectType,
                                    @Param("subjectId") Long subjectId);

    int save(SysDataPermRule rule);

    /** upsert 更新分支：row_scope/custom_accounts + update 审计两值 */
    int update(SysDataPermRule rule);

    /** 物理删除单条（Service 先经 findBySubject 保证存在） */
    int deleteById(@Param("id") Long id);

    /** 按主体物理删除（连带清理，无行返回 0 合法） */
    int deleteBySubject(@Param("resource") String resource,
                        @Param("subjectType") Integer subjectType,
                        @Param("subjectId") Long subjectId);
}
