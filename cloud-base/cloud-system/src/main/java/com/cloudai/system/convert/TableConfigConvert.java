package com.cloudai.system.convert;

import com.cloudai.system.dto.TableConfigSaveRequest;
import com.cloudai.system.vo.TableConfigVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

/**
 * 表格列配置 JSON 编解码（原生转换，禁三方拷贝工具）：保存入参 → 落库 JSON 串；
 * 落库串 → GET 出参 VO。落库形态即 wire 形态 {"columns":[{key,visible,width}]}——
 * 序列化整个请求对象（单字段 columns）即得包装形态，读写零结构翻译。
 */
@Slf4j
public final class TableConfigConvert {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private TableConfigConvert() {
    }

    /** 保存请求 → 落库 JSON 串（序列化失败=程序性错误，非业务输入问题，抛 IllegalState 上层兜底） */
    public static String toConfigJson(TableConfigSaveRequest req) {
        try {
            return OBJECT_MAPPER.writeValueAsString(req);
        } catch (Exception e) {
            throw new IllegalStateException("列配置序列化失败", e);
        }
    }

    /** 落库 JSON 串 → VO；null/空白/解析失败 → null（GET 无配置语义，降级默认列序——
     *  个人偏好非安全面 fail-open 无害；坏行理论不可达：写路径已过校验链，服务端是唯一写方） */
    public static TableConfigVo toVo(String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(configJson, TableConfigVo.class);
        } catch (Exception e) {
            log.error("列配置 JSON 解析失败（降级 null=默认列序渲染）：{}", configJson, e);
            return null;
        }
    }
}
