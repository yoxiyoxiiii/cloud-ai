package com.cloudai.common.translate.provider;

import com.cloudai.system.api.domain.UserEntry;

import java.util.List;

/**
 * 用户回源 SPI：缓存未命中时由实现方查库（或跨服务 Feign）返回全量未删用户投影。
 * 服务侧以 @Service 实现；无实现时缓存未命中返回空列表（log.warn 一次，翻译降级为 null）。
 */
public interface UserSourceProvider {

    /**
     * 全量未删用户投影（account→nickname 索引源；量级取舍见设计 D5）。
     *
     * @return 用户投影列表；无用户返回空列表
     */
    List<UserEntry> listAll();
}
