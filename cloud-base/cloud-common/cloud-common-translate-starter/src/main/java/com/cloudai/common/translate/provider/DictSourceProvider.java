package com.cloudai.common.translate.provider;

import com.cloudai.common.translate.domain.DictItemEntry;

import java.util.List;

/**
 * 字典回源 SPI：缓存未命中时由实现方查库（或跨服务 Feign）返回该字典键的启用项（消费口径由实现方保证）。
 * 服务侧以 @Service 实现；无实现时缓存未命中返回空列表（log.warn 一次，翻译降级为 null）。
 */
public interface DictSourceProvider {

    /**
     * 按字典键取启用项（消费口径：类型启用未删 ∧ 项启用未删，sort,id 升序）。
     *
     * @param dictKey 字典类型键
     * @return 启用项列表；无对应类型/无启用项返回空列表
     */
    List<DictItemEntry> listByDictKey(String dictKey);
}
