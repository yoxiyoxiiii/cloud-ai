package com.cloudai.common.translate.core;

import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Advisor 翻译回填单测（设计 §7.1）——含设计规则 0 守护用例：
 * 翻译前后原字段（status/createBy/updateBy/id/account）逐字段一致（红线：翻译绝不覆盖原字段）。
 */
class TranslateAdvisorTest {

    private final TranslationCacheService cacheService = mock(TranslationCacheService.class);
    private final TranslateAdvisor advisor = new TranslateAdvisor(new TransFieldScanner(), cacheService);

    /** 样板 VO：形态对齐 SysUserVo（契约 §3.1） */
    @TranslateVO
    @Data
    static class SampleUserVo {
        private Long id;
        private String account;
        @DictTrans(dictKey = "user_status", labelField = "statusLabel")
        private Integer status;
        private String statusLabel;
        @UserTrans(labelField = "createByName")
        private String createBy;
        private String createByName;
        @UserTrans(labelField = "updateByName")
        private String updateBy;
        private String updateByName;
    }

    /** labelField 目标名写错的坏样板（防呆分支） */
    @TranslateVO
    @Data
    static class BrokenLabelVo {
        @DictTrans(dictKey = "user_status", labelField = "noSuchField")
        private Integer status;
    }

    /** 自引用 VO（防环分支） */
    @TranslateVO
    @Data
    static class CycleVo {
        @UserTrans(labelField = "accountName")
        private String account;
        private String accountName;
        private CycleVo child;
    }

    /** 既有 controller 形态的假方法：supports 泛型解析样本 */
    @SuppressWarnings("unused")
    R<PageResult<SampleUserVo>> samplePage() {
        return null;
    }

    @SuppressWarnings("unused")
    R<List<SampleUserVo>> sampleList() {
        return null;
    }

    @SuppressWarnings("unused")
    R<Void> sampleVoid() {
        return null;
    }

    @SuppressWarnings("unused")
    R<Long> sampleLong() {
        return null;
    }

    @Test
    void fillLabels_translatedAndOriginalFieldsUntouched() throws Exception {
        // 红线守护（设计规则 0）：翻译只写 labelField，原字段逐字段一致
        SampleUserVo vo = new SampleUserVo();
        vo.setId(1L);
        vo.setAccount("admin");
        vo.setStatus(0);
        vo.setCreateBy("admin");
        vo.setUpdateBy("admin");
        // 翻译前原字段快照
        Long idBefore = vo.getId();
        String accountBefore = vo.getAccount();
        Integer statusBefore = vo.getStatus();
        String createByBefore = vo.getCreateBy();
        String updateByBefore = vo.getUpdateBy();

        when(cacheService.findDictLabels(eq("user_status"), anyCollection())).thenReturn(Map.of("0", "正常"));
        when(cacheService.findUserNames(anyCollection())).thenReturn(Map.of("admin", "管理员"));

        R<PageResult<SampleUserVo>> body = R.ok(PageResult.of(1, List.of(vo)));
        Object result = write(body);

        assertThat(result).isSameAs(body);
        assertThat(vo.getStatusLabel()).isEqualTo("正常");
        assertThat(vo.getCreateByName()).isEqualTo("管理员");
        assertThat(vo.getUpdateByName()).isEqualTo("管理员");
        // 原字段逐字段一致（红线）
        assertThat(vo.getId()).isEqualTo(idBefore);
        assertThat(vo.getAccount()).isEqualTo(accountBefore);
        assertThat(vo.getStatus()).isEqualTo(statusBefore);
        assertThat(vo.getCreateBy()).isEqualTo(createByBefore);
        assertThat(vo.getUpdateBy()).isEqualTo(updateByBefore);
    }

    @Test
    void listData_translated() throws Exception {
        SampleUserVo first = new SampleUserVo();
        first.setStatus(1);
        SampleUserVo second = new SampleUserVo();
        second.setStatus(0);
        second.setCreateBy("admin");
        when(cacheService.findDictLabels(eq("user_status"), anyCollection())).thenReturn(Map.of("0", "正常", "1", "停用"));
        when(cacheService.findUserNames(anyCollection())).thenReturn(Map.of("admin", "管理员"));

        R<List<SampleUserVo>> body = R.ok(List.of(first, second));
        write(body);

        assertThat(first.getStatusLabel()).isEqualTo("停用");
        assertThat(second.getStatusLabel()).isEqualTo("正常");
        assertThat(second.getCreateByName()).isEqualTo("管理员");
    }

