package com.cloudai.system.api.domain;

import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataScope;
import com.cloudai.system.api.dataperm.PredicateOperatorEnum;
import com.cloudai.system.api.dataperm.RowFilter;
import com.cloudai.system.api.dataperm.RowGroup;
import com.cloudai.system.api.dataperm.RowPredicate;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 服务间求值窄契约出参（契约 §5.1 + §3 谓词增量 / 设计 D19/D35）：承载消费方读路径所需要素；
 * 命中规则明细 Decision/HitRule/narratives 不出 system（求值内部产物，契约面最小=演进最稳，
 * explain/my-scope 是 system 自有端点已覆盖）。toDataScope()/toColumnScope()/toRowFilter()
 * 在 api 模块内完成契约模型→终态对象转换（消费方零胶水）。
 * 注解口径：@Data + @NoArgsConstructor（Feign Jackson 反序列化需无参构造；字段初始化空列表）+
 * @AllArgsConstructor（provider 全参构造）；显式四参构造为 v1 形态兼容保留（D40）。
 */
@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataPermScopeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 行范围=全部（过滤豁免） */
    private boolean rowAll;

    /**
     * 账号白名单——<b>语义自谓词轮收窄记档（D35，既有字段非删除）</b>：= 账号维并集，
     * 含条件规则时<b>不再是行集语义</b>（行集判定一律以 rowGroups 为准），仅供调试/摘要；
     * rowAll=true 时空；无条件规则退化为 v1 行集语义（与 toDataScope 一致）。
     */
    private List<String> accounts = new ArrayList<>();

    /** 隐藏列清单（VO 字段置 null；无则空） */
    private List<String> hiddenColumns = new ArrayList<>();

    /** 脱敏列清单（整值替换 ***；无则空） */
    private List<String> maskedColumns = new ArrayList<>();

    /** 行级分组并集（D31/D35；rowAll=true 时空数组）——行集判定唯一权威 */
    private List<RowGroupVo> rowGroups = new ArrayList<>();

    /** v1 形态兼容构造（D40）：rowGroups 空构造，语义与谓词轮前逐字一致（既有调用点零迁移） */
    public DataPermScopeVo(boolean rowAll, List<String> accounts, List<String> hiddenColumns,
            List<String> maskedColumns) {
        this.rowAll = rowAll;
        this.accounts = accounts;
        this.hiddenColumns = hiddenColumns;
        this.maskedColumns = maskedColumns;
    }

    /** 行级终态：rowAll=true 过滤豁免；否则按 accounts 白名单（空集合法=空范围） */
    public DataScope toDataScope() {
        return rowAll ? DataScope.all() : DataScope.of(Set.copyOf(accounts));
    }

    /** 列级终态：hidden 置 null / masked 整值 *** */
    public ColumnScope toColumnScope() {
        return ColumnScope.of(Set.copyOf(hiddenColumns), Set.copyOf(maskedColumns));
    }

    /**
     * 行级终态（谓词轮新增，D31/D35——消费方 list SQL 与 detail 判定的行集权威）：
     * rowAll 短路；RowGroupVo→RowGroup（PredicateVo 值字符串经 FilterColumnTypeEnum 归一类型化）。
     * v1 形态回退：rowGroups 空且 accounts 非空时按 accounts 重建无条件单组（与 toDataScope
     * 语义逐字等价，旧 provider/旧测试构造零迁移）；两空=空范围。
     */
    public RowFilter toRowFilter() {
        if (rowAll) {
            return RowFilter.rowAll();
        }
        if (rowGroups == null || rowGroups.isEmpty()) {
            if (accounts == null || accounts.isEmpty()) {
                return RowFilter.of(List.of());
            }
            return RowFilter.of(List.of(RowGroup.of(false, Set.copyOf(accounts), List.of())));
        }
        List<RowGroup> merged = new ArrayList<>();
        for (RowGroupVo groupVo : rowGroups) {
            merged.add(toRowGroup(groupVo));
        }
        return RowFilter.of(merged);
    }

    private RowGroup toRowGroup(RowGroupVo groupVo) {
        List<RowPredicate> predicates = new ArrayList<>();
        List<PredicateVo> predicateVos = groupVo.getPredicates() == null
                ? List.of() : groupVo.getPredicates();
        for (PredicateVo predicateVo : predicateVos) {
            predicates.add(toRowPredicate(predicateVo));
        }
        Set<String> groupAccounts = groupVo.getAccounts() == null
                ? Set.of() : Set.copyOf(groupVo.getAccounts());
        return RowGroup.of(groupVo.isAllAccounts(), groupAccounts, predicates);
    }

    /**
     * 谓词转换 + fail-closed 降级：操作符未知/值归一失败 → 恒不满足谓词占位
     * （IN 空值组永不命中）——不丢弃该谓词（丢弃=放大授权，红线禁放行分支）。
     */
    private RowPredicate toRowPredicate(PredicateVo vo) {
        try {
            PredicateOperatorEnum operator = PredicateOperatorEnum.of(vo.getOperator());
            List<Object> typedValues = new ArrayList<>();
            List<String> rawValues = vo.getValues() == null ? List.of() : vo.getValues();
            for (String value : rawValues) {
                typedValues.add(vo.getType().normalize(value));
            }
            return RowPredicate.of(vo.getField(), vo.getColumn(), operator, typedValues);
        } catch (IllegalArgumentException e) {
            log.error("ScopeVo 谓词归一失败（该组将恒不满足，fail-closed）: {}", vo, e);
            return RowPredicate.of(vo.getField(), vo.getColumn(), PredicateOperatorEnum.IN, List.of());
        }
    }
}
