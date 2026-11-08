package com.cloudai.system.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.system.dto.NotifySendRequest;
import com.cloudai.system.entity.SysNotifyMessage;
import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.entity.SysNotifyUserMessage;
import com.cloudai.system.entity.SysNotifyUserStat;
import com.cloudai.system.mapper.SysNotifyMessageMapper;
import com.cloudai.system.mapper.SysNotifyTemplateMapper;
import com.cloudai.system.mapper.SysNotifyUserMessageMapper;
import com.cloudai.system.mapper.SysNotifyUserStatMapper;
import com.cloudai.system.mapper.SysUserMapper;
import com.cloudai.system.vo.NotifySendResultVo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发送门面单测（契约 2026-11-08-notify-api §2.1 / §4）：
 * 1001 参数分支（三档配套/linkUrl/变量键）/ 3044→3045→3046→3047 校验顺序 / 三档展开
 * （无效用户过滤+去重、ROLE、ALL）/ 批量分批 500 / DuplicateKey 吸收 / 事务注解存在（反射断言）/
 * 统计表 Java 编排维护（按用户分组递增、水位=该用户批内最大收件行 id、
 * 统计行缺失走 save 建行、并发首建撞键重试递增、冲突批统计跳过）。
 */
@ExtendWith(MockitoExtension.class)
class NotifySendServiceTest {

    private static final int BATCH_SIZE = 500;

    @Mock
    private SysNotifyTemplateMapper templateMapper;
    @Mock
    private SysNotifyMessageMapper messageMapper;
    @Mock
    private SysNotifyUserMessageMapper userMessageMapper;
    @Mock
    private SysNotifyUserStatMapper statMapper;
    @Mock
    private SysUserMapper userMapper;
    @InjectMocks
    private NotifySendService service;

    /** 含 ${nickname} 变量的提醒模板（种子 approval_remind 形态） */
    private SysNotifyTemplate templateWithVar() {
        SysNotifyTemplate t = new SysNotifyTemplate();
        t.setId(2L);
        t.setCode("approval_remind");
        t.setTitleTemplate("审批待办提醒");
        t.setContentTemplate("您好 ${nickname}，您有一条新的审批任务待处理。");
        t.setType(SysNotifyTemplate.TypeEnum.REMIND.getCode());
        t.setLevel(SysNotifyTemplate.LevelEnum.IMPORTANT.getCode());
        t.setIsBuiltin(SysNotifyTemplate.BuiltinEnum.BUILT_IN.getCode());
        return t;
    }

    /** 全员请求（默认走通路径；variables 覆盖模板变量） */
    private NotifySendRequest allRequest() {
        NotifySendRequest req = new NotifySendRequest();
        req.setTemplateId(2L);
        req.setReceiverType(SysNotifyMessage.ReceiverTypeEnum.ALL.getCode());
        req.setVariables(Map.of("nickname", "张三"));
        return req;
    }

    private void stubTemplateFound() {
        when(templateMapper.findById(2L)).thenReturn(templateWithVar());
    }

