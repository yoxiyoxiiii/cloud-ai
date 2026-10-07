package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.common.translate.provider.DictSourceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * inner 字典端点委托转发单测（契约 2026-10-07-inner-api §3.1）：controller 只转发 Provider，
 * 空/未知 dictKey 的空数组语义在 Provider 消费口径（mapper JOIN），单测只验转发与空列表透传。
 */
@ExtendWith(MockitoExtension.class)
class InnerDictControllerTest {

    @Mock
    private DictSourceProvider dictSourceProvider;
    @InjectMocks
    private InnerDictController controller;

    @Test
    void listByDictKey_delegatesToProvider() {
        DictItemEntry item = new DictItemEntry();
        item.setValue("0");
        item.setLabel("正常");
        item.setSort(1);
        when(dictSourceProvider.listByDictKey("user_status")).thenReturn(List.of(item));

        R<List<DictItemEntry>> result = controller.listByDictKey("user_status");

        verify(dictSourceProvider).listByDictKey("user_status");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).extracting(DictItemEntry::getLabel).containsExactly("正常");
    }

    /** 未知 dictKey → Provider 返回空列表 → 200 + 空数组透传（空数组是合法缓存数据，调用方写缓存 TTL） */
    @Test
    void listByDictKey_unknownKeyReturnsEmptyArray() {
        when(dictSourceProvider.listByDictKey("nonexist")).thenReturn(List.of());

        R<List<DictItemEntry>> result = controller.listByDictKey("nonexist");

        verify(dictSourceProvider).listByDictKey("nonexist");
        assertThat(result.getCode()).isEqualTo(200);
        assertThat(result.getData()).isEmpty();
    }
}
