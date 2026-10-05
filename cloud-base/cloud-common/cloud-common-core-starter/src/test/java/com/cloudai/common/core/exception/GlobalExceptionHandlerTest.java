package com.cloudai.common.core.exception;

import com.cloudai.common.core.domain.R;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessException_mapsCodeAndMessage() {
        BusinessException e = new BusinessException("用户名或密码错误");
        R<Void> r = handler.handleBusinessException(e);
        assertThat(r.getCode()).isEqualTo(1002);
        assertThat(r.getMsg()).isEqualTo("用户名或密码错误");
    }

    @Test
    void businessException_withCustomCode() {
        BusinessException e = new BusinessException(3001, "用户不存在");
        R<Void> r = handler.handleBusinessException(e);
        assertThat(r.getCode()).isEqualTo(3001);
        assertThat(r.getMsg()).isEqualTo("用户不存在");
    }

    @Test
    void validException_mapsToParamError() {
        BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(new SampleDto(), "dto");
        bindingResult.rejectValue("userName", "NotBlank", "不能为空");
        MethodArgumentNotValidException e = new MethodArgumentNotValidException(null, bindingResult);
        R<Void> r = handler.handleValidException(e);
        assertThat(r.getCode()).isEqualTo(1001);
        assertThat(r.getMsg()).contains("userName").contains("不能为空");
    }

    @Test
    void unknownException_mapsToSystemError() {
        R<Void> r = handler.handleException(new RuntimeException("boom"));
        assertThat(r.getCode()).isEqualTo(500);
        assertThat(r.getMsg()).isEqualTo("系统异常，请稍后重试");
    }

    /** 仅用于提供可读属性：BeanPropertyBindingResult.rejectValue 需要目标 bean 存在对应 getter */
    static class SampleDto {

        public String getUserName() {
            return null;
        }
    }
}
