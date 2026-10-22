package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.api.dataperm.FilterColumn;
import com.cloudai.system.api.dataperm.FilterColumnTypeEnum;
import com.cloudai.system.vo.SysLeaveVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 注册表 Class 化升级单测（设计 D15/D17，红绿两态）：绿——leave 注册与五个公共 API
 * 行为等价（isRegistered/可配列/assertColumn/listRegisteredResources/未知资源假）；
 * 红——包可见断言体直调：不存在列 / 非 String 字段 → IllegalStateException（fail-fast）。
 * register 的空白 resource / 重复注册分支在静态初始化完成后不可复现（不引入 reset 钩子，
 * 生产纯度优先——设计 §3.3 记档取舍），由代码评审覆盖。
 */
class DataPermResourcesTest {

    // ---- 绿态：公共面行为等价（消费方 Evaluator/ManageService 零改动的前提） ----

    @Test
    void leave_registeredWithConfigurableColumns() {
        assertThat(DataPermResources.isRegistered("leave")).isTrue();
        assertThat(DataPermResources.getConfigurableColumns("leave"))
                .containsExactly("title", "reason");
        assertThat(DataPermResources.listRegisteredResources()).contains("leave");
    }

    // ---- 组件化轮（D22）：远程资源 registerRemote 纯字符串注册 ----

    @Test
    void bpmnApproval_remoteRegistered_withTitleColumnOnly() {
        // 静态初始化成功本身即证明 registerRemote 不调 D17 类断言（voClass=null——断言若触达会 NPE 启动失败）
        assertThat(DataPermResources.isRegistered(DataPermResources.BPMN_APPROVAL)).isTrue();
        assertThat(DataPermResources.getConfigurableColumns(DataPermResources.BPMN_APPROVAL))
                .containsExactly("title");
        assertThat(DataPermResources.listRegisteredResources())
                .contains(DataPermResources.BPMN_APPROVAL);
    }