    @Test
    void supports_annotatedVoMatched_othersSkipped() throws Exception {
        assertThat(advisor.supports(returnType("samplePage"), Converter.class)).isTrue();
        assertThat(advisor.supports(returnType("sampleList"), Converter.class)).isTrue();
        assertThat(advisor.supports(returnType("sampleVoid"), Converter.class)).isFalse();
        assertThat(advisor.supports(returnType("sampleLong"), Converter.class)).isFalse();
    }

    @Test
    void labelFieldTargetMissing_skippedNoError() throws Exception {
        BrokenLabelVo vo = new BrokenLabelVo();
        vo.setStatus(0);
        R<BrokenLabelVo> body = R.ok(vo);
        assertThatCode(() -> write(body)).doesNotThrowAnyException();
        assertThat(vo.getStatus()).isEqualTo(0);
        verify(cacheService, never()).findDictLabels(any(), anyCollection());
    }

    @Test
    void manualTranslation_kept() throws Exception {
        SampleUserVo vo = new SampleUserVo();
        vo.setStatus(0);
        vo.setStatusLabel("手翻");
        when(cacheService.findUserNames(anyCollection())).thenReturn(Map.of());

        write(R.ok(PageResult.of(1, List.of(vo))));

        // 手翻优先：labelField 已非 null 时跳过回填
        assertThat(vo.getStatusLabel()).isEqualTo("手翻");
    }

    @Test
    void cycleReference_noInfiniteLoop() {
        CycleVo vo = new CycleVo();
        vo.setAccount("admin");
        vo.setChild(vo);
        when(cacheService.findUserNames(anyCollection())).thenReturn(Map.of("admin", "管理员"));

        assertThatCode(() -> write(R.ok(PageResult.of(1, List.of(vo))))).doesNotThrowAnyException();
        assertThat(vo.getAccountName()).isEqualTo("管理员");
    }

    @Test
    void cacheServiceThrows_originalBodyReturned() throws Exception {
        SampleUserVo vo = new SampleUserVo();
        vo.setStatus(0);
        vo.setCreateBy("admin");
        doThrow(new RuntimeException("redis down")).when(cacheService).findDictLabels(any(), anyCollection());

        R<PageResult<SampleUserVo>> body = R.ok(PageResult.of(1, List.of(vo)));
        Object result = write(body);

        // 降级总纲：翻译异常不影响业务响应——原 body 返回、原字段不变、译文为 null
        assertThat(result).isSameAs(body);
        assertThat(vo.getStatus()).isEqualTo(0);
        assertThat(vo.getStatusLabel()).isNull();
        assertThat(vo.getCreateBy()).isEqualTo("admin");
    }

    @Test
    void nullDataSafe() {
        R<Void> body = R.ok();
        assertThatCode(() -> write(body)).doesNotThrowAnyException();
    }

    @Test
    void depthBeyondLimit_skipped() throws Exception {
        SampleUserVo vo = new SampleUserVo();
        vo.setStatus(0);
        when(cacheService.findDictLabels(eq("user_status"), anyCollection())).thenReturn(Map.of("0", "正常"));

        // R → PageResult(1) → PageResult(2) → List(3) → VO(4 超上限剪枝)
        write(R.ok(PageResult.of(1, List.of(PageResult.of(1, List.of(vo))))));

        assertThat(vo.getStatus()).isEqualTo(0);
        assertThat(vo.getStatusLabel()).isNull();
        verify(cacheService, never()).findDictLabels(any(), anyCollection());
    }

    private Object write(Object body) throws Exception {
        return advisor.beforeBodyWrite(body, returnType("samplePage"), MediaType.APPLICATION_JSON,
                Converter.class,
                new ServletServerHttpRequest(new MockHttpServletRequest()),
                new ServletServerHttpResponse(new MockHttpServletResponse()));
    }

    private MethodParameter returnType(String methodName) throws NoSuchMethodException {
        return new MethodParameter(TranslateAdvisorTest.class.getDeclaredMethod(methodName), -1);
    }

    /** converterType 形参占位（Advisor 不读其内容） */
    private static final class Converter implements HttpMessageConverter<Object> {
        @Override
        public boolean canRead(Class<?> clazz, MediaType mediaType) {
            return false;
        }

        @Override
        public boolean canWrite(Class<?> clazz, MediaType mediaType) {
            return false;
        }

        @Override
        public List<MediaType> getSupportedMediaTypes() {
            return List.of();
        }

        @Override
        public Object read(Class<?> clazz, org.springframework.http.HttpInputMessage inputMessage) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void write(Object o, MediaType contentType,
                          org.springframework.http.HttpOutputMessage outputMessage) {
            throw new UnsupportedOperationException();
        }
    }
}
