package com.cloudai.system.service;

import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.inner.config.InnerProperties;
import com.cloudai.common.inner.core.InnerActiveSecretSet;
import com.cloudai.common.inner.core.RedisInnerSecretSource;
import com.cloudai.system.config.InnerRotationProperties;
import com.cloudai.system.dto.NotifySendRequest;
import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.entity.SysSharedSecret;
import com.cloudai.system.mapper.SysNotifyTemplateMapper;
import com.cloudai.system.mapper.SysSharedSecretMapper;
import com.cloudai.system.vo.InnerSecretStatusVo;
import com.cloudai.system.vo.InnerSecretVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 密钥生命周期单测（契约 2026-11-09-inner-secret-admin-api §3-§5 / inner 契约 v2 §2）：
 * 轮换单一路径（频控 3053 / DuplicateKey 兜底 3053 / 落库后发布、发布失败告警+3052 软失败）、
 * 作废三分支（3050 终态 / 3051 最后一把 / 正常终态+发布）、任务编排（清扫归一 / 自愈重发跳过未轮换 /
 * 提前预算触发 / 活跃集空告警 / 站内信去重）、状态卡字段与 redisConsistent、静态过渡项附加条件。
 * self 用 mock 隔离代理边界（单测无 Spring 代理，rotateInTx 直测真实方法）。
 */
@ExtendWith(MockitoExtension.class)
class InnerSecretServiceTest {

    private static final String KEY = RedisInnerSecretSource.ACTIVE_SET_KEY;
    private static final int CODE_ROTATE_TOO_FREQUENT = 3053;
    private static final int CODE_PUBLISH_FAIL = 3052;
    private static final int CODE_NOT_FOUND = 3050;
    private static final int CODE_LAST_LIVE = 3051;

    @Mock
    private SysSharedSecretMapper secretMapper;
    @Mock
    private SysNotifyTemplateMapper templateMapper;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private NotifySendService notifySendService;
    @Mock
    private InnerSecretService selfStub;

    private final InnerRotationProperties rotationProperties = new InnerRotationProperties();
    private final InnerProperties innerProperties = new InnerProperties();
    private final ObjectMapper jsonParser = new ObjectMapper();

    private InnerSecretService service;

    @BeforeEach
    void setUp() {
        // Lombok @RequiredArgsConstructor 六参 + self 字段注入（@Lazy 字段，单测直塞 mock）
        service = new InnerSecretService(secretMapper, templateMapper, redisTemplate,
                rotationProperties, innerProperties, notifySendService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "self", selfStub);
    }

    // ---- 测试数据工厂 ----

    /** 活跃行（status 1，seq 指定，寿命 100h 远离轮换预算） */
    private SysSharedSecret activeRow(long id, int seq) {
        SysSharedSecret row = new SysSharedSecret();
        row.setId(id);
        row.setSeq(seq);
        row.setStatus(SysSharedSecret.StatusEnum.ACTIVE.getCode());
        row.setSecretValue("secret-value-seq-" + seq + "-tail99");
        row.setActivateTime(LocalDateTime.now().minusHours(1));
        row.setExpireTime(LocalDateTime.now().plusHours(100));
        return row;
    }

    /** 重叠期行（status 2，退场时刻由参数决定） */
    private SysSharedSecret overlapRow(long id, int seq, LocalDateTime retireAt) {
        SysSharedSecret row = activeRow(id, seq);
        row.setStatus(SysSharedSecret.StatusEnum.OVERLAP.getCode());
        row.setRetireTime(retireAt);
        return row;
    }

