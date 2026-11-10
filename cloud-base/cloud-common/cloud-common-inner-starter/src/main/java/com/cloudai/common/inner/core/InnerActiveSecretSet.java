package com.cloudai.common.inner.core;

import java.util.List;

/**
 * Redis 活跃集 JSON 结构（契约 2026-11-09-inner-access-secret-api v2 §2）：
 * 发布方（cloud-system 轮换服务）与消费方（RedisInnerSecretSource 解析）共用本模型，
 * 结构单一事实源，防两侧字段漂移。
 * 结构约定：version = DB sys_shared_secret 场景 inner 的 MAX(seq)；secrets 按 seq 降序
 * （首位 = 出站应注入的最新钥）；expireAtMs = 该行 min(expire_time, retire_time) 的
 * epoch 毫秒（一个字段同时承载过期与重叠期退场两语义，消费方本地时钟统一剔除）；
 * 空集 = {"version":N,"secrets":[]} 显式发布（全体 401，区别于键不存在回落静态）。
 */
public record InnerActiveSecretSet(long version, List<SecretEntry> secrets) {

    /** 单把密钥条目：value=密钥明文（仅存在于 Redis 与 Feign 头，任何接口不返回）；expireAtMs=退场时刻 epoch 毫秒 */
    public record SecretEntry(String value, long expireAtMs) {
    }
}
