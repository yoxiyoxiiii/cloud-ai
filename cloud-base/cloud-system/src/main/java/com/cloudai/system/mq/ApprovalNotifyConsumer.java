package com.cloudai.system.mq;

import com.cloudai.bpmn.api.domain.ApprovalNotifyMessage;
import com.cloudai.bpmn.api.mq.ApprovalMqTopics;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.consume.DedupRocketMQListener;
import com.cloudai.common.rocketmq.consume.JsonPayloads;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.cloudai.system.dto.NotifySendRequest;
import com.cloudai.system.entity.SysNotifyMessage.ReceiverTypeEnum;
import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.entity.SysUser;
import com.cloudai.system.mapper.SysNotifyTemplateMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.service.NotifySendService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 审批任务通知消费（契约 2026-11-08-approval-notify-mq-api §1.4，topic BPMN_TASK_NOTIFY：
 * 任务到达 TASK_ARRIVED → 收件人待办提醒；审批结果 TERMINAL_RESULT → 申请人结果通知）。
 * L1 去重表幂等（KEYS 即消息键：任务通知含 taskId 精确到任务实例，终态每审批单一次）。
 * 消费失败三分类：确定性业务失败（收件人无效停用/模板缺失/变量异常/terminalStatus 未知）
 * log.error 记根因后 ACK（重试结果一致，模板修复后新消息生效）；其余系统态抛出走重试 ×3
 * → %DLQ%g_system_notify 死信 watch（MqDlqWatchNotifyListener）人工处置。
 * @author jy-root
 */
@Slf4j
@Component
@RocketMQMessageListener(topic = ApprovalMqTopics.TOPIC_BPMN_TASK_NOTIFY,
        consumerGroup = ApprovalNotifyConsumer.GROUP, maxReconsumeTimes = 3)
public class ApprovalNotifyConsumer extends DedupRocketMQListener {

    /** 消费组（与 dedupGroup 同源——L1 去重表 group 列与订阅组名恒一致） */
    static final String GROUP = "g_system_notify";

    private static final String TEMPLATE_TASK_ARRIVED = "approval_remind";
    private static final String TEMPLATE_TERMINAL_RESULT = "approval_result";
    private static final String LINK_TASK = "/bpmn/task";
    private static final String LINK_APPROVAL = "/bpmn/approval";
    /** 审计操作人：消费域无登录态，站内信 create_by 统一系统账号 */
    private static final String OPERATOR = "bpmn-notify";

    /** 终态码（契约 §1.2 wire 值；cloud-bpmn 的状态枚举不在本模块依赖面） */
    private static final String STATUS_APPROVED = "1";
    private static final String STATUS_REJECTED = "2";
    private static final String STATUS_CANCELLED = "3";

    private final JsonPayloads jsonPayloads;
    private final SysUserMapper userMapper;
    private final SysNotifyTemplateMapper templateMapper;
    private final NotifySendService sendService;

    public ApprovalNotifyConsumer(ConsumeDedupDao consumeDedupDao, JsonPayloads jsonPayloads,
                                  SysUserMapper userMapper, SysNotifyTemplateMapper templateMapper,
                                  NotifySendService sendService) {
        super(consumeDedupDao);
        this.jsonPayloads = jsonPayloads;
        this.userMapper = userMapper;
        this.templateMapper = templateMapper;
        this.sendService = sendService;
    }

    @Override
    protected String dedupGroup() {
        return GROUP;
    }

    @Override
    protected void doConsume(MessageExt msg) throws Exception {
        // 解析失败=契约违例（系统态）：不在下方 catch 面内，抛出走重试
        ApprovalNotifyMessage message = jsonPayloads.parse(msg.getBody(), ApprovalNotifyMessage.class);
        try {
            switch (message.getNotifyType()) {
                case ApprovalMqTopics.TAG_TASK_ARRIVED -> sendTaskArrived(message);
                case ApprovalMqTopics.TAG_TERMINAL_RESULT -> sendTerminalResult(message);
                default -> throw new IllegalArgumentException("未知通知类型: " + message.getNotifyType());
            }
        } catch (BusinessException e) {
            // 确定性业务失败：重试结果一致——记根因后 ACK（模板/账号修复后新消息生效）
            log.error("审批任务通知确定性失败（ACK）: keys={}, notifyType={}",
                    msg.getKeys(), message.getNotifyType(), e);
        }
    }

