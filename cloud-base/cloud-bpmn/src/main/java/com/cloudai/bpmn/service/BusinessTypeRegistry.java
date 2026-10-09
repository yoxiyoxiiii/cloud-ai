package com.cloudai.bpmn.service;

import com.cloudai.bpmn.entity.BpmnBusinessType;
import com.cloudai.bpmn.mapper.BpmnBusinessTypeMapper;
import com.cloudai.common.core.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 业务类型配置读取（设计 D2）：type_code → 配置行；detail_route 模板渲染 + 校验。
 * 不做内存缓存（MVP 单行配置+每次发起/列表一次 uk_type_code 等值查询，量级可忽略；
 * 缓存失效复杂度不成比例，记档可演进——移交备忘 4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessTypeRegistry {

    private static final int ERR_BUSINESS_TYPE_NOT_FOUND = 4014;

    /** 路由模板占位符 */
    private static final String PLACEHOLDER = "{businessKey}";

    private final BpmnBusinessTypeMapper businessTypeMapper;

    /** 按 type_code 查配置；查不到 4014（发起路径强校验） */
    public BpmnBusinessType findByTypeCode(String typeCode) {
        BpmnBusinessType config = businessTypeMapper.findByTypeCode(typeCode);
        if (config == null) {
            throw new BusinessException(ERR_BUSINESS_TYPE_NOT_FOUND, "业务类型不存在");
        }
        return config;
    }

    /** 容忍版查询（待办/列表渲染路径）：配置行缺失/删除时返回 null——
     *  调用方置 businessTypeName/detailPath=null（log.error 记档，不炸列表，契约 §9 降级） */
    public BpmnBusinessType findByTypeCodeOrNull(String typeCode) {
        return businessTypeMapper.findByTypeCode(typeCode);
    }

    /** detail_route 模板渲染：{businessKey} 占位符替换；渲染后校验以 / 开头且不含 //
     *  （防开放重定向形态，内网纵深防御——设计 D2/R5）；校验失败 log.error 置 null（前端隐藏跳转） */
    public String renderDetailPath(String detailRoute, String businessKey) {
        if (detailRoute == null || detailRoute.isBlank()) {
            return null;
        }
        String rendered = detailRoute.replace(PLACEHOLDER, businessKey == null ? "" : businessKey);
        if (!rendered.startsWith("/") || rendered.contains("//")) {
            log.error("detail_route 渲染校验失败（须以 / 开头且无 //），置 null 隐藏跳转: {}", rendered);
            return null;
        }
        return rendered;
    }
}
