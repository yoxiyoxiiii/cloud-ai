package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.rocketmq.dao.ConsumeDedupDao;
import com.cloudai.common.rocketmq.tx.TxMessageSenderImpl;
import com.cloudai.common.security.util.SecurityUtils;
import com.cloudai.system.config.CloudSystemMqDlqProperties;
import com.cloudai.system.convert.MqDlqConvert;
import com.cloudai.system.dto.MqDlqPageQueryDto;
import com.cloudai.system.entity.MqDlqLog;
import com.cloudai.system.mapper.MqDlqLogMapper;
import com.cloudai.system.vo.MqDlqDetailVo;
import com.cloudai.system.vo.MqDlqVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

/**
 * MQ 死信治理服务（契约 2026-10-11-mq-dlq-governance-api §3 / 设计 D6/D7/D8）。
 * 重投编排不包 @Transactional（设计 D7 记档例外）：中间夹 MQ 发送外部调用本不可原子化，
 * 三步各自独立幂等——清 dedup 行删 0 行无害、发送失败行不动、发送成功后行更新失败=可再点一次
 * （至多重发一次，消费端 L1/L2 幂等层兜底），最坏情况有界且无害。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqDlqLogService {

    /** 错误码 system 段接续（前账至 3037，契约 §6） */
    private static final int ERR_DLQ_NOT_FOUND = 3038;
    private static final int ERR_RESEND_SEND_FAILED = 3039;
    private static final int ERR_DLQ_STATUS_NOT_ALLOWED = 3040;
    private static final int ERR_RESEND_LIMIT_EXCEEDED = 3041;

    private final MqDlqLogMapper dlqLogMapper;
    private final ConsumeDedupDao consumeDedupDao;
    private final RocketMQTemplate rocketMQTemplate;
    private final CloudSystemMqDlqProperties properties;

    /** watch 落库：uk_msg_id 冲突（broker 重投）log.info 吸收 ACK——至多落一行 */
    public void saveWatched(MqDlqLog dlq) {
        try {
            dlqLogMapper.saveDlqLog(dlq);
        } catch (DuplicateKeyException e) {
            log.info("watch 重复投递幂等吸收: group={}, msgId={}", dlq.getWatchGroup(), dlq.getMsgId());
        }
    }

    /** 只读分页（dlq_time 倒序；列表不返 body，详情取） */
    public PageResult<MqDlqVo> pageList(MqDlqPageQueryDto query) {
        IPage<MqDlqLog> page = dlqLogMapper.pageListDlqLog(
                new Page<>(query.getPageNum(), query.getPageSize()), query);
        List<MqDlqVo> rows = page.getRecords().stream().map(MqDlqConvert::toVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    /** 详情（含 body 原文与截断标记；行缺失 3038） */
    public MqDlqDetailVo findDetail(Long id) {
        MqDlqLog dlq = requireDlqLog(id);
        return MqDlqConvert.toDetailVo(dlq);
    }

    /**
     * 人工重投（设计 D7 三步，高危——前端必须二次确认）：
     * ① 状态校验（0/1 可重投；终态 3040；resend_count 达上限自动置弃 + 3041）
     * ② 清 dedup 残留行 (born_group, msg_key)——防"假重投"（残留行会 ACK 吞掉重投消息）；
     *    删 0 行无害（L2 路径本无 dedup 行）
     * ③ syncSend 原 topic（恢复 tag/KEYS/TX_NO/body，新 msgId 重计重试）→ 成功后行流转留痕。
     */
    public void updateResend(Long id) {
        MqDlqLog dlq = requireDlqLog(id);
        if (dlq.getStatus() == MqDlqLog.StatusEnum.IGNORED.getCode()
                || dlq.getStatus() == MqDlqLog.StatusEnum.ABANDONED.getCode()) {
            throw new BusinessException(ERR_DLQ_STATUS_NOT_ALLOWED, "死信状态不允许该操作: 终态不可重投");
        }
        if (dlq.getResendCount() >= properties.getResendMax()) {
            LocalDateTime now = LocalDateTime.now();
            dlqLogMapper.updateStatus(id, MqDlqLog.StatusEnum.ABANDONED.getCode(),
                    SecurityUtils.currentAccount(), now);
            throw new BusinessException(ERR_RESEND_LIMIT_EXCEEDED, "重投次数已达上限，已置为已放弃");
        }
        consumeDedupDao.delete(dlq.getBornGroup(), dlq.getMsgKey());
        try {
            sendResend(dlq);
        } catch (Exception e) {
            log.error("死信重投发送失败: id={}, topic={}, msgKey={}", id, dlq.getBornTopic(), dlq.getMsgKey(), e);
            throw new BusinessException(ERR_RESEND_SEND_FAILED, "消息重投发送失败");
        }
        LocalDateTime now = LocalDateTime.now();
        dlqLogMapper.updateResend(id, SecurityUtils.currentAccount(), now);
    }

    /** 人工忽略：仅待处理（0）可忽略——已重投行语义互斥（设计 D6）；终态留痕 */
    public void updateIgnore(Long id) {
        MqDlqLog dlq = requireDlqLog(id);
        if (dlq.getStatus() != MqDlqLog.StatusEnum.PENDING.getCode()) {
            throw new BusinessException(ERR_DLQ_STATUS_NOT_ALLOWED, "死信状态不允许该操作: 仅待处理可忽略");
        }
        dlqLogMapper.updateStatus(id, MqDlqLog.StatusEnum.IGNORED.getCode(),
                SecurityUtils.currentAccount(), LocalDateTime.now());
    }

    /** 解除终态（admin，设计 D8）：已忽略/已放弃 → 待处理，resend_count 清零（覆盖误忽略恢复） */
    public void updateReset(Long id) {
        MqDlqLog dlq = requireDlqLog(id);
        if (dlq.getStatus() != MqDlqLog.StatusEnum.IGNORED.getCode()
                && dlq.getStatus() != MqDlqLog.StatusEnum.ABANDONED.getCode()) {
            throw new BusinessException(ERR_DLQ_STATUS_NOT_ALLOWED, "死信状态不允许该操作: 仅终态可重置");
        }
        dlqLogMapper.updateReset(id, SecurityUtils.currentAccount(), LocalDateTime.now());
    }

    /** 重发原 topic：destination=tag 非空拼 topic:tag；msg_key≠msg_id 即原始 KEYS 才恢复 header；
     *  TX_NO 非空才恢复（userProperty 透传，消费端零感知消息为重投——设计 D7 完整度口径） */
    private void sendResend(MqDlqLog dlq) {
        byte[] payload = dlq.getBody() == null ? new byte[0] : dlq.getBody().getBytes(StandardCharsets.UTF_8);
        MessageBuilder<byte[]> builder = MessageBuilder.withPayload(payload);
        if (!dlq.getMsgKey().equals(dlq.getMsgId())) {
            builder.setHeader(TxMessageSenderImpl.HEADER_KEYS, dlq.getMsgKey());
        }
        if (dlq.getTxNo() != null && !dlq.getTxNo().isBlank()) {
            builder.setHeader(TxMessageSenderImpl.HEADER_TX_NO, dlq.getTxNo());
        }
        String destination = dlq.getTag() == null || dlq.getTag().isBlank()
                ? dlq.getBornTopic()
                : dlq.getBornTopic() + ":" + dlq.getTag();
        Message<byte[]> message = builder.build();
        rocketMQTemplate.syncSend(destination, message);
    }

    private MqDlqLog requireDlqLog(Long id) {
        MqDlqLog dlq = dlqLogMapper.findDlqLogById(id);
        if (dlq == null) {
            throw new BusinessException(ERR_DLQ_NOT_FOUND, "死信记录不存在: " + id);
        }
        return dlq;
    }
}
