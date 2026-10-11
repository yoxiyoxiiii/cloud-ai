package com.cloudai.system.convert;

import com.cloudai.system.entity.MqDlqLog;
import com.cloudai.system.vo.MqDlqDetailVo;
import com.cloudai.system.vo.MqDlqVo;

/**
 * 死信台账实体 → VO 转换（原生 setter 逐字段，禁三方拷贝工具）。
 * bodyPreview=body 前 120 字符+省略号（契约 §3.1）；status DB TINYINT → 契约字符串形态。
 */
public final class MqDlqConvert {

    /** 列表 body 预览长度（字符；契约 §3.1） */
    private static final int PREVIEW_CHARS = 120;

    private static final String ELLIPSIS = "...";

    private MqDlqConvert() {
    }

    public static MqDlqVo toVo(MqDlqLog log) {
        MqDlqVo vo = new MqDlqVo();
        vo.setId(log.getId());
        vo.setMsgId(log.getMsgId());
        vo.setTxNo(log.getTxNo());
        vo.setBornTopic(log.getBornTopic());
        vo.setBornGroup(log.getBornGroup());
        vo.setWatchGroup(log.getWatchGroup());
        vo.setTag(log.getTag());
        vo.setMsgKey(log.getMsgKey());
        vo.setBodyPreview(preview(log.getBody()));
        vo.setReconsumeTimes(log.getReconsumeTimes());
        vo.setBornTime(log.getBornTime());
        vo.setDlqTime(log.getDlqTime());
        vo.setStatus(log.getStatus() == null ? null : String.valueOf(log.getStatus()));
        vo.setResendCount(log.getResendCount());
        vo.setLastResendTime(log.getLastResendTime());
        vo.setOperateAccount(log.getOperateAccount());
        vo.setOperateTime(log.getOperateTime());
        return vo;
    }

    public static MqDlqDetailVo toDetailVo(MqDlqLog log) {
        MqDlqDetailVo vo = new MqDlqDetailVo();
        vo.setId(log.getId());
        vo.setMsgId(log.getMsgId());
        vo.setTxNo(log.getTxNo());
        vo.setBornTopic(log.getBornTopic());
        vo.setBornGroup(log.getBornGroup());
        vo.setWatchGroup(log.getWatchGroup());
        vo.setTag(log.getTag());
        vo.setMsgKey(log.getMsgKey());
        vo.setBodyPreview(preview(log.getBody()));
        vo.setReconsumeTimes(log.getReconsumeTimes());
        vo.setBornTime(log.getBornTime());
        vo.setDlqTime(log.getDlqTime());
        vo.setStatus(log.getStatus() == null ? null : String.valueOf(log.getStatus()));
        vo.setResendCount(log.getResendCount());
        vo.setLastResendTime(log.getLastResendTime());
        vo.setOperateAccount(log.getOperateAccount());
        vo.setOperateTime(log.getOperateTime());
        vo.setBody(log.getBody());
        vo.setBodyTruncated(log.getBody() != null
                && log.getBody().endsWith(MqDlqLog.TRUNCATED_TAIL));
        vo.setCreateTime(log.getCreateTime());
        vo.setUpdateTime(log.getUpdateTime());
        return vo;
    }

    /** body 前 120 字符（超长省略号）；null 透传 */
    private static String preview(String body) {
        if (body == null) {
            return null;
        }
        if (body.length() <= PREVIEW_CHARS) {
            return body;
        }
        return body.substring(0, PREVIEW_CHARS) + ELLIPSIS;
    }
}
