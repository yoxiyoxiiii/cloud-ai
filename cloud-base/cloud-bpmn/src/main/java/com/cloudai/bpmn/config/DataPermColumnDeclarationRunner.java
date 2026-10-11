package com.cloudai.bpmn.config;

import com.cloudai.bpmn.entity.BpmnApproval;
import com.cloudai.bpmn.vo.ApprovalVo;
import com.cloudai.system.api.dataperm.DataPermColumns;
import com.cloudai.system.api.dataperm.FilterColumn;
import com.cloudai.system.api.dataperm.FilterColumns;
import com.cloudai.system.api.dataperm.FilterColumnTypeEnum;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审批单数据权限列声明断言（组件化设计 D22 第三层防线，消费方防线；谓词轮 D32 增可过滤列维度）：
 * 启动期声明本服务消费的跨服务资源 bpmn_approval 可配列 + 可过滤列清单——与 provider 侧
 * DataPermResources static 块 registerRemote 各出现一次（双层声明），漂移由本断言兜底。
 * 可配列锚 ApprovalVo（title 不在字段中 → IllegalStateException 启动失败，D17 单一实现）；
 * 可过滤列锚 BpmnApproval entity（字段存在 + 类型匹配断言，D32——createTime 遍历类层级
 * 命中 BaseEntity）。
 */
@Component
public class DataPermColumnDeclarationRunner implements ApplicationRunner {

    /** bpmn_approval 可配列（与 provider 注册行逐字一致）：title=标题快照（ApprovalVo String 字段） */
    static final List<String> APPROVAL_COLUMNS = List.of("title");

    /** bpmn_approval 可过滤列（与 provider 注册行逐字一致，D32——XML 谓词分支对照面） */
    static final List<FilterColumn> APPROVAL_FILTER_COLUMNS = List.of(
            new FilterColumn("id", "id", FilterColumnTypeEnum.NUMBER, "审批单号"),
            new FilterColumn("status", "status", FilterColumnTypeEnum.NUMBER, "状态"),
            new FilterColumn("businessType", "business_type", FilterColumnTypeEnum.STRING, "业务类型"),
            new FilterColumn("createTime", "create_time", FilterColumnTypeEnum.DATETIME, "发起时间"));

    @Override
    public void run(ApplicationArguments args) {
        // 不 try 包：断言失败即启动失败（fail-fast，D22/D32——启动期防线不得静默降级）
        DataPermColumns.assertDeclared(ApprovalVo.class, APPROVAL_COLUMNS);
        FilterColumns.assertDeclared(BpmnApproval.class, APPROVAL_FILTER_COLUMNS);
    }
}
