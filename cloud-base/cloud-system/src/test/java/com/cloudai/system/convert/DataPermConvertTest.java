package com.cloudai.system.convert;

import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.api.dataperm.RowGroup;
import com.cloudai.system.dto.PredicateItem;
import com.cloudai.system.entity.SysDataPermRule;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.vo.FilterColumnVo;
import com.cloudai.system.vo.MyScopeVo;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 数据权限域转换单测（谓词轮 2026-10-11）：scopeLabel 判定序 v2——无条件五态文案逐字不变
 * （手工构造 Decision 不带 rowFilter，兼证 Convert 的 dataScope 回退判据）+ 条件态第 3 位
 * （含条件规则 → 条件范围（摘要）；ALL+条件 dataScope 空但 rowFilter 非空 → 不落空集态）；
 * predicates JSON 编解码（坏数据 null 信号 vs customAccounts 空列表降级分流记档）；条件中文摘要。
 */
class DataPermConvertTest {

    private static final String RESOURCE = "leave";

    // ---- scopeLabel v2：无条件五态逐字不变（零 diff 红线） ----

    @Test
    void label_unconditionalAllTier_stillAll() {
        assertThat(labelOf(decisionOf(allScope(), null))).isEqualTo("全部");
    }

    @Test
    void label_emptyScope_stillEmpty() {
        DataPermDecision decision = decisionOf(com.cloudai.system.api.dataperm.DataScope.of(Set.of()), null);
        assertThat(labelOf(decision)).isEqualTo("无（空范围）");
    }

    @Test
    void label_deptTier_stillDesignatedRange() {
        DataPermDecision decision = decisionOf(
                com.cloudai.system.api.dataperm.DataScope.of(Set.of("userA", "userB")), null);
        decision.getHitRules().add(hit(SysDataPermRule.RowScopeEnum.DEPT.getCode()));
        assertThat(labelOf(decision)).isEqualTo("指定范围（2 人）");
    }

    @Test
    void label_customWithSelf_stillCustomRange() {
        DataPermDecision decision = decisionOf(
                com.cloudai.system.api.dataperm.DataScope.of(Set.of("userA", "userB")), null);
        decision.getHitRules().add(hit(SysDataPermRule.RowScopeEnum.CUSTOM.getCode()));
        assertThat(labelOf(decision)).isEqualTo("自定义范围（含自己，共 2 人）");
    }

    @Test
    void label_selfFallback_stillSelfOnly() {
        DataPermDecision decision = decisionOf(
                com.cloudai.system.api.dataperm.DataScope.of(Set.of("userA")), null);
        decision.getHitRules().add(hit(SysDataPermRule.RowScopeEnum.SELF.getCode()));
        assertThat(labelOf(decision)).isEqualTo("仅自己");
    }

    // ---- scopeLabel v2：条件态第 3 位（语义变更点） ----

    @Test
    void label_conditionalHit_conditionRangeState() {
        DataPermDecision decision = decisionOf(
                com.cloudai.system.api.dataperm.DataScope.of(Set.of("userA")),
                RowFilter.of(List.of(RowGroup.of(false, Set.of("userA"), List.of()))));
        DataPermDecision.HitRule hit = hit(SysDataPermRule.RowScopeEnum.SELF.getCode());
        hit.getPredicates().add(predicate("leaveType", "EQ", "1"));
        decision.getHitRules().add(hit);

        assertThat(labelOf(decision)).isEqualTo("条件范围（请假类型等于1）");
    }

    @Test
    void label_allPlusConditional_dataScopeEmptyButRowFilterAlive() {
        // D31 判据优先级：rowFilter 非空 → 不落「无（空范围）」；ALL+条件不豁免 → 不落「全部」
        DataPermDecision decision = decisionOf(
                com.cloudai.system.api.dataperm.DataScope.of(Set.of()),
                RowFilter.of(List.of(RowGroup.of(true, Set.of(), List.of()))));
        DataPermDecision.HitRule hit = hit(SysDataPermRule.RowScopeEnum.ALL.getCode());
        hit.getPredicates().add(predicate("id", "BETWEEN", "1", "100"));
        decision.getHitRules().add(hit);

        assertThat(labelOf(decision)).isEqualTo("条件范围（单号介于1~100）");
    }

    @Test
    void label_multiConditionalRules_summariesSemicolonJoined() {
        DataPermDecision decision = decisionOf(
                com.cloudai.system.api.dataperm.DataScope.of(Set.of("userA")),
                RowFilter.of(List.of(RowGroup.of(false, Set.of("userA"), List.of()))));
        DataPermDecision.HitRule first = hit(SysDataPermRule.RowScopeEnum.SELF.getCode());
        first.getPredicates().add(predicate("id", "BETWEEN", "1", "100"));
        decision.getHitRules().add(first);
        DataPermDecision.HitRule second = hit(SysDataPermRule.RowScopeEnum.DEPT.getCode());
        second.getPredicates().add(predicate("leaveType", "EQ", "1"));
        decision.getHitRules().add(second);

        // §6：全部命中条件规则的谓词摘要分号连接（跨规则拍平）
        assertThat(labelOf(decision)).isEqualTo("条件范围（单号介于1~100;请假类型等于1）");
    }

