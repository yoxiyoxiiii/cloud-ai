package com.cloudai.system.api.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 服务间求值窄契约——行级分组出参（契约 §5.3 / 设计 D31/D35，2026-10-11 谓词轮）：
 * 行级分组并集（rowGroups）的组元素，行集判定唯一权威（消费方 list SQL 组间 OR + detail
 * 组匹配）。allAccounts=true 时 accounts 恒空（ALL+条件组，owner 无关授权形态）；
 * 空数组+false=组恒假（求值器已丢弃死组，正常不出现）。
 * 注解口径沿 DataPermScopeVo（@Data + @NoArgsConstructor + @AllArgsConstructor，Feign 反序列化）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RowGroupVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** true=组不限归属账号（ALL+条件组；此时 accounts 空） */
    private boolean allAccounts;

    /** 组账号白名单（allAccounts=true 时空；无条件规则账号并集单组即此形态） */
    private List<String> accounts = new ArrayList<>();

    /** 组条件（组内 AND；无条件组空） */
    private List<PredicateVo> predicates = new ArrayList<>();
}
