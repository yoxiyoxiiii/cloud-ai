package com.cloudai.common.translate.core;

import com.cloudai.common.translate.annotation.DictTrans;
import com.cloudai.common.translate.annotation.TranslateVO;
import com.cloudai.common.translate.annotation.UserTrans;
import lombok.Data;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 扫描器元数据缓存与 labelField 防呆校验单测（设计 §7.1）。
 */
class TransFieldScannerTest {

    private final TransFieldScanner scanner = new TransFieldScanner();

    @TranslateVO
    @Data
    static class ValidVo {
        @DictTrans(dictKey = "user_status", labelField = "statusLabel")
        private Integer status;
        private String statusLabel;
        @UserTrans(labelField = "createByName")
        private String createBy;
        private String createByName;
    }

    @TranslateVO
    @Data
    static class MissingTargetVo {
        @DictTrans(dictKey = "user_status", labelField = "noSuchField")
        private Integer status;
    }

    @TranslateVO
    @Data
    static class NonStringTargetVo {
        @DictTrans(dictKey = "user_status", labelField = "statusLabel")
        private Integer status;
        private Integer statusLabel;
    }

    @TranslateVO
    @Data
    static class FinalTargetVo {
        @UserTrans(labelField = "accountName")
        private String account;
        private final String accountName = null;
    }

    @Data
    static class PlainVo {
        private Integer status;
    }

    @Test
    void metaCachedByClass_sameInstanceOnSecondCall() {
        List<TransFieldScanner.TransFieldMeta> first = scanner.findTransFields(ValidVo.class);
        List<TransFieldScanner.TransFieldMeta> second = scanner.findTransFields(ValidVo.class);
        assertThat(second).isSameAs(first);
    }

    @Test
    void validVo_metaFieldsAndKindsFilled() {
        List<TransFieldScanner.TransFieldMeta> metas = scanner.findTransFields(ValidVo.class);
        assertThat(metas).hasSize(2);
        TransFieldScanner.TransFieldMeta dictMeta = metas.get(0);
        assertThat(dictMeta.getSourceField().getName()).isEqualTo("status");
        assertThat(dictMeta.getLabelField().getName()).isEqualTo("statusLabel");
        assertThat(dictMeta.getDictKey()).isEqualTo("user_status");
        assertThat(dictMeta.getKind()).isEqualTo(TransFieldScanner.TransKind.DICT);
        TransFieldScanner.TransFieldMeta userMeta = metas.get(1);
        assertThat(userMeta.getSourceField().getName()).isEqualTo("createBy");
        assertThat(userMeta.getLabelField().getName()).isEqualTo("createByName");
        assertThat(userMeta.getKind()).isEqualTo(TransFieldScanner.TransKind.USER);
    }

    @Test
    void labelFieldTargetMissing_dropped() {
        assertThat(scanner.findTransFields(MissingTargetVo.class)).isEmpty();
    }

    @Test
    void labelFieldNotString_dropped() {
        assertThat(scanner.findTransFields(NonStringTargetVo.class)).isEmpty();
    }

    @Test
    void labelFieldFinal_dropped() {
        assertThat(scanner.findTransFields(FinalTargetVo.class)).isEmpty();
    }

    @Test
    void plainVo_emptyMeta() {
        assertThat(scanner.findTransFields(PlainVo.class)).isEmpty();
    }
}