    // ---- predicates JSON 编解码（parsePredicates null 信号 vs customAccounts 空列表降级分流） ----

    @Test
    void parsePredicates_nullOrBlank_emptyList() {
        assertThat(DataPermConvert.parsePredicates(null)).isEmpty();
        assertThat(DataPermConvert.parsePredicates("  ")).isEmpty();
    }

    @Test
    void parsePredicates_validJson_itemsWithRawValues() {
        List<PredicateItem> items = DataPermConvert.parsePredicates(
                "[{\"field\":\"leaveType\",\"operator\":\"IN\",\"values\":[\"1\",\"2\"]}]");

        assertThat(items).hasSize(1);
        assertThat(items.get(0).getField()).isEqualTo("leaveType");
        assertThat(items.get(0).getOperator()).isEqualTo("IN");
        assertThat(items.get(0).getValues()).containsExactly("1", "2");
    }

    @Test
    void parsePredicates_brokenJson_nullSignal() {
        // 求值侧以 null 为「整规则不授权」信号（D39 fail-closed）——空列表降级即 fail-open，刻意分流
        assertThat(DataPermConvert.parsePredicates("not-a-json")).isNull();
    }

    @Test
    void toPredicatesJson_roundTrip() {
        String json = DataPermConvert.toPredicatesJson(List.of(predicate("leaveType", "EQ", "1")));

        assertThat(json).isEqualTo("[{\"field\":\"leaveType\",\"operator\":\"EQ\",\"values\":[\"1\"]}]");
        assertThat(DataPermConvert.parsePredicates(json)).hasSize(1);
    }

    // ---- 条件中文摘要（契约 §1.6：config/rule/my-scope 共用） ----

    @Test
    void toPredicateSummary_multiPredicateSemicolonJoined() {
        String summary = DataPermConvert.toPredicateSummary(RESOURCE, List.of(
                predicate("id", "BETWEEN", "1", "100"),
                predicate("leaveType", "EQ", "1")));

        assertThat(summary).isEqualTo("单号介于1~100;请假类型等于1");
    }

    @Test
    void toPredicateSummary_inValuesJoinedByDun() {
        String summary = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("leaveType", "IN", "1", "2", "3")));

        assertThat(summary).isEqualTo("请假类型属于1、2、3");
    }

    @Test
    void toPredicateSummary_dateGte() {
        String summary = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("startDate", "GTE", "2026-01-01")));

        assertThat(summary).isEqualTo("开始日期大于等于2026-01-01");
    }

    @Test
    void toPredicateSummary_emptyOrNull_null() {
        assertThat(DataPermConvert.toPredicateSummary(RESOURCE, null)).isNull();
        assertThat(DataPermConvert.toPredicateSummary(RESOURCE, List.of())).isNull();
    }

    @Test
    void toPredicateSummary_outOfBoundField_skipped() {
        String summary = DataPermConvert.toPredicateSummary(RESOURCE, List.of(
                predicate("ghost", "EQ", "1"),
                predicate("leaveType", "EQ", "1")));

        assertThat(summary).isEqualTo("请假类型等于1");
    }

    // ---- 可过滤列 VO 转换（资源注册表端点出参） ----

    @Test
    void toFilterColumnVo_mapsDeclaration() {
        FilterColumnVo vo = DataPermConvert.toFilterColumnVo(
                new com.cloudai.system.api.dataperm.FilterColumn(
                        "leaveType", "leave_type",
                        com.cloudai.system.api.dataperm.FilterColumnTypeEnum.NUMBER, "请假类型"));

        assertThat(vo.getField()).isEqualTo("leaveType");
        assertThat(vo.getColumn()).isEqualTo("leave_type");
        assertThat(vo.getType()).isEqualTo("NUMBER");
        assertThat(vo.getLabel()).isEqualTo("请假类型");
    }

    // ---- 脚手架 ----

    private String labelOf(DataPermDecision decision) {
        MyScopeVo vo = DataPermConvert.toMyScopeVo(decision);
        return vo.getScopeLabel();
    }

    private com.cloudai.system.api.dataperm.DataScope allScope() {
        return com.cloudai.system.api.dataperm.DataScope.all();
    }

    private DataPermDecision decisionOf(com.cloudai.system.api.dataperm.DataScope dataScope,
            RowFilter rowFilter) {
        DataPermDecision decision = new DataPermDecision();
        decision.setResource(RESOURCE);
        decision.setAccount("userA");
        decision.setDataScope(dataScope);
        decision.setRowFilter(rowFilter);
        decision.setColumnScope(com.cloudai.system.api.dataperm.ColumnScope.of(Set.of(), Set.of()));
        return decision;
    }

    private DataPermDecision.HitRule hit(int rowScope) {
        DataPermDecision.HitRule hit = new DataPermDecision.HitRule();
        hit.setRuleId(9L);
        hit.setSubjectType(0);
        hit.setSubjectName("roleX");
        hit.setRowScope(rowScope);
        return hit;
    }

    private PredicateItem predicate(String field, String operator, String... values) {
        PredicateItem item = new PredicateItem();
        item.setField(field);
        item.setOperator(operator);
        item.setValues(List.of(values));
        return item;
    }
}