    /** 任务到达 → 收件人待办提醒（变量组装按契约 §1.4 表逐字） */
    private void sendTaskArrived(ApprovalNotifyMessage message) {
        SysUser receiver = requireActiveReceiver(message.getReceiverAccount());
        SysNotifyTemplate template = requireTemplate(TEMPLATE_TASK_ARRIVED);
        Map<String, String> variables = new HashMap<>();
        variables.put("nickname", receiver.getNickname());
        variables.put("applyUserName", applyUserDisplayName(message));
        variables.put("title", message.getTitle());
        variables.put("businessTypeName", message.getBusinessTypeName());
        variables.put("taskName", message.getTaskName());
        sendService.saveNotify(buildRequest(template, receiver, variables, LINK_TASK), OPERATOR);
    }

    /** 审批结果 → 申请人结果通知（resultText 映射 1/2/3 → 已通过/已拒绝/已撤销） */
    private void sendTerminalResult(ApprovalNotifyMessage message) {
        SysUser receiver = requireActiveReceiver(message.getReceiverAccount());
        SysNotifyTemplate template = requireTemplate(TEMPLATE_TERMINAL_RESULT);
        Map<String, String> variables = new HashMap<>();
        variables.put("nickname", receiver.getNickname());
        variables.put("title", message.getTitle());
        variables.put("businessTypeName", message.getBusinessTypeName());
        variables.put("resultText", resultText(message.getTerminalStatus()));
        sendService.saveNotify(buildRequest(template, receiver, variables, LINK_APPROVAL), OPERATOR);
    }

    /** 收件人解析（本库 findByAccount，零跨服务）：行不在/已停用=确定性失败（异常由外层捕获 ACK） */
    private SysUser requireActiveReceiver(String account) {
        SysUser user = userMapper.findByAccount(account);
        if (user == null || !Integer.valueOf(SysUser.StatusEnum.NORMAL.getCode()).equals(user.getStatus())) {
            throw new BusinessException("通知收件人不存在或已停用: " + account);
        }
        return user;
    }

    /** 模板按 code 定位（消费域无 templateId）：缺失（种子未落库或被误删）=确定性失败 3044 */
    private SysNotifyTemplate requireTemplate(String code) {
        SysNotifyTemplate template = templateMapper.findByCode(code);
        if (template == null) {
            throw new BusinessException(3044, "通知模板缺失: " + code);
        }
        return template;
    }

    /** 申请人昵称（任务提醒文案需要）：账号已删回退账号展示并留痕——历史单据长尾不阻断通知 */
    private String applyUserDisplayName(ApprovalNotifyMessage message) {
        SysUser applyUser = userMapper.findByAccount(message.getApplyUser());
        if (applyUser == null) {
            log.warn("申请人账号不存在，回退账号展示: applyUser={}", message.getApplyUser());
            return message.getApplyUser();
        }
        return applyUser.getNickname() == null || applyUser.getNickname().isBlank()
                ? message.getApplyUser() : applyUser.getNickname();
    }

    /** 终态码 → 结果文案：未知值=确定性失败（契约违例但重试无意义，异常由外层捕获 ACK） */
    private String resultText(String terminalStatus) {
        return switch (terminalStatus) {
            case STATUS_APPROVED -> "已通过";
            case STATUS_REJECTED -> "已拒绝";
            case STATUS_CANCELLED -> "已撤销";
            default -> throw new BusinessException("terminalStatus 未知: " + terminalStatus);
        };
    }

    /** 单收件人发送请求（receiverType=1 按用户，站内信全链路走既有门面事务） */
    private NotifySendRequest buildRequest(SysNotifyTemplate template, SysUser receiver,
                                           Map<String, String> variables, String linkUrl) {
        NotifySendRequest req = new NotifySendRequest();
        req.setTemplateId(template.getId());
        req.setReceiverType(ReceiverTypeEnum.USER.getCode());
        req.setUserIds(List.of(receiver.getId()));
        req.setVariables(variables);
        req.setLinkUrl(linkUrl);
        return req;
    }
}
