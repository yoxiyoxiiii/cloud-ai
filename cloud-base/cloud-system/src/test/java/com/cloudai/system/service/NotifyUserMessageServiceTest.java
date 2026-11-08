package com.cloudai.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.NotifyUserMessagePageQuery;
import com.cloudai.system.entity.SysNotifyUserMessage;
import com.cloudai.system.entity.SysNotifyUserStat;
import com.cloudai.system.mapper.SysNotifyUserMessageMapper;
import com.cloudai.system.mapper.SysNotifyUserStatMapper;
import com.cloudai.system.vo.NotifySummaryVo;
import com.cloudai.system.vo.NotifyUserMessageVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户侧消息服务单测（契约 2026-11-08-notify-api §3）：
 * summary 读统计表单行（有行/无行两态）/ 3048 归属校验（不存在与非本人同文案）/ 已读幂等 /
 * read-all 计数 / 统计递减按实际影响行数（0 行不递减）/ 两语句写事务注解存在（反射断言）/
 * 对账 resetUnreadStat 编排序列（聚合读→清空→重灌，空列表不重灌）。
 */
@ExtendWith(MockitoExtension.class)
class NotifyUserMessageServiceTest {

    @Mock
    private SysNotifyUserMessageMapper userMessageMapper;
    @Mock
    private SysNotifyUserStatMapper statMapper;
    @InjectMocks
    private NotifyUserMessageService service;

    @Test
    void findSummary_readsStatRowAsString() {
        SysNotifyUserStat stat = new SysNotifyUserStat();
        stat.setUserId(7L);
        stat.setUnreadCount(3);
        stat.setLastReceiptId(2048L);
        when(statMapper.findByUser(7L)).thenReturn(stat);

        NotifySummaryVo vo = service.findSummary(7L);

        // 契约 §3.1：两值均 string（前端 Number() 消费），水位 latestId 无收件="0"
        assertThat(vo.getUnreadCount()).isEqualTo("3");
        assertThat(vo.getLatestId()).isEqualTo("2048");
    }

    @Test
    void findSummary_noStatRowReturnsZeros() {
        // 统计表无行=该用户无任何收件，兜底 0/0（不信 db 层走收件表回查）
        when(statMapper.findByUser(7L)).thenReturn(null);

        NotifySummaryVo vo = service.findSummary(7L);

        assertThat(vo.getUnreadCount()).isEqualTo("0");
        assertThat(vo.getLatestId()).isEqualTo("0");
    }

    @Test
    void pageList_buildsResultFromMapperPage() {
        Page<NotifyUserMessageVo> page = new Page<>(1, 10);
        NotifyUserMessageVo row = new NotifyUserMessageVo();
        row.setId(5L);
        row.setTitle("欢迎加入");
        page.setRecords(List.of(row));
        page.setTotal(1);
        when(userMessageMapper.pageList(any(Page.class), any(Long.class), any(NotifyUserMessagePageQuery.class)))
                .thenReturn(page);

        var result = service.pageList(7L, new NotifyUserMessagePageQuery());

        assertThat(result.getTotal()).isEqualTo(1);
        assertThat(result.getRows()).hasSize(1);
        assertThat(result.getRows().get(0).getTitle()).isEqualTo("欢迎加入");
    }

