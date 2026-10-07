package com.cloudai.system.service.translate;

import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.common.translate.provider.DictSourceProvider;
import com.cloudai.system.entity.SysDictData;
import com.cloudai.system.mapper.SysDictDataMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 字典回源实现：消费口径查询下沉 mapper（JOIN：类型启用未删 ∧ 项启用未删——契约 §2.1）。
 * 转换用原生 setter 逐字段（禁三方拷贝工具）。
 */
@Service
@RequiredArgsConstructor
public class DictItemSourceProvider implements DictSourceProvider {

    private final SysDictDataMapper dictDataMapper;

    @Override
    public List<DictItemEntry> listByDictKey(String dictKey) {
        return dictDataMapper.listEnabledByDictKey(dictKey).stream()
                .map(DictItemSourceProvider::toEntry)
                .toList();
    }

    private static DictItemEntry toEntry(SysDictData data) {
        DictItemEntry entry = new DictItemEntry();
        entry.setValue(data.getValue());
        entry.setLabel(data.getLabel());
        entry.setSort(data.getSort());
        return entry;
    }
}
