package com.cloudai.system.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.dto.NotifySendRequest;
import com.cloudai.system.entity.SysNotifyMessage;
import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.entity.SysNotifyUserMessage;
import com.cloudai.system.mapper.SysNotifyMessageMapper;
import com.cloudai.system.mapper.SysNotifyTemplateMapper;
import com.cloudai.system.mapper.SysNotifyUserMessageMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.util.NotifyTemplateParser;
import com.cloudai.system.vo.NotifySendResultVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 通知发送门面（契约 2026-11-08-notify-api §2.1 / 通知中心设计 §4）：
 * 解析模板 → 校验变量 → 三档展开收件人 → 消息主表 1 行 + 收件 N 行同事务落库。
 * 消息内容为解析后快照（与模板此后变更解耦）；按角色/全员为发送时点快照（之后入离职不回溯）。
 * 未来 inner Feign 发送接口（按 templateCode）直接复用本门面（契约 §6 预留）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotifySendService {

    /** 错误码分段：3xxx system，通知域 3043-3049（契约 §4）；1xxx 参数校验失败沿用全局 1001 */
    private static final int ERR_PARAM_INVALID = 1001;
    private static final int ERR_TEMPLATE_NOT_FOUND = 3044;
    private static final int ERR_VARIABLE_MISSING = 3045;
    private static final int ERR_CONTENT_TOO_LONG = 3046;
    private static final int ERR_RECEIVER_EMPTY = 3047;

    /** 解析后长度上限 = 消息表列宽（title VARCHAR(100) / content VARCHAR(1000)，超长 3046 不做静默截断） */
    private static final int TITLE_MAX_LEN = 100;
    private static final int CONTENT_MAX_LEN = 1000;

    /** 收件批量插入分批上限（foreach 单语句行数上限，防超长 SQL） */
    private static final int BATCH_SIZE = 500;

    /** 变量键合法字符集（与模板占位符变量名同规则，契约 §2.1） */
    private static final Pattern VARIABLE_KEY = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*$");

    private final SysNotifyTemplateMapper templateMapper;
    private final SysNotifyMessageMapper messageMapper;
    private final SysNotifyUserMessageMapper userMessageMapper;
    private final SysUserMapper userMapper;

    /**
     * 发送通知（多表写：消息主表 1 行 + 收件 N 行，@Transactional 保证原子）。
     * 校验顺序：1001 参数配套 → 3044 模板 → 3045 变量缺失 → 3046 解析超长 → 3047 收件人空。
     */
    @Transactional(rollbackFor = Exception.class)
    public NotifySendResultVo saveNotify(NotifySendRequest req) {
        assertRequestValid(req);
        SysNotifyTemplate template = requireTemplate(req.getTemplateId());
        Map<String, String> variables = req.getVariables() == null ? Map.of() : req.getVariables();
        RenderedMessage rendered = renderTemplate(template, variables);
        List<Long> receiverIds = expandReceivers(req);
        if (receiverIds.isEmpty()) {
            throw new BusinessException(ERR_RECEIVER_EMPTY, "接收人列表为空");
        }
        SysNotifyMessage message = saveMessage(template, req, rendered, receiverIds.size());
        saveReceipts(message.getId(), receiverIds);
        NotifySendResultVo vo = new NotifySendResultVo();
        vo.setMessageId(message.getId());
        vo.setReceiverCount(receiverIds.size());
        return vo;
    }

    /** 前置校验：三档与 id 列表配套、linkUrl 相对路由约束、变量键字符集（均 1001） */
    private void assertRequestValid(NotifySendRequest req) {
        Integer receiverType = req.getReceiverType();
        boolean knownType = receiverType != null && (receiverType == SysNotifyMessage.ReceiverTypeEnum.USER.getCode()
                || receiverType == SysNotifyMessage.ReceiverTypeEnum.ROLE.getCode()
                || receiverType == SysNotifyMessage.ReceiverTypeEnum.ALL.getCode());
        if (!knownType) {
            throw new BusinessException(ERR_PARAM_INVALID, "接收人维度必须为 1按用户 2按角色 3全员");
        }
        if (receiverType == SysNotifyMessage.ReceiverTypeEnum.USER.getCode()
                && (req.getUserIds() == null || req.getUserIds().isEmpty())) {
            throw new BusinessException(ERR_PARAM_INVALID, "receiverType=1 按用户发送时 userIds 必填非空");
        }
        if (receiverType == SysNotifyMessage.ReceiverTypeEnum.ROLE.getCode()
                && (req.getRoleIds() == null || req.getRoleIds().isEmpty())) {
            throw new BusinessException(ERR_PARAM_INVALID, "receiverType=2 按角色发送时 roleIds 必填非空");
        }
        if (req.getLinkUrl() != null && !req.getLinkUrl().isBlank() && !req.getLinkUrl().startsWith("/")) {
            throw new BusinessException(ERR_PARAM_INVALID, "linkUrl 必须为以 / 开头的前端相对路由");
        }
        if (req.getVariables() != null) {
            req.getVariables().keySet().stream()
                    .filter(key -> !VARIABLE_KEY.matcher(key).matches())
                    .findAny()
                    .ifPresent(key -> {
                        throw new BusinessException(ERR_PARAM_INVALID, "变量键非法（须字母开头、字母数字下划线）: " + key);
                    });
        }
    }

    private SysNotifyTemplate requireTemplate(Long templateId) {
        SysNotifyTemplate template = templateMapper.findById(templateId);
        if (template == null) {
            throw new BusinessException(ERR_TEMPLATE_NOT_FOUND, "模板不存在");
        }
        return template;
    }

    /** 变量校验 + 渲染：缺失必填变量 3045（msg 列缺失名，多余忽略）；解析后超列宽 3046 */
    private RenderedMessage renderTemplate(SysNotifyTemplate template, Map<String, String> variables) {
        Set<String> required = NotifyTemplateParser.extractVariables(
                template.getTitleTemplate(), template.getContentTemplate());
        List<String> missing = required.stream().filter(v -> !variables.containsKey(v)).toList();
        if (!missing.isEmpty()) {
            throw new BusinessException(ERR_VARIABLE_MISSING, "模板变量缺失: " + String.join(",", missing));
        }
        String title = NotifyTemplateParser.render(template.getTitleTemplate(), variables);
        String content = NotifyTemplateParser.render(template.getContentTemplate(), variables);
        if (title.length() > TITLE_MAX_LEN || content.length() > CONTENT_MAX_LEN) {
            throw new BusinessException(ERR_CONTENT_TOO_LONG, "解析后标题或内容超出长度限制");
        }
        return new RenderedMessage(title, content);
    }

    /** 三档收件人展开（LinkedHashSet 去重，发送时点快照）：按用户=过滤无效；按角色=角色→有效用户；全员=全部有效 */
    private List<Long> expandReceivers(NotifySendRequest req) {
        Integer receiverType = req.getReceiverType();
        Set<Long> receiverIds = new LinkedHashSet<>();
        if (receiverType == SysNotifyMessage.ReceiverTypeEnum.USER.getCode()) {
            Set<Long> activeIds = new LinkedHashSet<>(userMapper.listActiveUserIds());
            req.getUserIds().stream().filter(activeIds::contains).forEach(receiverIds::add);
        } else if (receiverType == SysNotifyMessage.ReceiverTypeEnum.ROLE.getCode()) {
            receiverIds.addAll(userMapper.listUserIdsByRoleIds(req.getRoleIds()));
        } else {
            receiverIds.addAll(userMapper.listActiveUserIds());
        }
        return new ArrayList<>(receiverIds);
    }

    /** 消息主表落一行：解析后文案 + 模板 type/level + linkUrl/receiverType/receiverCount 快照 */
    private SysNotifyMessage saveMessage(SysNotifyTemplate template, NotifySendRequest req,
                                         RenderedMessage rendered, int receiverCount) {
        SysNotifyMessage message = new SysNotifyMessage();
        message.setTemplateId(template.getId());
        message.setTitle(rendered.title());
        message.setContent(rendered.content());
        message.setType(template.getType());
        message.setLevel(template.getLevel());
        message.setLinkUrl(req.getLinkUrl());
        message.setReceiverType(req.getReceiverType());
        message.setReceiverCount(receiverCount);
        message.setCreateBy(SecurityUtils.currentAccount());
        messageMapper.save(message);
        return message;
    }

    /** 收件行批量落库（500/批，create_by 统一=发送人）；uk 防御层冲突吸收（发送前已去重，常态不触发） */
    private void saveReceipts(Long messageId, List<Long> receiverIds) {
        String sender = SecurityUtils.currentAccount();
        for (int start = 0; start < receiverIds.size(); start += BATCH_SIZE) {
            List<Long> batch = receiverIds.subList(start, Math.min(start + BATCH_SIZE, receiverIds.size()));
            List<SysNotifyUserMessage> rows = new ArrayList<>(batch.size());
            for (Long userId : batch) {
                SysNotifyUserMessage row = new SysNotifyUserMessage();
                row.setMessageId(messageId);
                row.setUserId(userId);
                row.setCreateBy(sender);
                rows.add(row);
            }
            try {
                userMessageMapper.saveBatch(rows);
            } catch (DuplicateKeyException e) {
                log.error("收件行唯一键冲突（uk_message_user 防御层，本批部分跳过）: messageId={}", messageId, e);
            }
        }
    }

    /** 渲染结果载体（标题/内容快照对） */
    private record RenderedMessage(String title, String content) {
    }
}
