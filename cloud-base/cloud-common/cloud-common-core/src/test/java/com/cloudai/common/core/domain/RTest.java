package com.cloudai.common.core.domain;

import com.cloudai.common.core.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RTest {

    @Test
    void ok_shouldReturn200WithDefaultMsg() {
        R<Void> r = R.ok();
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getMsg()).isEqualTo("操作成功");
        assertThat(r.getData()).isNull();
    }

    @Test
    void ok_shouldCarryData() {
        R<String> r = R.ok("pong");
        assertThat(r.getCode()).isEqualTo(200);
        assertThat(r.getData()).isEqualTo("pong");
    }

    @Test
    void fail_withCodeAndMsg() {
        R<Void> r = R.fail(500, "系统异常");
        assertThat(r.getCode()).isEqualTo(500);
        assertThat(r.getMsg()).isEqualTo("系统异常");
    }

    @Test
    void fail_withOnlyMsg_defaultsToBusinessError() {
        R<Void> r = R.fail("用户名或密码错误");
        assertThat(r.getCode()).isEqualTo(1002);
        assertThat(r.getMsg()).isEqualTo("用户名或密码错误");
    }

    @Test
    void fail_withErrorCodeEnum() {
        R<Void> r = R.fail(ErrorCode.PARAM_ERROR);
        assertThat(r.getCode()).isEqualTo(1001);
        assertThat(r.getMsg()).isEqualTo("参数校验失败");
    }
}