    @Test
    void bpmnApproval_assertColumn_sameValidationChain() {
        // 远程资源走同一 3034 校验链（配置台/my-scope 自然工作，消费的只是字符串清单）
        assertThatCode(() -> DataPermResources.assertColumn(DataPermResources.BPMN_APPROVAL, "title"))
                .doesNotThrowAnyException();

        BusinessException ex = catchThrowableOfType(
                () -> DataPermResources.assertColumn(DataPermResources.BPMN_APPROVAL, "reason"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3034);
    }

    @Test
    void unknownOrNullResource_notRegistered() {
        assertThat(DataPermResources.isRegistered("ghost")).isFalse();
        assertThat(DataPermResources.isRegistered(null)).isFalse();
        assertThat(DataPermResources.getConfigurableColumns("ghost")).isEmpty();
    }

    @Test
    void assertColumn_acceptsRegisteredColumn() {
        assertThatCode(() -> DataPermResources.assertColumn("leave", "reason"))
                .doesNotThrowAnyException();
    }

    @Test
    void assertColumn_rejectsUnknownColumnWith3034() {
        BusinessException ex = catchThrowableOfType(
                () -> DataPermResources.assertColumn("leave", "ghost"), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3034);
    }

    @Test
    void assertResource_rejectsUnknownResourceWith3034() {
        BusinessException ex = catchThrowableOfType(
                () -> DataPermResources.assertResource("ghost"), BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3034);
    }

    // ---- 红态：D17 声明断言体直调（包可见，不触 REGISTRY） ----

    @Test
    void assertDeclaredColumns_acceptsRealStringFields() {
        assertThatCode(() -> DataPermResources.assertDeclaredColumns(
                "leave", SysLeaveVo.class, List.of("title", "reason")))
                .doesNotThrowAnyException();
    }

    @Test
    void assertDeclaredColumns_rejectsNonExistentColumn() {
        IllegalStateException ex = catchThrowableOfType(
                () -> DataPermResources.assertDeclaredColumns(
                        "leave", SysLeaveVo.class, List.of("remark")),
                IllegalStateException.class);

        assertThat(ex.getMessage()).contains("remark").contains("SysLeaveVo");
    }

    @Test
    void assertDeclaredColumns_rejectsNonStringColumn() {
        IllegalStateException ex = catchThrowableOfType(
                () -> DataPermResources.assertDeclaredColumns(
                        "leave", SysLeaveVo.class, List.of("createTime")),
                IllegalStateException.class);

        assertThat(ex.getMessage()).contains("createTime").contains("SysLeaveVo");
    }

    // ---- 谓词轮（D32）：可过滤列清单（条件配置 3035 校验与求值 enrichment 的数据源） ----

    @Test
    void leave_filterColumnsDeclared() {
        // v2（操作符扩展轮 D42）：+title（STRING，PREFIX 兑现后进清单）
        List<FilterColumn> columns = DataPermResources.getFilterColumns("leave");
        assertThat(columns).extracting(FilterColumn::field, FilterColumn::column)
                .containsExactly(
                        tuple("id", "id"),
                        tuple("leaveType", "leave_type"),
                        tuple("startDate", "start_date"),
                        tuple("endDate", "end_date"),
                        tuple("title", "title"));
    }

    @Test
    void leave_filterColumns_dictKeyDeclarations() {
        // D45 字典列声明：leaveType 有字典（摘要/narratives 值译标签 + 前端字典下拉），其余列 null 原值
        assertThat(DataPermResources.findFilterColumn("leave", "leaveType").dictKey())
                .isEqualTo("system_leave_type");
        assertThat(DataPermResources.findFilterColumn("leave", "id").dictKey()).isNull();
        assertThat(DataPermResources.findFilterColumn("leave", "startDate").dictKey()).isNull();
        assertThat(DataPermResources.findFilterColumn("leave", "endDate").dictKey()).isNull();
        assertThat(DataPermResources.findFilterColumn("leave", "title").dictKey()).isNull();
    }

    @Test
    void findFilterColumn_locatesByFieldWithDeclaration() {
        FilterColumn leaveType = DataPermResources.findFilterColumn("leave", "leaveType");
        assertThat(leaveType).isNotNull();
        assertThat(leaveType.type()).isEqualTo(FilterColumnTypeEnum.NUMBER);
        assertThat(leaveType.label()).isEqualTo("请假类型");
    }

    @Test
    void findFilterColumn_unknownFieldOrResource_returnsNull() {
        assertThat(DataPermResources.findFilterColumn("leave", "ghost")).isNull();
        assertThat(DataPermResources.findFilterColumn("ghost", "id")).isNull();
    }

    @Test
    void leave_titleFilterColumn_stringTyped() {
        FilterColumn title = DataPermResources.findFilterColumn("leave", "title");
        assertThat(title).isNotNull();
        assertThat(title.type()).isEqualTo(FilterColumnTypeEnum.STRING);
        assertThat(title.label()).isEqualTo("标题");
    }

    @Test
    void bpmnApproval_filterColumnsRemoteDeclaredWithTypes() {
        // v2（操作符扩展轮 D42）：+title（STRING）
        assertThat(DataPermResources.getFilterColumns(DataPermResources.BPMN_APPROVAL))
                .extracting(FilterColumn::field, FilterColumn::type)
                .containsExactly(
                        tuple("id", FilterColumnTypeEnum.NUMBER),
                        tuple("status", FilterColumnTypeEnum.NUMBER),
                        tuple("businessType", FilterColumnTypeEnum.STRING),
                        tuple("createTime", FilterColumnTypeEnum.DATETIME),
                        tuple("title", FilterColumnTypeEnum.STRING));
    }

    @Test
    void bpmnApproval_filterColumns_dictKeyDeclarations() {
        // D45：status 有字典（bpmn_approval_status），其余列（含 title/businessType）null 原值
        assertThat(DataPermResources.findFilterColumn(DataPermResources.BPMN_APPROVAL, "status")
                .dictKey()).isEqualTo("bpmn_approval_status");
        assertThat(DataPermResources.findFilterColumn(DataPermResources.BPMN_APPROVAL, "id")
                .dictKey()).isNull();
        assertThat(DataPermResources.findFilterColumn(DataPermResources.BPMN_APPROVAL, "businessType")
                .dictKey()).isNull();
        assertThat(DataPermResources.findFilterColumn(DataPermResources.BPMN_APPROVAL, "title")
                .dictKey()).isNull();
    }

    // ---- 列级-only 资源（2026-10-22 列级数据权限轮）：user 注册形态 ----

    @Test
    void user_registeredColumnOnly_withMobileEmail() {
        // 静态初始化成功本身即证明 registerColumnOnly 走 D17 断言链
        //（SysUserVo 已标 @DataPermVo 且 mobile/email 是真实 String 实例字段）
        assertThat(DataPermResources.isRegistered(DataPermResources.USER)).isTrue();
        assertThat(DataPermResources.getConfigurableColumns(DataPermResources.USER))
                .containsExactly("mobile", "email");
        assertThat(DataPermResources.listRegisteredResources()).contains(DataPermResources.USER);
        // 行级不可配是注册态约束：可过滤列强制空清单（任何谓词 field 必 3035 拒）
        assertThat(DataPermResources.isColumnOnly(DataPermResources.USER)).isTrue();
        assertThat(DataPermResources.getFilterColumns(DataPermResources.USER)).isEmpty();
        assertThat(DataPermResources.findFilterColumn(DataPermResources.USER, "mobile")).isNull();
    }

    @Test
    void columnOnly_existingResourcesStayFalse() {
        // 既有资源形态零变化：leave/bpmn_approval 恒 false，未注册资源 false
        assertThat(DataPermResources.isColumnOnly("leave")).isFalse();
        assertThat(DataPermResources.isColumnOnly(DataPermResources.BPMN_APPROVAL)).isFalse();
        assertThat(DataPermResources.isColumnOnly("ghost")).isFalse();
        assertThat(DataPermResources.isColumnOnly(null)).isFalse();
    }

    @Test
    void user_assertColumn_sameValidationChain() {
        // user 资源列配置走同一 3034 校验链：注册列放行、未注册列（如 nickname）拒绝
        assertThatCode(() -> DataPermResources.assertColumn(DataPermResources.USER, "mobile"))
                .doesNotThrowAnyException();
        assertThatCode(() -> DataPermResources.assertColumn(DataPermResources.USER, "email"))
                .doesNotThrowAnyException();

        BusinessException ex = catchThrowableOfType(
                () -> DataPermResources.assertColumn(DataPermResources.USER, "nickname"),
                BusinessException.class);

        assertThat(ex.getCode()).isEqualTo(3034);
    }
}