    @Test
    void updateRead_receiptNotFoundRejected() {
        when(userMessageMapper.findByIdAndUser(9L, 7L)).thenReturn(null);
        assertThatThrownBy(() -> service.updateRead(9L, 7L, "user7"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("收件记录不存在");
    }

    @Test
    void updateRead_otherUsersRowSameMessageAsNotFound() {
        // 契约 §3.3：非本人与不存在同一文案 3048——mapper 按 id+userId 双键定位，非本人行查不到即走同分支
        when(userMessageMapper.findByIdAndUser(5L, 7L)).thenReturn(null);
        assertThatThrownBy(() -> service.updateRead(5L, 7L, "user7"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("收件记录不存在");
    }

    @Test
    void updateRead_alreadyReadIdempotentSuccess() {
        SysNotifyUserMessage row = new SysNotifyUserMessage();
        row.setId(5L);
        row.setReadFlag(SysNotifyUserMessage.ReadFlagEnum.READ.getCode());
        when(userMessageMapper.findByIdAndUser(5L, 7L)).thenReturn(row);
        when(userMessageMapper.updateRead(5L, 7L, "user7")).thenReturn(0);

        // 条件写（read_flag=0 才更新）0 行=重复已读幂等成功，不报错；统计不递减
        assertThatCode(() -> service.updateRead(5L, 7L, "user7")).doesNotThrowAnyException();
        verify(userMessageMapper).updateRead(5L, 7L, "user7");
        verify(statMapper, never()).updateDecrease(anyLong(), anyInt());
    }

    @Test
    void updateRead_unreadRowMarkedReadAndStatDecreased() {
        SysNotifyUserMessage row = new SysNotifyUserMessage();
        row.setId(5L);
        row.setReadFlag(SysNotifyUserMessage.ReadFlagEnum.UNREAD.getCode());
        when(userMessageMapper.findByIdAndUser(5L, 7L)).thenReturn(row);
        when(userMessageMapper.updateRead(5L, 7L, "user7")).thenReturn(1);

        service.updateRead(5L, 7L, "user7");

        verify(userMessageMapper).updateRead(5L, 7L, "user7");
        // 递减按实际影响行数（1 行已读=未读数-1）
        verify(statMapper).updateDecrease(7L, 1);
    }

    @Test
    void updateReadAll_returnsAffectedRowsAndDecreasesStat() {
        when(userMessageMapper.updateReadAll(7L, "user7")).thenReturn(5);

        Integer affected = service.updateReadAll(7L, "user7");

        assertThat(affected).isEqualTo(5);
        verify(statMapper).updateDecrease(7L, 5);
    }

    @Test
    void updateReadAll_zeroAffectedNoDecrease() {
        when(userMessageMapper.updateReadAll(7L, "user7")).thenReturn(0);

        Integer affected = service.updateReadAll(7L, "user7");

        assertThat(affected).isZero();
        verify(statMapper, never()).updateDecrease(anyLong(), anyInt());
    }

    @Test
    void readPaths_declareTransactionalOnTwoStatementWrites() throws Exception {
        // 已读/全部已读现为两语句写（收件表条件写 + 统计表递减）——必须 @Transactional(rollbackFor)
        Transactional single = NotifyUserMessageService.class
                .getMethod("updateRead", Long.class, Long.class, String.class)
                .getAnnotation(Transactional.class);
        Transactional all = NotifyUserMessageService.class
                .getMethod("updateReadAll", Long.class, String.class)
                .getAnnotation(Transactional.class);
        assertThat(single).isNotNull();
        assertThat(single.rollbackFor()).contains(Exception.class);
        assertThat(all).isNotNull();
        assertThat(all.rollbackFor()).contains(Exception.class);
    }

    @Test
    void resetUnreadStat_rebuildsInOrderAggDeleteReinsert() {
        // 对账编排序列：聚合读（真相源）→ 清空 → 重灌（同一事务原子），返回重建行数
        SysNotifyUserStat agg1 = new SysNotifyUserStat();
        agg1.setUserId(1L);
        agg1.setUnreadCount(2);
        agg1.setLastReceiptId(4002L);
        SysNotifyUserStat agg2 = new SysNotifyUserStat();
        agg2.setUserId(2L);
        agg2.setUnreadCount(0);
        agg2.setLastReceiptId(4005L);
        List<SysNotifyUserStat> aggs = List.of(agg1, agg2);
        when(userMessageMapper.listUnreadAgg()).thenReturn(aggs);

        int rows = service.resetUnreadStat();

        assertThat(rows).isEqualTo(2);
        InOrder inOrder = inOrder(userMessageMapper, statMapper);
        inOrder.verify(userMessageMapper).listUnreadAgg();
        inOrder.verify(statMapper).deleteAll();
        inOrder.verify(statMapper).saveBatch(aggs);
    }

    @Test
    void resetUnreadStat_emptyAggSkipsSaveBatch() {
        // 收件表已清空（如测试数据自清后）：聚合零行——清空统计后不调重灌，返回 0
        when(userMessageMapper.listUnreadAgg()).thenReturn(List.of());

        int rows = service.resetUnreadStat();

        assertThat(rows).isZero();
        verify(statMapper).deleteAll();
        verify(statMapper, never()).saveBatch(anyList());
    }

    @Test
    void resetUnreadStat_declaresTransactionalOnMultiStatementWrite() throws Exception {
        // 清空+重灌是多语句写——必须 @Transactional(rollbackFor)，失败整体回滚
        Transactional tx = NotifyUserMessageService.class
                .getMethod("resetUnreadStat")
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }
}
