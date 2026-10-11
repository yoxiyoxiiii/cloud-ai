package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可过滤列声明断言单测（设计 D32，2026-10-11 谓词轮）：红绿两态 + 父类字段遍历。
 * api 模块不依赖 system——用测试内建 FixtureEntity（父类模拟 BaseEntity 的 createTime）
 * 覆盖断言面，不引用 SysLeave/BpmnApproval 真实体。
 */
class FilterColumnsTest {

    /** 模拟 BaseEntity：审计字段在父类（createTime 是 D32 类层级遍历的关键用例） */
    private static class FixtureParent {
        private LocalDateTime createTime;
        private String createBy;
    }

    /** 模拟业务 entity：typed 形态字段（id Long/leaveType Integer/startDate LocalDate） */
    private static class FixtureEntity extends FixtureParent {
        private Long id;
        private Integer leaveType;
        private LocalDate startDate;
        private LocalDate endDate;
    }

    @Test
    void assertDeclared_green_allColumnsWithParentField() {
        List<FilterColumn> columns = List.of(
                new FilterColumn("id", "id", FilterColumnTypeEnum.NUMBER, "单号"),
                new FilterColumn("leaveType", "leave_type", FilterColumnTypeEnum.NUMBER, "请假类型"),
                new FilterColumn("startDate", "start_date", FilterColumnTypeEnum.DATE, "开始日期"),
                new FilterColumn("endDate", "end_date", FilterColumnTypeEnum.DATE, "结束日期"),
                new FilterColumn("createTime", "create_time", FilterColumnTypeEnum.DATETIME, "发起时间"));

        // 绿态：四型全过 + createTime 在父类（类层级遍历，D32 与 Applier 的差异记档点）
        assertThatCode(() -> FilterColumns.assertDeclared(FixtureEntity.class, columns))
                .doesNotThrowAnyException();
    }

    @Test
    void assertDeclared_red_unknownField() {
        FilterColumn unknown = new FilterColumn("ghost", "ghost", FilterColumnTypeEnum.NUMBER, "不存在");

        assertThatThrownBy(() -> FilterColumns.assertDeclared(FixtureEntity.class, List.of(unknown)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void assertDeclared_red_typeMismatch() {
        FilterColumn mismatch = new FilterColumn("startDate", "start_date",
                FilterColumnTypeEnum.NUMBER, "日期列误声明数值");

        assertThatThrownBy(() -> FilterColumns.assertDeclared(FixtureEntity.class, List.of(mismatch)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startDate")
                .hasMessageContaining("不匹配");
    }

    @Test
    void assertDeclared_red_stringFieldDeclaredAsNumber() {
        FilterColumn mismatch = new FilterColumn("createBy", "create_by",
                FilterColumnTypeEnum.NUMBER, "字符串列误声明数值");

        assertThatThrownBy(() -> FilterColumns.assertDeclared(FixtureEntity.class, List.of(mismatch)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("createBy");
    }
}
