package com.cloudai.system.service.dataperm;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.vo.SysLeaveVo;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

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
}
