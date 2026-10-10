package com.cloudai.system.api.dataperm;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * 消费方列声明断言单测（设计 D22 第三层防线，红绿两态）：
 * 绿——真实 String 实例字段通过；红——不存在列 / 非 String 字段 → IllegalStateException
 * （消息含列名与 VO 类名，消费方启动失败可定位）。断言语义与 provider 侧 D17 单一实现同源。
 */
class DataPermColumnsTest {

    @Test
    void assertDeclared_acceptsRealStringFields() {
        assertThatCode(() -> DataPermColumns.assertDeclared(
                SampleVo.class, List.of("title", "reason")))
                .doesNotThrowAnyException();
    }

    @Test
    void assertDeclared_rejectsNonExistentColumn() {
        IllegalStateException ex = catchThrowableOfType(
                () -> DataPermColumns.assertDeclared(SampleVo.class, List.of("remark")),
                IllegalStateException.class);

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("remark").contains("SampleVo");
    }

    @Test
    void assertDeclared_rejectsNonStringColumn() {
        IllegalStateException ex = catchThrowableOfType(
                () -> DataPermColumns.assertDeclared(SampleVo.class, List.of("createTime")),
                IllegalStateException.class);

        // createTime 为 LocalDateTime：可配列必须 String（masked 整值替换 *** 的语义前提）
        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("createTime").contains("SampleVo");
    }

    @Test
    void assertDeclared_staticFieldNotCountedAsInstanceField() {
        // serialVersionUID 为 static 字段：不得误判为可配列（D17 排除 static 修饰符）
        IllegalStateException ex = catchThrowableOfType(
                () -> DataPermColumns.assertDeclared(SampleVo.class, List.of("serialVersionUID")),
                IllegalStateException.class);

        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                .contains("serialVersionUID").contains("SampleVo");
    }
}
