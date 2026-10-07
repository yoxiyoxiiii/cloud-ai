package com.cloudai.system.controller.feign;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.common.translate.provider.DictSourceProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 服务间内部接口（字典翻译回源）：仅 Feign 调用，网关已屏蔽 /{service}/inner/**。
 */
@RestController
@RequestMapping("/inner/dict")
@RequiredArgsConstructor
public class InnerDictController {

    private final DictSourceProvider dictSourceProvider;

    /** 按字典键取启用项（消费口径：类型与项均启用未删；契约 inner-api §3.1）——未知 dictKey/停用类型/无启用项一律空数组 */
    @GetMapping("/items/{dictKey}")
    public R<List<DictItemEntry>> listByDictKey(@PathVariable("dictKey") String dictKey) {
        List<DictItemEntry> items = dictSourceProvider.listByDictKey(dictKey);
        return R.ok(items);
    }
}
