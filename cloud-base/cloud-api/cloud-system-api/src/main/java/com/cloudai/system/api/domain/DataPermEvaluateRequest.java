package com.cloudai.system.api.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 跨服务数据权限求值入参（契约 2026-10-10-dataperm-component-api §2.1）。
 * 注解口径：@Data + @NoArgsConstructor（Feign Jackson 反序列化需无参构造）+ @AllArgsConstructor
 * （消费方全参构造两用）——沿 api 模块契约模型简单 POJO 先例；校验由 provider 显式轻校验承担
 * （account/operation 空白 1002、resource 未注册 3034，inner 信任域口径），不加 Bean Validation 注解。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DataPermEvaluateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 决策对象账号（消费方从自己 SecurityContext 显式传入，D20） */
    private String account;

    /** 资源标识（provider 注册表管辖，含远程资源；空白或未注册 3034） */
    private String resource;

    /** 操作类型：list / detail（空白 1002；留痕 operation 列） */
    private String operation;

    /** 业务键（detail 时为目标单据 id；list 为 null） */
    private String businessKey;
}
