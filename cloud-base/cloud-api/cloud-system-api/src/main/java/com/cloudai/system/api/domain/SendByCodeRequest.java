package com.cloudai.system.api.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 按模板 code 发送通知入参（契约 2026-11-10-task-center-api §11，沿 notify 契约 §2.1 三档收件人形态）。
 * Long 字段线上 JSON 按全局口径为 string，Java 模型用 Long；variables 为模板变量值透传
 * （键集由模板内容决定，服务端校验缺失变量 3045）。
 */
@Data
public class SendByCodeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 通知模板 code（如 task_fail_alert）；不存在 3044 */
    @NotBlank
    @Size(max = 50)
    private String templateCode;

    /** 接收人维度：1按用户 2按角色 3全员 */
    @NotNull
    private Integer receiverType;

    /** receiverType=1 时必填非空 */
    private List<Long> userIds;

    /** receiverType=2 时必填非空（如 admin 角色 = [1]） */
    private List<Long> roleIds;

    /** 模板变量值（可空=无变量模板） */
    private Map<String, String> variables;

    /** 前端相对路由（以 / 开头），可空 */
    @Size(max = 200)
    private String linkUrl;
}
