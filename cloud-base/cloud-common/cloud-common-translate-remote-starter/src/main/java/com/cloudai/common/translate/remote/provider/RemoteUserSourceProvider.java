package com.cloudai.common.translate.remote.provider;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.core.exception.ErrorCode;
import com.cloudai.common.translate.domain.UserEntry;
import com.cloudai.common.translate.provider.UserSourceProvider;
import com.cloudai.common.translate.remote.client.SystemTranslateClient;

import java.util.List;

/**
 * 用户回源远程实现：转发 Feign 客户端调 cloud-system /inner/user/all（全量投影含停用用户）。
 * R 非 200 / 响应缺失一律抛 IllegalStateException——交 TranslationCacheService 统一降级
 * （log.error → 空列表 → 译文 null 不写缓存，设计 §6 降级总纲，不在此重复 fallback 层）。
 */
public class RemoteUserSourceProvider implements UserSourceProvider {

    private final SystemTranslateClient client;

    public RemoteUserSourceProvider(SystemTranslateClient client) {
        this.client = client;
    }

    @Override
    public List<UserEntry> listAll() {
        R<List<UserEntry>> response = client.listAllUsers();
        if (response == null || response.getCode() != ErrorCode.SUCCESS.getCode()) {
            throw new IllegalStateException("user source remote failed"
                    + (response == null ? ", response=null" : ", code=" + response.getCode()));
        }
        // data null 防御：契约恒返回数组，null 按空数据口径处理（空库语义 data=[]）
        return response.getData() == null ? List.of() : response.getData();
    }
}
