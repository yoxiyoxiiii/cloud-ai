package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.system.api.domain.DataPermDenyRequest;
import com.cloudai.system.api.domain.DataPermEvaluateRequest;
import com.cloudai.system.api.domain.DataPermScopeVo;
import com.cloudai.system.service.dataperm.DataPermDecision;
import com.cloudai.system.service.dataperm.DataPermEvaluator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间数据权限求值内部接口（契约 2026-10-10-dataperm-component-api §2，组件化 D18-D23）：
 * 仅 Feign 调用，网关已屏蔽 /system/inner/**；无 @PreAuthorize 沿 inner 惯例（网格内信任模型，
 * 同 getUserByAccount）。两端点均为求值器薄壳：evaluate 恒留痕（D7）→ Decision 收敛为窄契约
 * ScopeVo 四字段（D19，命中明细/narratives 不出 system）；deny 补痕 operation=deny 留痕。
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

    /** Decision → 窄契约四字段收敛（D19）：rowAll=true 时 accounts 置空（过滤豁免无需白名单） */
    private DataPermScopeVo toScopeVo(DataPermDecision decision) {
        boolean rowAll = decision.getDataScope().isAll();
        List<String> accounts = rowAll ? List.of()
                : List.copyOf(decision.getDataScope().getAccounts());
        List<String> hiddenColumns = List.copyOf(decision.getColumnScope().getHiddenColumns());
        List<String> maskedColumns = List.copyOf(decision.getColumnScope().getMaskedColumns());
        return new DataPermScopeVo(rowAll, accounts, hiddenColumns, maskedColumns);
    }
}
