package com.cloudai.system.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * 发送通知入参（契约 2026-11-08-notify-api §2.1）。
 * 变量键空间动态（由模板内容决定），variables 为 DTO 内嵌受控 Map（键须匹配
 * ^[a-zA-Z][a-zA-Z0-9_]*$，Service 校验）——「Controller 禁 Map 接参」约束方法签名整体接参，此为明示裁量。
 * receiverType 与 userIds/roleIds 配套、linkUrl 相对路由前缀等跨字段校验在 Service（失败 1001）。
 */
@Data
public class NotifySendRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 模板ID（发送弹窗从列表选中行取；不存在 3044） */
    @NotNull
    private Long templateId;

    /** 接收人维度：1按用户 2按角色 3全员 */
    @NotNull
    private Integer receiverType;

    /** receiverType=1 时必填非空；元素去重后展开，无效用户（停用/已删）剔除 */
    private List<Long> userIds;

    /** receiverType=2 时必填非空；按角色展开为有效用户集（多角色并集去重） */
    private List<Long> roleIds;

    /** 模板变量值；缺失必填变量 3045，多余变量忽略（前向兼容），键非法 1001 */
    private Map<String, String> variables;

    /** 前端相对路由（如 /bpmn/task），非空必须以 / 开头；缺省 null=纯展示 */
    @Size(max = 200)
    private String linkUrl;
}
