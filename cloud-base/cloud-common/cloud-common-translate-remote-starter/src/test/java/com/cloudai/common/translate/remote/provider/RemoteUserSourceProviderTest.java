package com.cloudai.common.translate.remote.provider;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.UserEntry;
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
 * 用户远程回源单测（设计 §7.1）：R.ok 转发；R.fail / 响应 null 抛 IllegalStateException（交缓存层降级）；
 * data=null 空列表。
 */
@ExtendWith(MockitoExtension.class)
class RemoteUserSourceProviderTest {

    @Mock
    private SystemTranslateClient client;
    @InjectMocks
    private RemoteUserSourceProvider provider;

    @Test
    void listAll_forwardsSuccessData() {
        List<UserEntry> entries = List.of(new UserEntry());
        when(client.listAllUsers()).thenReturn(R.ok(entries));

        List<UserEntry> result = provider.listAll();

        assertThat(result).isSameAs(entries);
    }

    @Test
    void listAll_failResponseThrowsIllegalState() {
        when(client.listAllUsers()).thenReturn(R.fail(1000, "internal error"));

        assertThatThrownBy(provider::listAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("code=1000");
    }

    @Test
    void listAll_nullResponseThrowsIllegalState() {
        when(client.listAllUsers()).thenReturn(null);

        assertThatThrownBy(provider::listAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("response=null");
    }

    /** data=null 防御：契约空库语义 data=[]，null 按空数据口径 */
    @Test
    void listAll_nullDataReturnsEmptyList() {
        R<List<UserEntry>> response = new R<>();
        response.setCode(200);
        when(client.listAllUsers()).thenReturn(response);

        assertThat(provider.listAll()).isEmpty();
    }
}
