package com.cloudai.system.service.dataperm;

import com.cloudai.system.vo.SysLeaveVo;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 反射列应用工具单测（设计 D16）：真 SysLeaveVo + 真 ColumnScope，禁 mock——
 * D14 教训：反射映射类缺陷 mock 不可见，必须走真实字段解析路径。
 * 七场景：hidden 置 null / masked 置 *** / masked 原 null 保持 null / 空动作零修改 /
 * 脏列跳过不炸其余列正常 / masked 非 String 列跳过不炸 / hidden+masked 并存（含 null 短路）。
 */
class DataPermColumnApplierTest {

    @Test
    void hiddenColumn_setsNull_leavesOthers() {
        SysLeaveVo vo = vo("t", "r");

        DataPermColumnApplier.apply(vo, ColumnScope.of(Set.of("title"), Set.of()));

        assertThat(vo.getTitle()).isNull();
        assertThat(vo.getReason()).isEqualTo("r");
    }

    @Test
    void maskedColumn_replacedWithMask_leavesOthers() {
        SysLeaveVo vo = vo("t", "r");

        DataPermColumnApplier.apply(vo, ColumnScope.of(Set.of(), Set.of("reason")));

        assertThat(vo.getReason()).isEqualTo("***");
        assertThat(vo.getTitle()).isEqualTo("t");
    }

    @Test
    void maskedColumn_originalNull_staysNull() {
        SysLeaveVo vo = vo("t", null);

        DataPermColumnApplier.apply(vo, ColumnScope.of(Set.of(), Set.of("reason")));

        // ColumnScope.mask 口径：null→null（不替换为 ***）
        assertThat(vo.getReason()).isNull();
    }

    @Test
    void emptyScope_zeroModification() {
        SysLeaveVo vo = vo("t", "r");

        DataPermColumnApplier.apply(vo, ColumnScope.of(Set.of(), Set.of()));

        assertThat(vo.getTitle()).isEqualTo("t");
        assertThat(vo.getReason()).isEqualTo("r");
    }

    @Test
    void ghostHiddenColumn_skippedWithoutBreakingOthers() {
        SysLeaveVo vo = vo("t", "r");

        // 脏列 ghost 不在 VO 字段中：warn 跳过不炸，同 scope 内 title 正常置 null（读路径防御）
        assertThatCode(() -> DataPermColumnApplier.apply(
                vo, ColumnScope.of(Set.of("ghost", "title"), Set.of())))
                .doesNotThrowAnyException();

        assertThat(vo.getTitle()).isNull();
        assertThat(vo.getReason()).isEqualTo("r");
    }

    @Test
    void maskedNonStringColumn_skippedWithoutException() {
        SysLeaveVo vo = vo("t", "r");

        // createTime 为 LocalDateTime 字段：masked 赋 *** 会 IllegalArgumentException，双防御跳过
        assertThatCode(() -> DataPermColumnApplier.apply(
                vo, ColumnScope.of(Set.of(), Set.of("createTime"))))
                .doesNotThrowAnyException();

        assertThat(vo.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 10, 10, 12, 0));
        assertThat(vo.getReason()).isEqualTo("r");
    }

    @Test
    void hiddenAndMasked_coexistBothApplied() {
        SysLeaveVo vo = vo("t", "r");

        DataPermColumnApplier.apply(vo, ColumnScope.of(Set.of("title"), Set.of("reason")));

        assertThat(vo.getTitle()).isNull();
        assertThat(vo.getReason()).isEqualTo("***");
    }

    @Test
    void nullVoOrNullScope_shortCircuits() {
        SysLeaveVo vo = vo("t", "r");

        DataPermColumnApplier.apply(null, ColumnScope.of(Set.of("title"), Set.of()));
        DataPermColumnApplier.apply(vo, null);

        assertThat(vo.getTitle()).isEqualTo("t");
    }

    private SysLeaveVo vo(String title, String reason) {
        SysLeaveVo vo = new SysLeaveVo();
        vo.setTitle(title);
        vo.setReason(reason);
        vo.setCreateTime(LocalDateTime.of(2026, 10, 10, 12, 0));
        return vo;
    }
}
