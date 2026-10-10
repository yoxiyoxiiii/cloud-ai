package com.cloudai.system.api.domain;

import com.cloudai.system.api.dataperm.ColumnScope;
import com.cloudai.system.api.dataperm.DataScope;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 服务间求值窄契约出参（契约 §5.1 / 设计 D19）：仅承载消费方读路径所需四要素；
 * 命中规则明细 Decision/HitRule/narratives 不出 system（求值内部产物，契约面最小=演进最稳，
 * explain/my-scope 是 system 自有端点已覆盖）。toDataScope()/toColumnScope() 在 api 模块内
 * 完成契约模型→终态对象转换（消费方零胶水）。
 * 注解口径：@Data + @NoArgsConstructor（Feign Jackson 反序列化需无参构造；字段初始化空列表）+
 * @AllArgsConstructor（provider 全参构造）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataPermScopeVo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 行范围=全部（过滤豁免） */
    private boolean rowAll;

    /** 账号白名单（rowAll=true 时空；空数组+rowAll=false=空集短路——消费方不查库） */
    private List<String> accounts = new ArrayList<>();

    /** 隐藏列清单（VO 字段置 null；无则空） */
    private List<String> hiddenColumns = new ArrayList<>();

    /** 脱敏列清单（整值替换 ***；无则空） */
    private List<String> maskedColumns = new ArrayList<>();

    /** 行级终态：rowAll=true 过滤豁免；否则按 accounts 白名单（空集合法=空范围） */
    public DataScope toDataScope() {
        return rowAll ? DataScope.all() : DataScope.of(Set.copyOf(accounts));
    }

    /** 列级终态：hidden 置 null / masked 整值 *** */
    public ColumnScope toColumnScope() {
        return ColumnScope.of(Set.copyOf(hiddenColumns), Set.copyOf(maskedColumns));
    }
}