    private void stubValueOps() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    /** 解析发布到 Redis 的活跃集 JSON */
    private InnerActiveSecretSet capturePublishedSet() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(valueOps).set(eq(KEY), captor.capture());
        try {
            return jsonParser.readValue(captor.getValue(), InnerActiveSecretSet.class);
        } catch (Exception e) {
            throw new IllegalStateException("活跃集 JSON 解析失败", e);
        }
    }

    // ---- 轮换（单一代码路径）----

    @Test
    void rotateInTx_declaresTransactionalOnMultiStatementWrite() throws Exception {
        // 多语句写（顶替 UPDATE × N + INSERT）必须 @Transactional——部分成功会留脏状态
        Transactional tx = InnerSecretService.class
                .getMethod("rotateInTx", String.class, int.class)
                .getAnnotation(Transactional.class);
        assertThat(tx).isNotNull();
        assertThat(tx.rollbackFor()).contains(Exception.class);
    }

    @Test
    void rotateInTx_demotesPredecessorAndInsertsNewRow() {
        SysSharedSecret predecessor = activeRow(10L, 2);
        when(secretMapper.findMaxSeq(anyString())).thenReturn(2L);
        when(secretMapper.listBySceneStatus(anyString(), anyList()))
                .thenReturn(List.of())            // 频控查重叠行：无
                .thenReturn(List.of(predecessor)); // 顶替查活跃行
        LocalDateTime before = LocalDateTime.now();

        SysSharedSecret created = service.rotateInTx("admin", SysSharedSecret.SourceEnum.MANUAL.getCode());

        assertThat(created.getSeq()).isEqualTo(3);
        assertThat(created.getStatus()).isEqualTo(SysSharedSecret.StatusEnum.ACTIVE.getCode());
        assertThat(created.getSecretValue()).hasSize(44);
        ArgumentCaptor<SysSharedSecret> insertCaptor = ArgumentCaptor.forClass(SysSharedSecret.class);
        verify(secretMapper).save(insertCaptor.capture());
        assertThat(insertCaptor.getValue().getCreateBy()).isEqualTo("admin");
        assertThat(insertCaptor.getValue().getExpireTime())
                .isAfter(before.plusHours(rotationProperties.getValidityHours() - 1));
        // 前任 1→2，退场 = now + 重叠窗（180s）
        ArgumentCaptor<LocalDateTime> retireCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(secretMapper).updateStatus(eq(10L), eq(SysSharedSecret.StatusEnum.OVERLAP.getCode()),
                retireCaptor.capture(), eq("admin"));
        assertThat(retireCaptor.getValue())
                .isAfter(before.plusSeconds(rotationProperties.getOverlapSeconds() - 10))
                .isBefore(before.plusSeconds(rotationProperties.getOverlapSeconds() + 10));
    }

    @Test
    void rotateInTx_blockedWhileOverlapWindowOpen_3053() {
        SysSharedSecret overlap = overlapRow(9L, 1, LocalDateTime.now().plusSeconds(120));
        when(secretMapper.listBySceneStatus(anyString(), anyList()))
                .thenReturn(List.of(overlap));
        lenient().when(secretMapper.findMaxSeq(anyString())).thenReturn(1L);

        assertThatThrownBy(() -> service.rotateInTx("admin", SysSharedSecret.SourceEnum.MANUAL.getCode()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重叠窗口未结束")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(CODE_ROTATE_TOO_FREQUENT);
        verify(secretMapper, never()).save(any());
    }

    @Test
    void rotateInTx_concurrentDuplicateKey_translatesTo3053() {
        when(secretMapper.findMaxSeq(anyString())).thenReturn(1L);
        when(secretMapper.listBySceneStatus(anyString(), anyList()))
                .thenReturn(List.of())
                .thenReturn(List.of());
        when(secretMapper.save(any())).thenThrow(new DuplicateKeyException("uk_scene_seq"));

        assertThatThrownBy(() -> service.rotateInTx("admin", SysSharedSecret.SourceEnum.MANUAL.getCode()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(CODE_ROTATE_TOO_FREQUENT);
    }

    @Test
    void rotateSecret_publishesAfterDbWrite_andMasksInVo() {
        SysSharedSecret created = activeRow(21L, 4);
        when(selfStub.rotateInTx(anyString(), anyInt())).thenReturn(created);
        when(secretMapper.findMaxSeq(anyString())).thenReturn(4L);
        when(secretMapper.listBySceneStatus(anyString(), anyList()))
                .thenReturn(List.of(created));
        stubValueOps();

        InnerSecretVo vo = service.rotateSecret("admin", SysSharedSecret.SourceEnum.MANUAL.getCode());

        // 发布在 DB 写之后（编排顺序：self.rotateInTx → publishActiveSet）
        verify(selfStub).rotateInTx("admin", SysSharedSecret.SourceEnum.MANUAL.getCode());
        InnerActiveSecretSet published = capturePublishedSet();
        assertThat(published.version()).isEqualTo(4L);
        assertThat(published.secrets()).hasSize(1);
        assertThat(published.secrets().get(0).value()).isEqualTo(created.getSecretValue());
        // VO 只出掩码：****+尾4字符（值尾 "tail99" → "il99"），永不含明文
        assertThat(vo.getSecretMask()).isEqualTo("****il99")
                .doesNotContain(created.getSecretValue());
    }

    @Test
    void rotateSecret_publishFailAfter3Attempts_alertsAdminAndThrows3052() {
        SysSharedSecret created = activeRow(21L, 4);
        when(selfStub.rotateInTx(anyString(), anyInt())).thenReturn(created);
        when(secretMapper.findMaxSeq(anyString())).thenReturn(4L);
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of(created));
        stubValueOps();
        org.mockito.Mockito.doThrow(new IllegalStateException("redis down"))
                .when(valueOps).set(eq(KEY), anyString());
        when(valueOps.setIfAbsent(contains("inner:secret:alert:"), anyString(), any()))
                .thenReturn(true);
        SysNotifyTemplate template = new SysNotifyTemplate();
        template.setId(4L);
        when(templateMapper.findByCode("inner_secret_alert")).thenReturn(template);

        assertThatThrownBy(() -> service.rotateSecret("admin", SysSharedSecret.SourceEnum.MANUAL.getCode()))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(CODE_PUBLISH_FAIL);
        // 重试满 3 次后才告警 + 抛 3052；落库不回滚（save 已发生，无补偿删除）
        verify(valueOps, times(3)).set(eq(KEY), anyString());
        ArgumentCaptor<NotifySendRequest> alertCaptor = ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(notifySendService).saveNotify(alertCaptor.capture(), eq(InnerSecretService.JOB_OPERATOR));
        assertThat(alertCaptor.getValue().getTemplateId()).isEqualTo(4L);
        assertThat(alertCaptor.getValue().getVariables()).containsKey("content");
    }

    // ---- 作废 ----

    @Test
    void revokeSecret_notFoundOrTerminal_3050() {
        when(secretMapper.findById(99L)).thenReturn(null);
        assertThatThrownBy(() -> service.revokeSecret(99L, "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(CODE_NOT_FOUND);

        SysSharedSecret expired = activeRow(8L, 1);
        expired.setStatus(SysSharedSecret.StatusEnum.EXPIRED.getCode());
        when(secretMapper.findById(8L)).thenReturn(expired);
        assertThatThrownBy(() -> service.revokeSecret(8L, "admin"))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(CODE_NOT_FOUND);
        verify(secretMapper, never()).updateStatus(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    void revokeSecret_lastLiveKeyRejected_3051() {
        SysSharedSecret only = activeRow(20L, 5);
        when(secretMapper.findById(20L)).thenReturn(only);
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of(only));

        assertThatThrownBy(() -> service.revokeSecret(20L, "admin"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("最后一把")
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(CODE_LAST_LIVE);
        verify(secretMapper, never()).updateStatus(anyLong(), anyInt(), any(), anyString());
    }

    @Test
    void revokeSecret_withLiveSuccessor_marksRevokedAndPublishes() {
        SysSharedSecret target = overlapRow(20L, 5, LocalDateTime.now().plusSeconds(60));
        SysSharedSecret successor = activeRow(21L, 6);
        when(secretMapper.findById(20L)).thenReturn(target);
        when(secretMapper.listBySceneStatus(anyString(), anyList()))
                .thenReturn(List.of(successor, target))
                .thenReturn(List.of(successor));
        when(secretMapper.findMaxSeq(anyString())).thenReturn(6L);
        stubValueOps();

        service.revokeSecret(20L, "admin");

        verify(secretMapper).updateStatus(eq(20L), eq(SysSharedSecret.StatusEnum.REVOKED.getCode()),
                any(LocalDateTime.class), eq("admin"));
        InnerActiveSecretSet published = capturePublishedSet();
        assertThat(published.version()).isEqualTo(6L);
        assertThat(published.secrets()).extracting(InnerActiveSecretSet.SecretEntry::value)
                .containsExactly(successor.getSecretValue());
    }

    // ---- 任务编排 ----

    @Test
    void sweepAndRotate_sweepsExpiredAndSkipsFresh() {
        SysSharedSecret expiredOverlap = overlapRow(9L, 1, LocalDateTime.now().minusSeconds(10));
        SysSharedSecret fresh = activeRow(10L, 2);
        when(secretMapper.listBySceneStatus(anyString(), anyList()))
                .thenReturn(List.of(expiredOverlap, fresh));
        when(secretMapper.findMaxSeq(anyString())).thenReturn(2L);
        when(secretMapper.findLatestActive(anyString())).thenReturn(fresh);
        stubValueOps();

        service.sweepAndRotate();

        verify(secretMapper).updateStatus(eq(9L), eq(SysSharedSecret.StatusEnum.EXPIRED.getCode()),
                eq(null), eq(InnerSecretService.JOB_OPERATOR));
        // 未到退场时刻的行不被动；fresh 远未到预算（100h > 24h）不轮换
        verify(secretMapper, never()).save(any());
        verify(selfStub, never()).rotateInTx(anyString(), anyInt());
        // 自愈重发发生（曾轮换过）：version=2 单钥活跃集
        assertThat(capturePublishedSet().version()).isEqualTo(2L);
    }

    @Test
    void sweepAndRotate_rotatesWhenLatestWithinLeadHours() {
        SysSharedSecret latest = activeRow(10L, 2);
        latest.setExpireTime(LocalDateTime.now().plusHours(10));
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of(latest));
        when(secretMapper.findMaxSeq(anyString())).thenReturn(2L);
        when(secretMapper.findLatestActive(anyString())).thenReturn(latest);
        when(selfStub.rotateInTx(anyString(), anyInt())).thenReturn(activeRow(11L, 3));
        stubValueOps();

        service.sweepAndRotate();

        verify(selfStub).rotateInTx(InnerSecretService.JOB_OPERATOR, SysSharedSecret.SourceEnum.JOB.getCode());
    }

    @Test
    void sweepAndRotate_neverRotated_neitherPublishNorAlert() {
        when(secretMapper.findMaxSeq(anyString())).thenReturn(null);
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of());
        when(secretMapper.findLatestActive(anyString())).thenReturn(null);

        service.sweepAndRotate();

        // 静态兜底模式是合法态：不发布（发布空集会砖掉兜底通路）、不告警、不自动轮换
        verify(valueOps, never()).set(anyString(), anyString());
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any());
        verify(notifySendService, never()).saveNotify(any(), anyString());
    }

    @Test
    void sweepAndRotate_activeSetEmptyAfterRotation_alertsAdminRole() {
        when(secretMapper.findMaxSeq(anyString())).thenReturn(3L);
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of());
        when(secretMapper.findLatestActive(anyString())).thenReturn(null);
        stubValueOps();
        when(valueOps.setIfAbsent(contains("inner:secret:alert:empty-set"), anyString(), any()))
                .thenReturn(true);
        SysNotifyTemplate template = new SysNotifyTemplate();
        template.setId(4L);
        when(templateMapper.findByCode("inner_secret_alert")).thenReturn(template);

        service.sweepAndRotate();

        // 曾轮换过且活跃集空：显式发布空集（=全体 401 信号）+ admin 角色告警站内信
        ArgumentCaptor<NotifySendRequest> alertCaptor = ArgumentCaptor.forClass(NotifySendRequest.class);
        verify(notifySendService).saveNotify(alertCaptor.capture(), eq(InnerSecretService.JOB_OPERATOR));
        assertThat(alertCaptor.getValue().getRoleIds()).containsExactly(1L);
        assertThat(alertCaptor.getValue().getLinkUrl()).isEqualTo("/system/inner-secret");
    }

    @Test
    void alertAdmin_dedupMarkedWithinTtl_suppressesRepeat() {
        stubValueOps();
        when(valueOps.setIfAbsent(anyString(), anyString(), any())).thenReturn(false);

        service.alertAdmin("empty-set", "活跃密钥集为空");

        verify(notifySendService, never()).saveNotify(any(), anyString());
    }

    // ---- 状态卡与发布细节 ----

    @Test
    void findRotationStatus_reportsParamsSeqAndRedisConsistency() {
        SysSharedSecret latest = activeRow(10L, 7);
        when(secretMapper.findLatestActive(anyString())).thenReturn(latest);
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of(latest));
        when(secretMapper.findMaxSeq(anyString())).thenReturn(7L);
        stubValueOps();
        when(valueOps.get(KEY)).thenReturn("{\"version\":7,\"secrets\":[]}");

        InnerSecretStatusVo vo = service.findRotationStatus();

        assertThat(vo.getValidityHours()).isEqualTo(168);
        assertThat(vo.getOverlapSeconds()).isEqualTo(180);
        assertThat(vo.getLeadHours()).isEqualTo(24);
        assertThat(vo.getCurrentSeq()).isEqualTo(7);
        assertThat(vo.getActiveCount()).isEqualTo(1);
        assertThat(vo.getNextRotationTime())
                .isEqualTo(latest.getExpireTime().minusHours(rotationProperties.getLeadHours()));
        assertThat(vo.getRedisVersion()).isEqualTo(7);
        assertThat(vo.getRedisConsistent()).isTrue();
    }

    @Test
    void findRotationStatus_nothingRotated_redisAbsentInconsistent() {
        when(secretMapper.findLatestActive(anyString())).thenReturn(null);
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of());
        when(secretMapper.findMaxSeq(anyString())).thenReturn(null);
        stubValueOps();
        when(valueOps.get(KEY)).thenReturn(null);

        InnerSecretStatusVo vo = service.findRotationStatus();

        assertThat(vo.getCurrentSeq()).isEqualTo(0);
        assertThat(vo.getNextRotationTime()).isNull();
        assertThat(vo.getActiveCount()).isEqualTo(0);
        assertThat(vo.getRedisVersion()).isNull();
        assertThat(vo.getRedisConsistent()).isFalse();
    }

    @Test
    void publishActiveSet_appendsStaticBridgeOnlyWhenKeyMissing() {
        innerProperties.setSharedSecret("static-bridge-secret");
        SysSharedSecret live = activeRow(10L, 4);
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of(live));
        when(secretMapper.findMaxSeq(anyString())).thenReturn(4L);
        stubValueOps();
        long before = System.currentTimeMillis();
        when(redisTemplate.hasKey(KEY)).thenReturn(false);

        service.publishActiveSet();

        InnerActiveSecretSet published = capturePublishedSet();
        assertThat(published.secrets()).hasSize(2);
        // 首位仍是最新钥；过渡项在集尾，寿命 = 重叠窗 + 2×缓存TTL（180+60s）
        assertThat(published.secrets().get(0).value()).isEqualTo(live.getSecretValue());
        long bridgeExpire = published.secrets().get(1).expireAtMs();
        long expectAt = before + rotationProperties.getOverlapSeconds() * 1000L
                + innerProperties.getCacheTtlSeconds() * 2 * 1000L;
        assertThat(bridgeExpire).isBetween(expectAt - 5000, expectAt + 5000);
    }

    @Test
    void publishActiveSet_keyExists_noStaticBridgeAppended() {
        innerProperties.setSharedSecret("static-bridge-secret");
        SysSharedSecret live = activeRow(10L, 4);
        when(secretMapper.listBySceneStatus(anyString(), anyList())).thenReturn(List.of(live));
        when(secretMapper.findMaxSeq(anyString())).thenReturn(4L);
        stubValueOps();
        when(redisTemplate.hasKey(KEY)).thenReturn(true);

        service.publishActiveSet();

        // 键已存在（常态重发/自愈）：不附加过渡项，集合只含 DB 活跃行
        assertThat(capturePublishedSet().secrets()).hasSize(1);
    }
}
