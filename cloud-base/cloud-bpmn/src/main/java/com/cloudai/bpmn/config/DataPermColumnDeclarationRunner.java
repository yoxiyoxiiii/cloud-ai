package com.cloudai.bpmn.config;

import com.cloudai.bpmn.vo.ApprovalVo;
import com.cloudai.system.api.dataperm.DataPermColumns;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 审批单数据权限列声明断言（组件化设计 D22 第三层防线，消费方防线）：启动期声明本服务消费的
 * 跨服务资源 bpmn_approval 可配列清单——与 provider 侧 DataPermResources static 块
 * registerRemote("bpmn_approval", ["title"]) 各出现一次（双层声明），漂移由本断言兜底
 * （provider 清单 ⊋ 本声明时多余列配了规则但 applier warn 跳过；本声明列不在 ApprovalVo
 * 字段中 → IllegalStateException 启动失败）。断言语义与 provider D17 单一实现同源
 * （DataPermColumns.assertDeclared）。
 */
@Component
public class DataPermColumnDeclarationRunner implements ApplicationRunner {

    /** bpmn_approval 可配列（与 provider 注册行逐字一致）：title=标题快照（ApprovalVo String 字段） */
    static final List<String> APPROVAL_COLUMNS = List.of("title");

    @Override
    public void run(ApplicationArguments args) {
        // 不 try 包：断言失败即启动失败（fail-fast，D22——启动期防线不得静默降级）
        DataPermColumns.assertDeclared(ApprovalVo.class, APPROVAL_COLUMNS);
    }
}
