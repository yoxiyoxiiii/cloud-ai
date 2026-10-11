package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.dataperm.FilterColumn;
import com.cloudai.system.api.dataperm.FilterColumnTypeEnum;
import com.cloudai.system.api.dataperm.RowGroup;
import com.cloudai.system.api.dataperm.RowPredicate;
import com.cloudai.system.api.domain.DataPermDenyRequest;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import com.cloudai.system.api.domain.PredicateVo;
import com.cloudai.system.api.domain.RowGroupVo;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import com.cloudai.system.service.dataperm.DataPermResources;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 服务间数据权限求值内部接口（契约 2026-10-10-dataperm-component-api §2 + 谓词轮 §3 增量，
 * 组件化 D18-D23）：仅 Feign 调用，网关已屏蔽 /system/inner/**；无 @PreAuthorize 沿 inner 惯例
 * （网格内信任模型，同 getUserByAccount）。两端点均为求值器薄壳：evaluate 恒留痕（D7）→
 * Decision 收敛为窄契约 ScopeVo（D19 五字段——rowGroups 行集判定唯一权威，命中明细/narratives
 * 不出 system）；deny 补痕 operation=deny 留痕。
 */
@RestController
@RequestMapping("/inner/data-perm")
@RequiredArgsConstructor
public class InnerDataPermController {

    private final DataPermEvaluator evaluator;

    /** 真实决策求值（account 显式传入 D20；不存在/停用账号按无规则 SELF 收敛不报错——方向安全） */
    @PostMapping("/evaluate")
    public R<DataPermScopeVo> evaluate(@RequestBody DataPermEvaluateRequest request) {
        DataPermDecision decision = evaluator.evaluateFor(
                request.getAccount(), request.getResource(), request.getOperation(), request.getBusinessKey());
        DataPermScopeVo scopeVo = toScopeVo(decision);
        return R.ok(scopeVo);
    }

    /** deny 安全审计补痕（D13/D23）：行级拒绝后消费方补记，不返回任何范围数据 */
    @PostMapping("/deny")
    public R<Void> deny(@RequestBody DataPermDenyRequest request) {
        evaluator.logDeny(request.getAccount(), request.getResource(), request.getBusinessKey());
        return R.ok();
    }

    /**
     * Decision → 窄契约 ScopeVo 收敛（D19 + 谓词轮 D35）：rowAll 改读 rowFilter（行集判定唯一
     * 权威——豁免态 dataScope=all() 同值，ALL+条件不豁免）；rowAll=true 时 accounts/rowGroups
     * 均空（过滤豁免无需白名单）；accounts=账号维并集（含条件时非行集语义，调试用途记档）。
     */
    private DataPermScopeVo toScopeVo(DataPermDecision decision) {
        boolean rowAll = decision.getRowFilter().isRowAll();
        List<String> accounts = rowAll ? List.of()
                : List.copyOf(decision.getDataScope().getAccounts());
        List<String> hiddenColumns = List.copyOf(decision.getColumnScope().getHiddenColumns());
        List<String> maskedColumns = List.copyOf(decision.getColumnScope().getMaskedColumns());
        List<RowGroupVo> rowGroups = rowAll ? List.of()
                : decision.getRowFilter().getGroups().stream()
                        .map(group -> toRowGroupVo(decision.getResource(), group)).toList();
        return new DataPermScopeVo(rowAll, accounts, hiddenColumns, maskedColumns, rowGroups);
    }

    /** RowGroup → RowGroupVo（组三字段原样：allAccounts/accounts/predicates） */
    private RowGroupVo toRowGroupVo(String resource, RowGroup group) {
        List<PredicateVo> predicates = group.getPredicates().stream()
                .map(predicate -> toPredicateVo(resource, predicate)).toList();
        return new RowGroupVo(group.isAllAccounts(), List.copyOf(group.getAccounts()), predicates);
    }

    /** RowPredicate → PredicateVo（自含 column/type）：type 自注册表声明，values 类型化 → 线格式字符串 */
    private PredicateVo toPredicateVo(String resource, RowPredicate predicate) {
        FilterColumn declared = DataPermResources.findFilterColumn(resource, predicate.getField());
        FilterColumnTypeEnum type = declared == null ? deriveType(predicate) : declared.type();
        List<String> values = predicate.getValues().stream().map(type::format).toList();
        return new PredicateVo(predicate.getField(), predicate.getColumn(), predicate.getOperator(), type, values);
    }

    /** 声明缺席兜底（求值 enrichment 成功组恒不至此）：按类型化值实例推导列类型 */
    private FilterColumnTypeEnum deriveType(RowPredicate predicate) {
        Object probe = predicate.getValues().isEmpty() ? null : predicate.getValues().get(0);
        if (probe instanceof BigDecimal) {
            return FilterColumnTypeEnum.NUMBER;
        }
        if (probe instanceof LocalDate) {
            return FilterColumnTypeEnum.DATE;
        }
        if (probe instanceof LocalDateTime) {
            return FilterColumnTypeEnum.DATETIME;
        }
        return FilterColumnTypeEnum.STRING;
    }
}
