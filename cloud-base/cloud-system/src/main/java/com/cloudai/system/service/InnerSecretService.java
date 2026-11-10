package com.cloudai.system.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.cloudai.common.core.domain.PageResult;
import com.cloudai.common.core.exception.BusinessException;
import com.cloudai.common.inner.config.InnerProperties;
import com.cloudai.common.inner.core.InnerActiveSecretSet;
import com.cloudai.common.inner.core.RedisInnerSecretSource;
import com.cloudai.system.config.InnerRotationProperties;
import com.cloudai.system.convert.InnerSecretConvert;
import com.cloudai.system.dto.InnerSecretPageQuery;
import com.cloudai.system.dto.NotifySendRequest;
import com.cloudai.system.entity.SysNotifyMessage;
import com.cloudai.system.entity.SysNotifyTemplate;
import com.cloudai.system.entity.SysSharedSecret;
import com.cloudai.system.mapper.SysNotifyTemplateMapper;
import com.cloudai.system.mapper.SysSharedSecretMapper;
import com.cloudai.system.vo.InnerSecretStatusVo;
import com.cloudai.system.vo.InnerSecretVo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * inner 面共享密钥生命周期服务（契约 2026-11-09-inner-secret-admin-api / 运行时语义
 * 2026-11-09-inner-access-secret-api v2；设计 docs/superpowers/specs/2026-11-09-inner-secret-rotation-design.md）。
 * 轮换单一代码路径：手动端点与定时任务共用 rotateSecret（生成新钥即刻生效、前任进重叠期）；
 * 活跃集发布在 DB 落库之后（发布失败 3052 软失败——不回滚已生效变更，任务一分钟内自愈重发）。
 * 真值红线：密钥明文仅存 DB/Redis 活跃集/静态 Nacos 键三处，不入日志、任何接口只出掩码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InnerSecretService {

    // ---- 错误码（admin 契约 §5：system 域 3xxx 接续 3049）----
    private static final int ERR_SECRET_NOT_FOUND = 3050;
    private static final int ERR_REVOKE_LAST_LIVE = 3051;
    private static final int ERR_PUBLISH_FAIL = 3052;
    private static final int ERR_ROTATE_TOO_FREQUENT = 3053;

    /** 密钥场景：服务间 inner 面（表按 scene 预留多场景复用，本套生命周期当前只服务 inner） */
    private static final String SCENE_INNER = "inner";

    /** 定时任务操作人（审计列与告警站内信 operator）＝ xxl-job handler 名，admin 契约 §1 示例同名 */
    public static final String JOB_OPERATOR = "innerSecretRotateJobHandler";

    /** 告警模板 code（种子 id 4：提醒/重要/变量 content——admin 契约 §6，非端点面） */
    private static final String ALERT_TEMPLATE_CODE = "inner_secret_alert";
    /** 告警站内信去重键前缀：tag 区分告警类型，SETNX + 1h TTL 防刷屏 */
    private static final String ALERT_DEDUP_PREFIX = "inner:secret:alert:";
    private static final String ALERT_TAG_EMPTY_SET = "empty-set";
    private static final String ALERT_TAG_PUBLISH_FAIL = "publish-fail";
    private static final Duration ALERT_DEDUP_TTL = Duration.ofHours(1);
    /** 告警接收角色：admin（内置角色 id 1） */
    private static final long ALERT_ROLE_ID = 1L;
    /** 告警站内信跳转：密钥管理页（前端相对路由） */
    private static final String ADMIN_PAGE_URL = "/system/inner-secret";

    /** 活跃集发布连续失败上限（超限告警并抛 3052；任务路径下一分钟自愈重发） */
    private static final int PUBLISH_MAX_ATTEMPTS = 3;
    /** 新钥随机字节数（Base64 后约 44 字符） */
    private static final int NEW_SECRET_BYTES = 32;

    private final SysSharedSecretMapper secretMapper;
    private final SysNotifyTemplateMapper templateMapper;
    private final StringRedisTemplate redisTemplate;
    private final InnerRotationProperties rotationProperties;
    private final InnerProperties innerProperties;
    private final NotifySendService notifySendService;

    /** 自代理：非事务编排方法内调本类 @Transactional 方法须走代理事务才生效；
     *  字段注入 + @Lazy 破自引用循环（构造器自注入 Spring 不允许，@Lazy 代理延迟解析） */
    @Lazy
    @Autowired
    private InnerSecretService self;

    /** 活跃集 JSON 独立序列化器：全局 Jackson 的 Long→String 定制会改坏 version 数值契约结构 */
    private final ObjectMapper activeSetMapper = new ObjectMapper();
    private final SecureRandom secureRandom = new SecureRandom();

    /** 密钥分页（id 倒序流水全量可见含终态行；VO 仅掩码） */
    public PageResult<InnerSecretVo> pageList(InnerSecretPageQuery query) {
        IPage<SysSharedSecret> page = secretMapper.pageList(
                new Page<>(query.getPageNum(), query.getPageSize()), query);
        List<InnerSecretVo> rows = page.getRecords().stream().map(InnerSecretConvert::toVo).toList();
        return PageResult.of(page.getTotal(), rows);
    }

    /** 轮换状态卡（admin 契约 §2 只读回显；redisConsistent=false 表示发布落后，任务一分钟内自愈） */
    public InnerSecretStatusVo findRotationStatus() {
        LocalDateTime now = LocalDateTime.now();
        InnerSecretStatusVo vo = new InnerSecretStatusVo();
        vo.setValidityHours(rotationProperties.getValidityHours());
        vo.setOverlapSeconds(rotationProperties.getOverlapSeconds());
        vo.setLeadHours(rotationProperties.getLeadHours());
        SysSharedSecret latest = secretMapper.findLatestActive(SCENE_INNER);
        vo.setCurrentSeq(latest == null ? 0 : latest.getSeq());
        vo.setNextRotationTime(latest == null ? null
                : latest.getExpireTime().minusHours(rotationProperties.getLeadHours()));
        vo.setActiveCount(listLiveRows(now).size());
        fillRedisState(vo);
        return vo;
    }

    /** 立即轮换（单一代码路径：手动端点与定时任务共用）——落库后发布，发布失败 3052 软失败不回滚 */
    public InnerSecretVo rotateSecret(String operator, int source) {
        SysSharedSecret created = self.rotateInTx(operator, source);
        publishActiveSet();
        return InnerSecretConvert.toVo(created);
    }

    /** 作废（单一代码路径同上）：目标立即移出活跃集标终态 4；发布失败 3052 软失败（作废已落库） */
    public synchronized void revokeSecret(Long id, String operator) {
        SysSharedSecret target = requireLiveSecret(id);
        assertNotLastLiveKey(target);
        secretMapper.updateStatus(id, SysSharedSecret.StatusEnum.REVOKED.getCode(),
                LocalDateTime.now(), operator);
        publishActiveSet();
    }

    /**
     * 轮换 DB 写（多语句 @Transactional 原子）：频控校验 → 顶替既有活跃行 → 插入新行。
     * synchronized 封闭同 JVM 频控竞态；跨实例并发由 uk_scene_seq + DuplicateKey 兜底（等价频控拒绝）。
     */
    @Transactional(rollbackFor = Exception.class)
    public synchronized SysSharedSecret rotateInTx(String operator, int source) {
        LocalDateTime now = LocalDateTime.now();
        assertRotationAllowed(now);
        SysSharedSecret secret = buildNewSecret(now, source);
        secret.setCreateBy(operator);
        demoteLiveRows(operator, now);
        try {
            secretMapper.save(secret);
        } catch (DuplicateKeyException e) {
            log.error("轮换并发撞 uk_scene_seq（跨实例双轮换，后到者拒绝）: scene={}", SCENE_INNER, e);
            throw new BusinessException(ERR_ROTATE_TOO_FREQUENT, "轮换请求过于频繁");
        }
        return secret;
    }

    /** 新钥行：seq=场景内 MAX+1，32 随机字节 Base64，即刻生效、寿命=有效期参数 */
    private SysSharedSecret buildNewSecret(LocalDateTime now, int source) {
        Long maxSeq = secretMapper.findMaxSeq(SCENE_INNER);
        byte[] bytes = new byte[NEW_SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        SysSharedSecret secret = new SysSharedSecret();
        secret.setScene(SCENE_INNER);
        secret.setSeq((maxSeq == null ? 0 : maxSeq.intValue()) + 1);
        secret.setSecretValue(Base64.getEncoder().encodeToString(bytes));
        secret.setStatus(SysSharedSecret.StatusEnum.ACTIVE.getCode());
        secret.setSource(source);
        secret.setActivateTime(now);
        secret.setExpireTime(now.plusHours(rotationProperties.getValidityHours()));
        return secret;
    }

    /** 频控（3053）：上一轮重叠窗未结束拒绝——存在状态 2 且退场时刻未到的行即窗口内 */
    private void assertRotationAllowed(LocalDateTime now) {
        List<SysSharedSecret> overlapRows = secretMapper.listBySceneStatus(
                SCENE_INNER, List.of(SysSharedSecret.StatusEnum.OVERLAP.getCode()));
        overlapRows.stream()
                .filter(row -> actualRetireTime(row).isAfter(now))
                .findFirst()
                .ifPresent(row -> {
                    long remainSeconds = Duration.between(now, actualRetireTime(row)).getSeconds();
                    throw new BusinessException(ERR_ROTATE_TOO_FREQUENT,
                            "轮换请求过于频繁（上一轮重叠窗口未结束，剩余约 " + remainSeconds + " 秒）");
                });
    }

    /** 顶替：状态 1 → 2（退场=now+重叠窗）；遗留的状态 2 → 3（窗口已耗尽或乱序归一） */
    private void demoteLiveRows(String operator, LocalDateTime now) {
        LocalDateTime retireAt = now.plusSeconds(rotationProperties.getOverlapSeconds());
        for (SysSharedSecret row : secretMapper.listBySceneStatus(SCENE_INNER, liveStatusCodes())) {
            if (row.getStatus() == SysSharedSecret.StatusEnum.OVERLAP.getCode()) {
                secretMapper.updateStatus(row.getId(),
                        SysSharedSecret.StatusEnum.EXPIRED.getCode(), null, operator);
            } else {
                secretMapper.updateStatus(row.getId(),
                        SysSharedSecret.StatusEnum.OVERLAP.getCode(), retireAt, operator);
            }
        }
    }

    /** 作废目标校验（3050）：须存在且状态 ∈ {1,2}——已过期/已作废是已退场终态 */
    private SysSharedSecret requireLiveSecret(Long id) {
        SysSharedSecret target = secretMapper.findById(id);
        boolean live = target != null
                && (target.getStatus() == SysSharedSecret.StatusEnum.ACTIVE.getCode()
                || target.getStatus() == SysSharedSecret.StatusEnum.OVERLAP.getCode());
        if (!live) {
            throw new BusinessException(ERR_SECRET_NOT_FOUND, "密钥记录不存在或已退场");
        }
        return target;
    }

    /** 最后一把活跃钥禁作废（3051）：作废后活跃集为空 = inner 面全体拒绝（含登录链），须先轮换 */
    private void assertNotLastLiveKey(SysSharedSecret target) {
        List<SysSharedSecret> live = listLiveRows(LocalDateTime.now());
        if (live.size() == 1 && live.get(0).getId().equals(target.getId())) {
            throw new BusinessException(ERR_REVOKE_LAST_LIVE, "禁止作废最后一把生效密钥（请先轮换）");
        }
    }

    /**
     * 轮换任务编排（xxl-job id 12 每分钟；入口不加事务——各步骤独立幂等，中断重跑无害）：
     * ①清扫到期行 ②活跃集自愈重发 ③最新钥到提前预算即轮换 ④活跃集空告警。
     */
    public void sweepAndRotate() {
        int swept = sweepExpired();
        log.info("inner secret sweep done: sweptRows={}", swept);
        selfHealRepublish();
        rotateIfNeeded();
        alertIfActiveSetEmpty();
    }

    /** 清扫：状态 1/2 且已到实际退场时刻 → 3（幂等，任意乱序/重投收敛到状态机） */
    public int sweepExpired() {
        LocalDateTime now = LocalDateTime.now();
        int swept = 0;
        for (SysSharedSecret row : secretMapper.listBySceneStatus(SCENE_INNER, liveStatusCodes())) {
            if (!actualRetireTime(row).isAfter(now)) {
                secretMapper.updateStatus(row.getId(),
                        SysSharedSecret.StatusEnum.EXPIRED.getCode(), null, JOB_OPERATOR);
                swept++;
            }
        }
        return swept;
    }

    /** 自愈重发：曾轮换过（MAX(seq)>0）则幂等重写活跃集；从未轮换保持静态兜底模式——发布空集会砖掉兜底通路 */
    private void selfHealRepublish() {
        Long maxSeq = secretMapper.findMaxSeq(SCENE_INNER);
        if (maxSeq == null || maxSeq == 0) {
            return;
        }
        try {
            publishActiveSet();
        } catch (BusinessException e) {
            log.error("活跃集自愈发布失败（发布内部已告警），等待下一分钟重试: {}", e.getMessage());
        }
    }

    /** 到预算即轮换：最新生效行距过期不足 lead 小时触发；无生效行不自动轮换（设计拍板：告警人工处置） */
    private void rotateIfNeeded() {
        SysSharedSecret latest = secretMapper.findLatestActive(SCENE_INNER);
        if (latest == null) {
            return;
        }
        LocalDateTime threshold = latest.getExpireTime().minusHours(rotationProperties.getLeadHours());
        if (!LocalDateTime.now().isBefore(threshold)) {
            rotateSecret(JOB_OPERATOR, SysSharedSecret.SourceEnum.JOB.getCode());
        }
    }

    /** 活跃集空且曾轮换过 → 告警（从未轮换的静态兜底模式是合法态不告警） */
    private void alertIfActiveSetEmpty() {
        Long maxSeq = secretMapper.findMaxSeq(SCENE_INNER);
        if (maxSeq == null || maxSeq == 0) {
            return;
        }
        if (listLiveRows(LocalDateTime.now()).isEmpty()) {
            alertAdmin(ALERT_TAG_EMPTY_SET,
                    "Redis 活跃密钥集为空（无任何生效密钥，inner 面全体拒绝），请立即手动轮换");
        }
    }

    /**
     * 活跃集全量幂等重写（inner 契约 v2 §2：DB 活跃行 → seq 降序 entries → 单键 SET）。
     * 空集显式发布 = 全体 401（区别于键不存在回落静态兜底）；静态过渡项仅切换瞬间附加（见 appendStaticBridgeEntry）。
     */
    public void publishActiveSet() {
        List<InnerActiveSecretSet.SecretEntry> entries = new ArrayList<>();
        for (SysSharedSecret row : listLiveRows(LocalDateTime.now())) {
            entries.add(new InnerActiveSecretSet.SecretEntry(
                    row.getSecretValue(), toEpochMilli(actualRetireTime(row))));
        }
        appendStaticBridgeEntry(entries);
        Long version = secretMapper.findMaxSeq(SCENE_INNER);
        publishWithRetry(writeActiveSetJson(new InnerActiveSecretSet(version == null ? 0 : version, entries)));
    }

    /** 静态过渡项：仅「键不存在→存在」切换的首次发布附加——静态兜底值并入集尾，
     *  expireAtMs = 发布时刻 + 重叠窗 + 2×缓存TTL（吸收收发双方缓存不同步的切换缝隙，耗尽后自然剔除） */
    private void appendStaticBridgeEntry(List<InnerActiveSecretSet.SecretEntry> entries) {
        String staticValue = innerProperties.getSharedSecret();
        if (staticValue == null || staticValue.isBlank() || redisKeyExists()) {
            return;
        }
        long bridgeExpireAtMs = System.currentTimeMillis()
                + rotationProperties.getOverlapSeconds() * 1000L
                + innerProperties.getCacheTtlSeconds() * 2 * 1000L;
        entries.add(new InnerActiveSecretSet.SecretEntry(staticValue, bridgeExpireAtMs));
    }

    /** 键存在性：访问异常按已存在处理（此时发布必失败走 3052，不误加过渡项） */
    private boolean redisKeyExists() {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(RedisInnerSecretSource.ACTIVE_SET_KEY));
        } catch (Exception e) {
            log.error("活跃集键存在性检查失败，按已存在处理（不附加静态过渡项）", e);
            return true;
        }
    }

    /** SET 连续失败 3 次：告警 admin + 抛 3052（软失败——变更已落库不回滚，任务自愈重发） */
    private void publishWithRetry(String json) {
        for (int attempt = 1; attempt <= PUBLISH_MAX_ATTEMPTS; attempt++) {
            try {
                redisTemplate.opsForValue().set(RedisInnerSecretSource.ACTIVE_SET_KEY, json);
                return;
            } catch (Exception e) {
                log.error("活跃集发布失败（第 {}/{} 次）: key={}",
                        attempt, PUBLISH_MAX_ATTEMPTS, RedisInnerSecretSource.ACTIVE_SET_KEY, e);
            }
        }
        alertAdmin(ALERT_TAG_PUBLISH_FAIL,
                "活跃集发布连续失败 " + PUBLISH_MAX_ATTEMPTS + " 次（Redis 不可达？），轮换任务将自动重试");
        throw new BusinessException(ERR_PUBLISH_FAIL, "活跃集发布失败（变更已落库，定时任务一分钟内自愈重发）");
    }

    /** 活跃集 JSON（独立 mapper，结构见契约 v2 §2；序列化失败按发布失败防御处理） */
    private String writeActiveSetJson(InnerActiveSecretSet activeSet) {
        try {
            return activeSetMapper.writeValueAsString(activeSet);
        } catch (JsonProcessingException e) {
            log.error("活跃集 JSON 序列化失败", e);
            throw new BusinessException(ERR_PUBLISH_FAIL, "活跃集发布失败（变更已落库，定时任务一分钟内自愈重发）");
        }
    }

    /** 活跃集 version 读取（键缺失/解析失败/访问异常 → null；状态卡仅展示，不区分失败原因） */
    private Long readActiveSetVersion() {
        try {
            String raw = redisTemplate.opsForValue().get(RedisInnerSecretSource.ACTIVE_SET_KEY);
            return raw == null ? null : activeSetMapper.readValue(raw, InnerActiveSecretSet.class).version();
        } catch (Exception e) {
            log.warn("活跃集 version 读取失败（状态卡降级为 null）: {}", e.getMessage());
            return null;
        }
    }

    /** 状态卡 Redis 两字段：version 原样回显；consistent = 键存在且 version == DB MAX(seq) */
    private void fillRedisState(InnerSecretStatusVo vo) {
        Long maxSeq = secretMapper.findMaxSeq(SCENE_INNER);
        Long redisVersion = readActiveSetVersion();
        vo.setRedisVersion(redisVersion == null ? null : redisVersion.intValue());
        vo.setRedisConsistent(redisVersion != null && maxSeq != null && redisVersion.longValue() == maxSeq);
    }

    /**
     * 告警 admin 角色：服务日志 ERROR + 站内信（模板 inner_secret_alert，linkUrl 指向管理页）。
     * 站内信按 tag SETNX 去重 1 小时（日志不去重——完整留痕）；去重键写失败仍发送（宁重复不漏报）；
     * 发送失败仅记日志，告警链路故障不反向打断轮换任务。
     */
    public void alertAdmin(String tag, String content) {
        log.error("inner 密钥告警[{}]: {}", tag, content);
        if (dedupMarked(tag)) {
            return;
        }
        SysNotifyTemplate template = templateMapper.findByCode(ALERT_TEMPLATE_CODE);
        if (template == null) {
            log.error("告警模板缺失（code={}），站内信未发送", ALERT_TEMPLATE_CODE);
            return;
        }
        try {
            notifySendService.saveNotify(buildAlertRequest(template.getId(), content), JOB_OPERATOR);
        } catch (Exception e) {
            log.error("密钥告警站内信发送失败: tag={}", tag, e);
        }
    }

    /** 告警去重：同 tag 1 小时内只发一次；Redis 异常按未标记处理（本次照发） */
    private boolean dedupMarked(String tag) {
        try {
            Boolean first = redisTemplate.opsForValue()
                    .setIfAbsent(ALERT_DEDUP_PREFIX + tag, "1", ALERT_DEDUP_TTL);
            return !Boolean.TRUE.equals(first);
        } catch (Exception e) {
            log.error("告警去重键写入失败，本次仍发送: tag={}", tag, e);
            return false;
        }
    }

    /** 告警站内信载荷：admin 角色（id 1）、变量 content、跳转密钥管理页 */
    private NotifySendRequest buildAlertRequest(Long templateId, String content) {
        NotifySendRequest req = new NotifySendRequest();
        req.setTemplateId(templateId);
        req.setReceiverType(SysNotifyMessage.ReceiverTypeEnum.ROLE.getCode());
        req.setRoleIds(List.of(ALERT_ROLE_ID));
        req.setVariables(Map.of("content", content));
        req.setLinkUrl(ADMIN_PAGE_URL);
        return req;
    }

    /** 活跃读集：状态 1/2 且未到实际退场时刻，seq 降序（首位 = 最新钥） */
    private List<SysSharedSecret> listLiveRows(LocalDateTime now) {
        return secretMapper.listBySceneStatus(SCENE_INNER, liveStatusCodes()).stream()
                .filter(row -> actualRetireTime(row).isAfter(now))
                .sorted(Comparator.comparing(SysSharedSecret::getSeq).reversed())
                .toList();
    }

    /** 状态 1/2 码集（多处共用：清扫/顶替/活跃集/作废校验） */
    private List<Integer> liveStatusCodes() {
        return List.of(SysSharedSecret.StatusEnum.ACTIVE.getCode(),
                SysSharedSecret.StatusEnum.OVERLAP.getCode());
    }

    /** 实际退场时刻 = min(expire, retire)；retire 为 null（未被顶替）即 expire */
    private LocalDateTime actualRetireTime(SysSharedSecret row) {
        LocalDateTime expire = row.getExpireTime();
        LocalDateTime retire = row.getRetireTime();
        return retire == null || retire.isAfter(expire) ? expire : retire;
    }

    /** LocalDateTime → epoch 毫秒（发布方与各消费方同宿主时区，本地时钟口径一致） */
    private long toEpochMilli(LocalDateTime time) {
        return time.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
