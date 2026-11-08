package com.cloudai.system.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.NotifyUserMessagePageQuery;
import com.cloudai.system.entity.SysNotifyUserMessage;
import com.cloudai.system.mapper.SysNotifyUserMessageMapper;
import com.cloudai.system.vo.NotifySummaryVo;
import com.cloudai.system.vo.NotifyUserMessageVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户侧消息服务单测（契约 2026-11-08-notify-api §3）：
 * summary 双值（无收件 latestId="0"）/ 3048 归属校验（不存在与非本人同文案）/ 已读幂等 / read-all 计数。
 */
@ExtendWith(MockitoExtension.class)
class NotifyUserMessageServiceTest {

    @Mock
    private SysNotifyUserMessageMapper userMessageMapper;
    @InjectMocks
    private NotifyUserMessageService service;

    @Test
    void findSummary_unreadCountAndLatestIdAsString() {
        when(userMessageMapper.countUnread(7L)).thenReturn(3L);
        when(userMessageMapper.findLatestId(7L)).thenReturn(2048L);

        NotifySummaryVo vo = service.findSummary(7L);

        // 契约 §3.1：两值均 string（前端 Number() 消费），水位 latestId 无收件="0"
        assertThat(vo.getUnreadCount()).isEqualTo("3");
        assertThat(vo.getLatestId()).isEqualTo("2048");
    }

    @Test
    void findSummary_noReceiptsReturnsZeros() {
        when(userMessageMapper.countUnread(7L)).thenReturn(0L);
        when(userMessageMapper.findLatestId(7L)).thenReturn(null);

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

        // 条件写（read_flag=0 才更新）0 行=重复已读幂等成功，不报错
        assertThatCode(() -> service.updateRead(5L, 7L, "user7")).doesNotThrowAnyException();
        verify(userMessageMapper).updateRead(5L, 7L, "user7");
    }

    @Test
    void updateRead_unreadRowMarkedRead() {
        SysNotifyUserMessage row = new SysNotifyUserMessage();
        row.setId(5L);
        row.setReadFlag(SysNotifyUserMessage.ReadFlagEnum.UNREAD.getCode());
        when(userMessageMapper.findByIdAndUser(5L, 7L)).thenReturn(row);
        when(userMessageMapper.updateRead(5L, 7L, "user7")).thenReturn(1);

        service.updateRead(5L, 7L, "user7");

        verify(userMessageMapper).updateRead(5L, 7L, "user7");
    }

    @Test
    void updateReadAll_returnsAffectedRows() {
        when(userMessageMapper.updateReadAll(7L, "user7")).thenReturn(5);

        Integer affected = service.updateReadAll(7L, "user7");

        assertThat(affected).isEqualTo(5);
    }
}
