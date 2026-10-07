package com.cloudai.common.translate.remote.provider;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.common.translate.remote.client.SystemTranslateClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 字典远程回源单测（设计 §7.1）：R.ok 转发；R.fail / 响应 null 抛 IllegalStateException（交缓存层降级）；
 * data=null 空列表。
 */
@ExtendWith(MockitoExtension.class)
class RemoteDictSourceProviderTest {

    @Mock
    private SystemTranslateClient client;
    @InjectMocks
    private RemoteDictSourceProvider provider;

    @Test
    void listByDictKey_forwardsSuccessData() {
        List<DictItemEntry> items = List.of(new DictItemEntry());
        when(client.listDictItems("user_status")).thenReturn(R.ok(items));

        List<DictItemEntry> result = provider.listByDictKey("user_status");

        assertThat(result).isSameAs(items);
    }

    @Test
    void listByDictKey_failResponseThrowsIllegalState() {
        when(client.listDictItems("user_status")).thenReturn(R.fail(3008, "字典类型不存在"));

        assertThatThrownBy(() -> provider.listByDictKey("user_status"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dictKey=user_status")
                .hasMessageContaining("3008");
    }

    @Test
    void listByDictKey_nullResponseThrowsIllegalState() {
        when(client.listDictItems("user_status")).thenReturn(null);

        assertThatThrownBy(() -> provider.listByDictKey("user_status"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("response=null");
    }

    /** data=null 防御：契约恒返回数组，null 按空数据口径（空列表是合法缓存数据，调用方写缓存 TTL） */
    @Test
    void listByDictKey_nullDataReturnsEmptyList() {
        R<List<DictItemEntry>> response = new R<>();
        response.setCode(200);
        when(client.listDictItems("user_status")).thenReturn(response);

        assertThat(provider.listByDictKey("user_status")).isEmpty();
    }
}
