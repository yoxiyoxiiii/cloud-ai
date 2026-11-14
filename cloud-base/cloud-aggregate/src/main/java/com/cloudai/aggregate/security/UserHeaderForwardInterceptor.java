package com.cloudai.aggregate.security;

import feign.RequestInterceptor;
import feign.RequestTemplate;

import java.util.Map;

/**
 * Feign 出站拦截器：把当前聚合请求携带的用户身份五头写入下游请求头。
 * 头值来源是网关注入的真实身份（网关已剥离外部伪造头）；非聚合请求线程（上下文为空）不注入，
 * 下游按匿名处理、由其自身安全链裁决。空值头跳过（转发语义与下游解析为空列表一致，取跳过更干净）。
 */
public class UserHeaderForwardInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        Map<String, String> headers = AggregateUserContext.get();
        if (headers == null) {
            return;
        }
        headers.forEach((k, v) -> {
            if (v != null && !v.isEmpty()) {
                template.header(k, v);
            }
        });
    }
}
