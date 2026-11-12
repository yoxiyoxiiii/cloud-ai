package com.cloudai.task.controller;

import com.cloudai.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 连通性验证接口（三胞胎惯例，网关 /task/demo/ping 白名单放行）
 */
@RestController
@RequestMapping("/demo")
public class DemoController {

    @GetMapping("/ping")
    public R<String> ping() {
        return R.ok("cloud-task running");
    }
}
