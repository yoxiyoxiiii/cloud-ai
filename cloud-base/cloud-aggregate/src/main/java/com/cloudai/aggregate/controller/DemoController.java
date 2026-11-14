package com.cloudai.aggregate.controller;

import com.cloudai.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连通性验证接口（五服务惯例，网关 /aggregate/demo 白名单放行）
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    /** 连通性探针（无业务语义，供网关路由与冒烟先行验证） */
    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("cloud-aggregate running");
    }
}
