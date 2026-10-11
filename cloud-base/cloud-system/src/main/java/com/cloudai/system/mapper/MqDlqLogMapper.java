package com.cloudai.system.mapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.system.dto.MqDlqPageQueryDto;
import com.cloudai.system.entity.MqDlqLog;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;

/**
 * MQ 死信治理台账 SQL（XML：mapper/MqDlqLogMapper.xml）。
 * 无 deleted 列（设计 D5：沿 mq_tx_log/mq_consume_dedup 先例，MQ 审计表物理清理），
 * 故语句不带 deleted 条件；处置审计（operate_account/operate_time/update_time）由 Service 显式传参。
 */
public interface MqDlqLogMapper {

    /** 单查全列（重投/忽略/重置前的行校验与详情） */
    MqDlqLog findDlqLogById(@Param("id") Long id);

    /** 分页查询（XML 不写 LIMIT，由 PaginationInnerInterceptor 追加；dlq_time 倒序） */
    IPage<MqDlqLog> pageListDlqLog(Page<MqDlqLog> page, @Param("query") MqDlqPageQueryDto query);

    /** watch 落库插入（全列显式；uk_msg_id 冲突由 Service catch DuplicateKeyException 吸收） */
    int saveDlqLog(MqDlqLog log);

    /** 重投成功后行流转：status=1、resend_count+1、last_resend_time 与处置审计两值（WHERE id） */
    int updateResend(@Param("id") Long id,
                     @Param("operateAccount") String operateAccount,
                     @Param("operateTime") LocalDateTime operateTime);

    /** 处置状态流转（忽略/自动置弃共用；resend_count 保持；WHERE id） */
    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("operateAccount") String operateAccount,
                     @Param("operateTime") LocalDateTime operateTime);

    /** 解除终态：status=0、resend_count 清零（设计 D6：不清零回 0 立即再触上限失去重置意义） */
    int updateReset(@Param("id") Long id,
                    @Param("operateAccount") String operateAccount,
                    @Param("operateTime") LocalDateTime operateTime);

    /** 清理任务范围删除（status IN (1,2,3) AND dlq_time &lt; cutoff——待处理行永不清，设计 D10） */
    int deleteExpiredDlqLog(@Param("cutoff") LocalDateTime cutoff);
}
