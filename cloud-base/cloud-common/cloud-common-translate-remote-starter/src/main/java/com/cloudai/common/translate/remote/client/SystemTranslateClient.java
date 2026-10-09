package com.cloudai.common.translate.remote.client;

import com.cloudai.common.core.domain.R;
import com.cloudai.common.translate.domain.DictItemEntry;
import com.cloudai.system.api.domain.UserEntry;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.util.List;

/**
 * cloud-system /inner 翻译数据源客户端（契约 2026-10-07-inner-api §2.2/§3.1）。
 * <p>程序式构建：实际注册经 {@code CommonTranslateRemoteAutoConfiguration} 以 FeignClientBuilder 创建，
 * <b>不经 @EnableFeignClients 扫描</b>（common 包不在消费方扫描范围）——消费方引依赖即用、零配置。
 * FeignClientBuilder 不读取本注解（name/path 在 Builder 显式传），注解仅作元数据文档与未来扫描注册的形态锚点。
 * 超时与降级：connect 1s / read 2s（自动装配内定制）；失败由 Provider 转异常交缓存层统一降级（不写 Feign fallback）。
 */
@FeignClient(name = "cloud-system", contextId = "systemTranslateClient", path = "/inner")
public interface SystemTranslateClient {

    /** 按字典键取启用项（未知 dictKey/停用类型/无启用项一律 200 + 空数组） */
    @GetMapping("/dict/items/{dictKey}")
    R<List<DictItemEntry>> listDictItems(@PathVariable("dictKey") String dictKey);

    /** 全量用户投影（含停用用户；id 为 Long→String 字符串形态，消费端 Jackson coercion 回 Long） */
    @GetMapping("/user/all")
    R<List<UserEntry>> listAllUsers();
}
