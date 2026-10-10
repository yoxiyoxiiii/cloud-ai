package com.cloudai.system.service.dataperm;

import com.cloudai.system.entity.SysDataPermColumn;
import com.cloudai.system.mapper.DataPermColumnMapper;
import com.cloudai.system.mapper.DataPermRuleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 注册表一致性检查器单测（设计 D17 后半）：mock 两 mapper 直调包可见 collectViolations，
 * 断言越界清单内容（不依赖日志捕获）。五态：双空 / 全合规 / rule 越界 / column 越界 /
 * 混合并存（rule 路先 column 路后，顺序稳定）。
 * 注册表真源不 mock——静态 DataPermResources（leave 可配列 title/reason）。
 */
@ExtendWith(MockitoExtension.class)
class DataPermRegistryConsistencyCheckerTest {

    @Mock
    private DataPermRuleMapper ruleMapper;
    @Mock
    private DataPermColumnMapper columnMapper;
    @InjectMocks
    private DataPermRegistryConsistencyChecker checker;

    @Test
    void bothTablesEmpty_noViolations() {
        when(ruleMapper.listDistinctResources()).thenReturn(List.of());
        when(columnMapper.listDistinctResourceColumns()).thenReturn(List.of());

        assertThat(checker.collectViolations()).isEmpty();
    }

    @Test
    void allWithinRegistry_noViolations() {
        when(ruleMapper.listDistinctResources()).thenReturn(List.of("leave"));
        when(columnMapper.listDistinctResourceColumns())
                .thenReturn(List.of(column("leave", "title")));

        assertThat(checker.collectViolations()).isEmpty();
    }

    @Test
    void unregisteredRuleResource_reported() {
        when(ruleMapper.listDistinctResources()).thenReturn(List.of("leave", "ghost"));
        when(columnMapper.listDistinctResourceColumns()).thenReturn(List.of());

        List<String> violations = checker.collectViolations();

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("ghost").contains("未注册");
    }

    @Test
    void columnOutsideConfigurableList_reported() {
        when(ruleMapper.listDistinctResources()).thenReturn(List.of());
        when(columnMapper.listDistinctResourceColumns())
                .thenReturn(List.of(column("leave", "status")));

        List<String> violations = checker.collectViolations();

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0)).contains("status").contains("不在可配清单");
    }

    @Test
    void mixedViolations_bothReportedInStableOrder() {
        when(ruleMapper.listDistinctResources()).thenReturn(List.of("ghost"));
        when(columnMapper.listDistinctResourceColumns())
                .thenReturn(List.of(column("leave", "ghost_col")));

        List<String> violations = checker.collectViolations();

        // rule 路先 column 路后（设计 D17：清单顺序稳定）
        assertThat(violations).hasSize(2);
        assertThat(violations.get(0)).contains("ghost").contains("未注册");
        assertThat(violations.get(1)).contains("ghost_col").contains("不在可配清单");
    }

    private SysDataPermColumn column(String resource, String columnKey) {
        SysDataPermColumn col = new SysDataPermColumn();
        col.setResource(resource);
        col.setColumnKey(columnKey);
        return col;
    }
}
