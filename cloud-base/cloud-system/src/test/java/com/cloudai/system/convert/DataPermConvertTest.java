package com.cloudai.system.convert;

import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.api.dataperm.RowGroup;
import com.cloudai.system.dto.PredicateItem;
import com.cloudai.system.entity.SysDataPermRule;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermResources;
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
                        com.cloudai.system.api.dataperm.FilterColumnTypeEnum.NUMBER, "请假类型", null));

        assertThat(vo.getField()).isEqualTo("leaveType");
        assertThat(vo.getColumn()).isEqualTo("leave_type");
        assertThat(vo.getType()).isEqualTo("NUMBER");
        assertThat(vo.getLabel()).isEqualTo("请假类型");
    }

    // ---- 操作符扩展轮（D41/D45，2026-10-11）：新操作符摘要 + 字典译文三参路径 ----

    @Test
    void toFilterColumnVo_dictKeyMapped() {
        FilterColumnVo vo = DataPermConvert.toFilterColumnVo(
                DataPermResources.findFilterColumn(RESOURCE, "leaveType"));

        assertThat(vo.getDictKey()).isEqualTo("system_leave_type");
    }

    @Test
    void toPredicateSummary_newOperators_text() {
        // 新四操作符摘要文本（GT/LT/NE/PREFIX——label 走枚举中文，值原样）
        assertThat(DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("id", "GT", "100")))).isEqualTo("单号大于100");
        assertThat(DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("endDate", "LT", "2026-12-31")))).isEqualTo("结束日期小于2026-12-31");
        assertThat(DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("leaveType", "NE", "2")))).isEqualTo("请假类型不等于2");
        assertThat(DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("title", "PREFIX", "e2e")))).isEqualTo("标题前缀e2e");
    }

    @Test
    void toPredicateSummary_threeParam_dictValuesTranslated() {
        // D45 三参路径：字典列（leaveType/system_leave_type）值译标签——IN 多值逐值翻译
        java.util.function.BiFunction<String, List<String>, java.util.Map<String, String>> resolver =
                (dictKey, values) -> java.util.Map.of("1", "事假", "2", "病假", "3", "年假");

        String summary = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("leaveType", "IN", "1", "2", "3")), resolver);

        assertThat(summary).isEqualTo("请假类型属于事假、病假、年假");
    }

    @Test
    void toPredicateSummary_threeParam_singleValueTranslated() {
        java.util.function.BiFunction<String, List<String>, java.util.Map<String, String>> resolver =
                (dictKey, values) -> java.util.Map.of("1", "事假");

        String summary = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("leaveType", "EQ", "1")), resolver);

        assertThat(summary).isEqualTo("请假类型等于事假");
    }

    @Test
    void toPredicateSummary_threeParam_missingLabelFallsBackOriginal() {
        // 译文缺失（值不在字典/项停用）回退原值（getOrDefault 口径）
        java.util.function.BiFunction<String, List<String>, java.util.Map<String, String>> resolver =
                (dictKey, values) -> java.util.Map.of("1", "事假");

        String summary = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("leaveType", "IN", "1", "9")), resolver);

        assertThat(summary).isEqualTo("请假类型属于事假、9");
    }

    @Test
    void toPredicateSummary_threeParam_nonDictColumnOriginalValues() {
        // 非字典列（dictKey=null，如 title）resolver 在场也原值——businessType 断言守恒的单测面对应
        java.util.function.BiFunction<String, List<String>, java.util.Map<String, String>> resolver =
                (dictKey, values) -> java.util.Map.of("e2e", "不应出现");

        String summary = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("title", "PREFIX", "e2e")), resolver);

        assertThat(summary).isEqualTo("标题前缀e2e");
    }

    @Test
    void toPredicateSummary_threeParam_resolverThrows_fallsBackOriginal() {
        // 显示层 fail-open（D45）：resolver 异常回退原值列表，摘要不炸
        java.util.function.BiFunction<String, List<String>, java.util.Map<String, String>> resolver =
                (dictKey, values) -> {
                    throw new IllegalStateException("redis down");
                };

        String summary = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("leaveType", "EQ", "1")), resolver);

        assertThat(summary).isEqualTo("请假类型等于1");
    }

    @Test
    void toPredicateSummary_twoParam_originalValues_contrast() {
        // 两参 overload 委托 null resolver——原值形态（既有语义零变化的对照证明）
        String twoParam = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("leaveType", "EQ", "1")));
        String threeParamNull = DataPermConvert.toPredicateSummary(RESOURCE,
                List.of(predicate("leaveType", "EQ", "1")), null);

        assertThat(twoParam).isEqualTo("请假类型等于1");
        assertThat(threeParamNull).isEqualTo("请假类型等于1");
    }

    @Test
    void toMyScopeVo_resolver_sixthStateTranslated() {
        // 第六态经三参路径带译文（D45）：条件范围（请假类型等于事假）
        DataPermDecision decision = decisionOf(
                com.cloudai.system.api.dataperm.DataScope.of(Set.of("userA")),
                RowFilter.of(List.of(RowGroup.of(false, Set.of("userA"), List.of()))));
        DataPermDecision.HitRule hit = hit(SysDataPermRule.RowScopeEnum.SELF.getCode());
        hit.getPredicates().add(predicate("leaveType", "EQ", "1"));
        decision.getHitRules().add(hit);
        java.util.function.BiFunction<String, List<String>, java.util.Map<String, String>> resolver =
                (dictKey, values) -> java.util.Map.of("1", "事假");

        MyScopeVo vo = DataPermConvert.toMyScopeVo(decision, resolver);

        assertThat(vo.getScopeLabel()).isEqualTo("条件范围（请假类型等于事假）");
    }

    @Test
    void toRuleVo_resolver_summaryTranslated() {
        SysDataPermRule rule = new SysDataPermRule();
        rule.setId(9L);
        rule.setResource(RESOURCE);
        rule.setSubjectType(0);
        rule.setSubjectId(1L);
        rule.setRowScope(SysDataPermRule.RowScopeEnum.ALL.getCode());
        rule.setPredicates(DataPermConvert.toPredicatesJson(
                List.of(predicate("leaveType", "EQ", "1"))));
        java.util.function.BiFunction<String, List<String>, java.util.Map<String, String>> resolver =
                (dictKey, values) -> java.util.Map.of("1", "事假");

        com.cloudai.system.vo.DataPermRuleVo vo = DataPermConvert.toRuleVo(
                rule, "roleX", List.of(), List.of(), resolver);

        assertThat(vo.getPredicateSummary()).isEqualTo("请假类型等于事假");
        // wire 回显不经翻译（原值，D45 不翻译面）
        assertThat(vo.getPredicates()).hasSize(1);
        assertThat(vo.getPredicates().get(0).getValues()).containsExactly("1");
    }

    // ---- 列级数据权限轮增量（2026-10-22）：columns 结构化清单 + columnOnly scopeLabel ----

    @Test
    void toMyScopeVo_columnsStructured_hiddenFirstThenMaskedEachSorted() {
        DataPermDecision decision = decisionOf(allScope(), null);
        decision.setColumnScope(com.cloudai.system.api.dataperm.ColumnScope.of(
                Set.of("email"), Set.of("mobile")));

        MyScopeVo vo = DataPermConvert.toMyScopeVo(decision);

        // 与 explain 列决策同序同源：hidden 在前 masked 在后、各自字典序；action 数值 0/1
        assertThat(vo.getColumns())
                .extracting(com.cloudai.system.vo.DataPermExplainVo.ColumnDecisionVo::getColumnKey,
                        com.cloudai.system.vo.DataPermExplainVo.ColumnDecisionVo::getAction)
                .containsExactly(
                        org.assertj.core.api.Assertions.tuple("email", 0),
                        org.assertj.core.api.Assertions.tuple("mobile", 1));
    }

    @Test
    void toMyScopeVo_noColumnActions_emptyArray() {
        DataPermDecision decision = decisionOf(allScope(), null);

        MyScopeVo vo = DataPermConvert.toMyScopeVo(decision);

        // 无列动作必返 [] 而非 null（机器消费 length===0 即全显示，免 null 判断）
        assertThat(vo.getColumns()).isNotNull().isEmpty();
    }

    @Test
    void toMyScopeVo_columnOnlyResource_labelNotApplicable() {
        DataPermDecision decision = decisionOf(allScope(), null);
        decision.setResource(DataPermResources.USER);

        MyScopeVo vo = DataPermConvert.toMyScopeVo(decision);

        // 新首位分支：columnOnly 资源恒「不适用」，不落既有五态判定序
        assertThat(vo.getScopeLabel()).isEqualTo("不适用（该资源仅列级权限）");
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
