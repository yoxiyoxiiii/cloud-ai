package com.cloudai.system.controller;

import com.cloudai.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 阶段 1 连通性验证接口（阶段 2 被系统管理接口替代后保留）
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("cloud-system running");
    }
}