    @Test
    void sendFacade_declaresTransactionalOnMultiTableWrite() throws Exception {
        // 多表写（消息主表 + 收件 N 行）必须 @Transactional(rollbackFor = Exception.class)——事务口径
        Transactional tx = NotifySendService.class
                .getMethod("saveNotify", NotifySendRequest.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }

    @Test
    void saveNotify_userModeWithoutUserIdsRejected() {
        NotifySendRequest req = allRequest();
        req.setReceiverType(SysNotifyMessage.ReceiverTypeEnum.USER.getCode());
        req.setUserIds(List.of());
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("userIds");
    }

    @Test
    void saveNotify_roleModeWithoutRoleIdsRejected() {
        NotifySendRequest req = allRequest();
        req.setReceiverType(SysNotifyMessage.ReceiverTypeEnum.ROLE.getCode());
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("roleIds");
    }

    @Test
    void saveNotify_unknownReceiverTypeRejected() {
        NotifySendRequest req = allRequest();
        req.setReceiverType(9);
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("接收人维度");
    }

    @Test
    void saveNotify_linkUrlWithoutLeadingSlashRejected() {
        NotifySendRequest req = allRequest();
        req.setLinkUrl("bpmn/task");
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("linkUrl");
    }

    @Test
    void saveNotify_invalidVariableKeyRejected() {
        NotifySendRequest req = allRequest();
        req.setVariables(Map.of("1bad", "x"));
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("变量");
    }

    @Test
    void saveNotify_templateNotFoundRejected() {
        when(templateMapper.findById(9L)).thenReturn(null);
        NotifySendRequest req = allRequest();
        req.setTemplateId(9L);
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板不存在");
    }

    @Test
    void saveNotify_missingVariableRejectedWithNames() {
        stubTemplateFound();
        NotifySendRequest req = allRequest();
        req.setVariables(Map.of());
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("模板变量缺失")
                .hasMessageContaining("nickname");
    }

    @Test
    void saveNotify_renderedTooLongRejected() {
        SysNotifyTemplate t = templateWithVar();
        t.setContentTemplate("${payload}");
        when(templateMapper.findById(2L)).thenReturn(t);
        NotifySendRequest req = allRequest();
        req.setVariables(Map.of("payload", "x".repeat(1001)));
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超出长度限制");
    }

    @Test
    void saveNotify_emptyReceiversRejected() {
        stubTemplateFound();
        NotifySendRequest req = allRequest();
        when(userMapper.listActiveUserIds()).thenReturn(List.of());
        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("接收人列表为空");
        verify(messageMapper, never()).save(any(SysNotifyMessage.class));
    }

    @Test
    void saveNotify_userModeDedupAndInvalidUserFiltered() {
        stubTemplateFound();
        when(userMapper.listActiveUserIds()).thenReturn(List.of(1L, 2L));
        NotifySendRequest req = allRequest();
        req.setReceiverType(SysNotifyMessage.ReceiverTypeEnum.USER.getCode());
        // 4 入参去重后 3 个，再过滤无效用户（3 停用/已删）剩 2：1 与 1 重复、3 不在有效集
        req.setUserIds(List.of(1L, 1L, 2L, 3L));

        NotifySendResultVo result = service.saveNotify(req);

        assertThat(result.getReceiverCount()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SysNotifyUserMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(userMessageMapper).saveBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()).extracting(SysNotifyUserMessage::getUserId).containsExactly(1L, 2L);
    }

    @Test
    void saveNotify_roleModeExpandsByRoleIds() {
        stubTemplateFound();
        when(userMapper.listUserIdsByRoleIds(List.of(2L))).thenReturn(List.of(1L, 2L, 3L));
        NotifySendRequest req = allRequest();
        req.setReceiverType(SysNotifyMessage.ReceiverTypeEnum.ROLE.getCode());
        req.setRoleIds(List.of(2L));

        NotifySendResultVo result = service.saveNotify(req);

        assertThat(result.getReceiverCount()).isEqualTo(3);
        verify(userMessageMapper).saveBatch(anyList());
    }

    @Test
    void saveNotify_allModeSnapshotsMessageAndReceipts() {
        stubTemplateFound();
        when(userMapper.listActiveUserIds()).thenReturn(List.of(1L, 2L));
        doAnswer(inv -> {
            SysNotifyMessage m = inv.getArgument(0);
            m.setId(1001L);
            return 1;
        }).when(messageMapper).save(any(SysNotifyMessage.class));
        NotifySendRequest req = allRequest();
        req.setLinkUrl("/bpmn/task");

        NotifySendResultVo result = service.saveNotify(req);

        assertThat(result.getMessageId()).isEqualTo(1001L);
        assertThat(result.getReceiverCount()).isEqualTo(2);
        ArgumentCaptor<SysNotifyMessage> messageCaptor = ArgumentCaptor.forClass(SysNotifyMessage.class);
        verify(messageMapper).save(messageCaptor.capture());
        SysNotifyMessage saved = messageCaptor.getValue();
        // 消息内容为解析后快照 + 模板 type/level/linkUrl/receiverType/receiver_count 快照
        assertThat(saved.getTitle()).isEqualTo("审批待办提醒");
        assertThat(saved.getContent()).isEqualTo("您好 张三，您有一条新的审批任务待处理。");
        assertThat(saved.getType()).isEqualTo(SysNotifyTemplate.TypeEnum.REMIND.getCode());
        assertThat(saved.getLevel()).isEqualTo(SysNotifyTemplate.LevelEnum.IMPORTANT.getCode());
        assertThat(saved.getLinkUrl()).isEqualTo("/bpmn/task");
        assertThat(saved.getReceiverType()).isEqualTo(SysNotifyMessage.ReceiverTypeEnum.ALL.getCode());
        assertThat(saved.getReceiverCount()).isEqualTo(2);
    }

    @Test
    void saveNotify_over500ReceiversSplitIntoBatches() {
        stubTemplateFound();
        List<Long> receivers = LongStream.rangeClosed(1, 501).boxed().toList();
        when(userMapper.listActiveUserIds()).thenReturn(receivers);
        when(statMapper.updateIncrease(anyLong(), anyInt(), anyLong())).thenReturn(1);

        service.saveNotify(allRequest());

        // 501 = 500 + 1 两批；批大小上限 500（BATCH_SIZE 常量由实现固定，此处按 500 断言）
        ArgumentCaptor<List<SysNotifyUserMessage>> captor = batchCaptor();
        verify(userMessageMapper, times(2)).saveBatch(captor.capture());
        assertThat(captor.getAllValues().get(0)).hasSize(BATCH_SIZE);
        assertThat(captor.getAllValues().get(1)).hasSize(1);
        // 统计维护 Java 按用户分组：501 个用户各一次递增（发送去重保证每用户单行）
        verify(statMapper, times(501)).updateIncrease(anyLong(), anyInt(), anyLong());
    }

    @Test
    void saveNotify_increasesStatPerUserWithOwnMaxIdAsWatermark() {
        stubTemplateFound();
        when(userMapper.listActiveUserIds()).thenReturn(List.of(1L, 2L, 3L));
        // 模拟 useGeneratedKeys 回填收件行 id（4001-4003），水位断言才有真实输入
        doAnswer(inv -> {
            List<SysNotifyUserMessage> rows = inv.getArgument(0);
            for (int i = 0; i < rows.size(); i++) {
                rows.get(i).setId(4001L + i);
            }
            return rows.size();
        }).when(userMessageMapper).saveBatch(anyList());
        when(statMapper.updateIncrease(anyLong(), anyInt(), anyLong())).thenReturn(1);

        service.saveNotify(allRequest());

        // 按用户分组递增：每用户一次 updateIncrease(userId, 批内该用户收件数, 该用户最大收件行 id)
        verify(statMapper).updateIncrease(1L, 1, 4001L);
        verify(statMapper).updateIncrease(2L, 1, 4002L);
        verify(statMapper).updateIncrease(3L, 1, 4003L);
        // 统计行已存在（递增命中 1 行），不走建行分支
        verify(statMapper, never()).save(any(SysNotifyUserStat.class));
    }

    @Test
    void saveNotify_missingStatRowCreatesRowBySave() {
        stubTemplateFound();
        when(userMapper.listActiveUserIds()).thenReturn(List.of(1L));
        doAnswer(inv -> {
            List<SysNotifyUserMessage> rows = inv.getArgument(0);
            rows.get(0).setId(4001L);
            return 1;
        }).when(userMessageMapper).saveBatch(anyList());
        // 统计行不存在（该用户首条收件）：递增 0 行 → save 建行
        when(statMapper.updateIncrease(1L, 1, 4001L)).thenReturn(0);

        service.saveNotify(allRequest());

        ArgumentCaptor<SysNotifyUserStat> captor = ArgumentCaptor.forClass(SysNotifyUserStat.class);
        verify(statMapper).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
        assertThat(captor.getValue().getUnreadCount()).isEqualTo(1);
        assertThat(captor.getValue().getLastReceiptId()).isEqualTo(4001L);
    }

    @Test
    void saveNotify_duplicateKeyOnStatRowSaveRetriesIncrease() {
        stubTemplateFound();
        when(userMapper.listActiveUserIds()).thenReturn(List.of(1L));
        doAnswer(inv -> {
            List<SysNotifyUserMessage> rows = inv.getArgument(0);
            rows.get(0).setId(4001L);
            return 1;
        }).when(userMessageMapper).saveBatch(anyList());
        // 并发同用户首建：递增 0 行 + save 撞主键 → 吸收冲突重试递增一次（计数不丢）
        when(statMapper.updateIncrease(1L, 1, 4001L)).thenReturn(0);
        doThrow(new DuplicateKeyException("PRIMARY")).when(statMapper).save(any(SysNotifyUserStat.class));

        service.saveNotify(allRequest());

        verify(statMapper, times(2)).updateIncrease(1L, 1, 4001L);
    }

    @Test
    void saveNotify_duplicateKeyOnBatchAbsorbed() {
        stubTemplateFound();
        when(userMapper.listActiveUserIds()).thenReturn(List.of(1L, 2L));
        // uk(message_id, user_id) 防御层：发送前已 Set 去重，常态不触发；触发时吸收不阻断整次发送
        doThrow(new DuplicateKeyException("uk_message_user")).when(userMessageMapper)
                .saveBatch(anyList());

        NotifySendRequest req = allRequest();

        assertThatCode(() -> service.saveNotify(req)).doesNotThrowAnyException();
        verify(messageMapper).save(any(SysNotifyMessage.class));
        // 冲突批收件行未落库，统计一并跳过（漂移由每日对账收敛）
        verify(statMapper, never()).updateIncrease(anyLong(), anyInt(), anyLong());
    }

    @Test
    void saveNotify_extraVariablesIgnored() {
        stubTemplateFound();
        when(userMapper.listActiveUserIds()).thenReturn(List.of(1L));
        NotifySendRequest req = allRequest();
        // 多余变量忽略（前向兼容）：只提供模板声明变量 + 1 个多余键仍成功
        req.setVariables(Map.of("nickname", "张三", "future_var", "x"));

        NotifySendResultVo result = service.saveNotify(req);

        assertThat(result.getReceiverCount()).isEqualTo(1);
    }

    @Test
    void saveNotify_userModeAllInvalidRejectedAsEmpty() {
        stubTemplateFound();
        when(userMapper.listActiveUserIds()).thenReturn(List.of(1L));
        NotifySendRequest req = allRequest();
        req.setReceiverType(SysNotifyMessage.ReceiverTypeEnum.USER.getCode());
        req.setUserIds(List.of(99L));

        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("接收人列表为空");
        verify(messageMapper, never()).save(any(SysNotifyMessage.class));
    }

    @Test
    void saveNotify_roleModeNoValidMemberRejectedAsEmpty() {
        stubTemplateFound();
        when(userMapper.listUserIdsByRoleIds(List.of(9L))).thenReturn(new ArrayList<>());
        NotifySendRequest req = allRequest();
        req.setReceiverType(SysNotifyMessage.ReceiverTypeEnum.ROLE.getCode());
        req.setRoleIds(List.of(9L));

        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("接收人列表为空");
    }

    @Test
    void saveNotify_titleTooLongRejected() {
        SysNotifyTemplate t = templateWithVar();
        t.setTitleTemplate("${head}");
        t.setContentTemplate("plain content");
        when(templateMapper.findById(2L)).thenReturn(t);
        NotifySendRequest req = allRequest();
        req.setVariables(Map.of("head", "t".repeat(101)));

        assertThatThrownBy(() -> service.saveNotify(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超出长度限制");
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<SysNotifyUserMessage>> batchCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }
}
